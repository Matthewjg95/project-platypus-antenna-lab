package dev.antennalab.core.run;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.lab.Procedure;
import dev.antennalab.core.source.CommandChannel;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes a {@link Procedure} as an automated A/B run.
 *
 * <p>This is the piece that makes Antenna Lab an instrument's pipeline rather
 * than a chart: it drives the RF switch, waits for the device to confirm, throws
 * away settle samples, collects balanced blocks, and decides at the end whether
 * the run is even quotable.
 *
 * <h2>Why blocks are interleaved</h2>
 *
 * The obvious plan — collect every chip sample, switch once, collect every
 * external sample — is the wrong one. It confounds the antenna with time: if
 * the RF environment drifts during the run (someone walks past, the AP changes
 * rate), the drift lands entirely on one antenna and is indistinguishable from
 * antenna gain. Alternating CHIP/EXT/CHIP/EXT spreads any drift across both
 * paths, which is what makes the paired comparison worth more than two separate
 * captures.
 *
 * <h2>Why a switch must be confirmed, not assumed</h2>
 *
 * Sending {@code AE} does not mean the antenna changed — the firmware queues the
 * request and applies it on its own loop. So the runner ignores every sample
 * until one arrives <em>tagged with the new path</em>, which is the device's own
 * report of physical reality. Samples that arrive during the switch are
 * discarded rather than attributed, because a sample attributed to the wrong
 * antenna is worse than a missing one.
 *
 * <h2>Why there is a closing baseline</h2>
 *
 * The run ends by returning to the chip antenna and re-measuring it. If the
 * closing baseline disagrees with the opening one by more than the instrument
 * can explain, the room moved during the run and the comparison is void — a
 * result the operator needs to be told, not spared.
 *
 * <p><b>Threading.</b> {@link #onSample} is called from the capture pipeline's
 * virtual thread at sample rate. The class is a state machine over samples with
 * no timers of its own: every deadline is measured from sample timestamps, which
 * makes the whole thing testable at full speed with no clock injection.
 */
public final class ExperimentRunner {

    /** Samples discarded after a confirmed switch, before collecting resumes. */
    public static final int SETTLE_SAMPLES = 2;

    /** Samples per block in a {@link #quickCheckPlan() quick check}. */
    public static final int QUICK_CHECK_SAMPLES = 4;

    /** How long to wait for the device to confirm a commanded switch. */
    public static final Duration SWITCH_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Largest opening-to-closing baseline drift the run tolerates, in dB.
     *
     * <p>Set above {@code MEANINGFUL_DELTA_FLOOR_DB} (2 dB): drift smaller than
     * the instrument can resolve should not void a run, but drift larger than
     * the effect being measured makes the comparison meaningless.
     */
    public static final double MAX_BASELINE_DRIFT_DB = 3.0;

    /** Where the run currently is. */
    public enum Phase {
        /** Waiting for the device to report the antenna we asked for. */
        SWITCHING,
        /** Discarding samples so the radio settles after a switch. */
        SETTLING,
        /** Accumulating samples for the current block. */
        COLLECTING,
        /** All blocks done. */
        FINISHED
    }

    /** One collection block: a target path and how many samples it wants. */
    public record Block(AntennaPath path, int target, boolean closingBaseline) {
    }

    /** What the run produced. */
    public record Outcome(boolean quotable,
                          String note,
                          List<RssiSample> samples,
                          double baselineDriftDb) {
    }

    /** Progress callbacks. All fire on the pipeline's sampling thread. */
    public interface Listener {
        /** A new phase began; {@code target} is the antenna it concerns. */
        default void onPhase(Phase phase, AntennaPath target, int blockIndex, int blockCount) {
        }

        /** Progress within the current block. */
        default void onProgress(int collected, int target) {
        }

        /**
         * The run needs the antenna changed and could not command it.
         *
         * <p>Only fires in guided mode — no command channel. The runner then
         * waits, exactly as it would for a commanded switch, until samples
         * confirm the operator actually flipped it.
         */
        default void onSwitchRequested(AntennaPath to) {
        }

        /** Terminal. */
        default void onFinished(Outcome outcome) {
        }
    }

    private final List<Block> plan;
    private final Optional<CommandChannel> commands;
    private final Listener listener;

    /** A wiring check rather than a measurement; its outcome is never quotable. */
    private final boolean diagnostic;

    private final List<RssiSample> collected = new ArrayList<>();
    private final List<Double> openingBaseline = new ArrayList<>();
    private final List<Double> closingBaseline = new ArrayList<>();

    private int blockIndex;
    private int inBlock;
    private int settleRemaining;
    private Phase phase = Phase.SWITCHING;
    private Instant switchDeadline;
    private boolean finished;

    public ExperimentRunner(List<Block> plan,
                            Optional<CommandChannel> commands,
                            Listener listener) {
        this(plan, commands, listener, false);
    }

    private ExperimentRunner(List<Block> plan,
                             Optional<CommandChannel> commands,
                             Listener listener,
                             boolean diagnostic) {
        if (plan == null || plan.isEmpty()) {
            throw new IllegalArgumentException("plan must have at least one block");
        }
        this.plan = List.copyOf(plan);
        this.commands = commands == null ? Optional.empty() : commands;
        this.listener = listener == null ? new Listener() {
        } : listener;
        this.diagnostic = diagnostic;
    }

    /** True when this run is a wiring check whose result must not be quoted. */
    public boolean isDiagnostic() {
        return diagnostic;
    }

    /**
     * Build the standard plan for a procedure: {@code blocksPerPath} alternating
     * pairs sized to reach the procedure's sample floor on each path, then a
     * closing baseline block on the chip antenna.
     */
    public static List<Block> planFor(Procedure procedure, int blocksPerPath) {
        if (procedure == null) {
            throw new IllegalArgumentException("procedure is required");
        }
        if (blocksPerPath < 1) {
            throw new IllegalArgumentException("need at least one block per path");
        }
        int floor = Math.max(1, procedure.minSamplesPerPath());
        // Round up: the floor is a minimum, so a plan that lands just under it
        // would produce a run the statistics layer refuses to grade.
        int perBlock = (floor + blocksPerPath - 1) / blocksPerPath;

        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < blocksPerPath; i++) {
            blocks.add(new Block(AntennaPath.CHIP, perBlock, false));
            blocks.add(new Block(AntennaPath.EXTERNAL, perBlock, false));
        }
        // Closing baseline is deliberately short -- it exists to detect drift,
        // not to contribute statistics, and every extra sample is bench time.
        blocks.add(new Block(AntennaPath.CHIP, Math.max(5, perBlock / 4), true));
        return blocks;
    }

    /**
     * A short plan that exercises the whole loop without measuring anything.
     *
     * <p>A real procedure floors at 100 samples per path, which is ten minutes of
     * bench time before the operator learns whether the serial link, the command
     * bytes and the switch confirmation even work. That is a terrible first thing
     * to try on new hardware: every failure mode costs ten minutes to observe.
     * This plan hits every state transition -- command, confirm, settle, collect,
     * switch again, closing baseline -- in well under a minute.
     *
     * <p>Pair it with {@link #quickCheck}, which marks the run diagnostic so its
     * outcome can never be quoted. That matters more than it looks: four samples
     * a path will happily produce a delta, and a number on screen is a number
     * someone will write down.
     */
    public static List<Block> quickCheckPlan() {
        return List.of(
                new Block(AntennaPath.CHIP, QUICK_CHECK_SAMPLES, false),
                new Block(AntennaPath.EXTERNAL, QUICK_CHECK_SAMPLES, false),
                new Block(AntennaPath.CHIP, QUICK_CHECK_SAMPLES, true));
    }

    /**
     * Build a runner for {@link #quickCheckPlan()} whose outcome is never quotable.
     *
     * <p>This is a factory rather than a flag on the constructor so the plan and
     * its diagnostic status cannot be separated by accident.
     */
    public static ExperimentRunner quickCheck(Optional<CommandChannel> commands,
                                              Listener listener) {
        return new ExperimentRunner(quickCheckPlan(), commands, listener, true);
    }

    /** Begin: request the first block's antenna. */
    public void start() {
        blockIndex = 0;
        beginBlock();
    }

    /** True once the run has produced its outcome. */
    public boolean isFinished() {
        return finished;
    }

    /** The plan being executed. */
    public List<Block> plan() {
        return plan;
    }

    /**
     * Feed one sample from the capture pipeline.
     *
     * <p>Ignored once finished, so a pipeline that keeps running after the run
     * completes cannot corrupt the result.
     */
    public void onSample(RssiSample sample) {
        if (finished || sample == null) {
            return;
        }
        Block block = plan.get(blockIndex);

        switch (phase) {
            case SWITCHING -> {
                // Arm the timeout from the first sample after the request, not
                // from when the request was sent: a run started before the
                // device is producing must not time out against a deadline that
                // was already running while nothing could confirm anything.
                if (switchDeadline == null) {
                    switchDeadline = sample.timestamp().plus(SWITCH_TIMEOUT);
                }
                if (sample.antenna() == block.path()) {
                    // The device reported the antenna we asked for: physical
                    // reality, not our own optimism about the command.
                    settleRemaining = SETTLE_SAMPLES;
                    setPhase(Phase.SETTLING, block);
                    // This sample arrived at the moment of the switch; it is the
                    // first settle sample, not data.
                    settleRemaining--;
                } else if (sample.timestamp().isAfter(switchDeadline)) {
                    finish(false, "antenna never switched to " + block.path()
                            + " within " + SWITCH_TIMEOUT.toSeconds() + "s");
                }
            }
            case SETTLING -> {
                if (sample.antenna() != block.path()) {
                    return; // stale sample from the previous antenna
                }
                if (settleRemaining > 0) {
                    settleRemaining--;
                } else {
                    setPhase(Phase.COLLECTING, block);
                    accept(sample, block);
                }
            }
            case COLLECTING -> {
                if (sample.antenna() != block.path()) {
                    // The antenna changed under us -- someone tapped the screen
                    // mid-block. Attributing these would corrupt the block, so
                    // the run stops and says why.
                    finish(false, "antenna changed to " + sample.antenna()
                            + " during a " + block.path() + " block");
                    return;
                }
                accept(sample, block);
            }
            case FINISHED -> {
                // nothing to do
            }
        }
    }

    private void accept(RssiSample sample, Block block) {
        collected.add(sample);
        inBlock++;
        if (block.closingBaseline()) {
            closingBaseline.add(sample.rssiDbm());
        } else if (blockIndex == 0) {
            openingBaseline.add(sample.rssiDbm());
        }
        listener.onProgress(inBlock, block.target());

        if (inBlock >= block.target()) {
            blockIndex++;
            if (blockIndex >= plan.size()) {
                concludeRun();
            } else {
                beginBlock();
            }
        }
    }

    private void beginBlock() {
        Block block = plan.get(blockIndex);
        inBlock = 0;
        setPhase(Phase.SWITCHING, block);
        switchDeadline = null;

        if (commands.isPresent()) {
            try {
                commands.get().sendCommand(block.path() == AntennaPath.EXTERNAL
                        ? CommandChannel.CMD_ANT_EXTERNAL
                        : CommandChannel.CMD_ANT_INTERNAL);
            } catch (IOException e) {
                finish(false, "could not command the antenna switch: " + e.getMessage());
            }
        } else {
            // No command channel: ask the operator, then wait for the same
            // confirmation a commanded switch would need. The run never takes
            // anyone's word for it.
            listener.onSwitchRequested(block.path());
        }
    }

    private void setPhase(Phase next, Block block) {
        phase = next;
        listener.onPhase(next, block.path(), blockIndex, plan.size());
    }

    /** All blocks collected: check the baselines agree, then report. */
    private void concludeRun() {
        double drift = baselineDriftDb();
        if (Double.isNaN(drift)) {
            finish(true, completionNote("no closing baseline to compare"));
            return;
        }
        if (Math.abs(drift) > MAX_BASELINE_DRIFT_DB) {
            finish(false, "baseline drifted %+.1f dB between the start and end of the run; "
                    .formatted(drift)
                    + "the RF environment changed, so the comparison is not valid");
        } else {
            finish(true, completionNote("baseline held to %+.1f dB".formatted(drift)));
        }
    }

    /**
     * The note for a run that completed cleanly.
     *
     * <p>A quick check that reached the end has proved the thing it exists to
     * prove -- the link, the command bytes, the switch confirmation and the
     * settle logic all work -- so it says so, and says just as plainly that it
     * measured nothing.
     */
    private String completionNote(String baselineDetail) {
        if (!diagnostic) {
            return "completed; " + baselineDetail;
        }
        return "wiring check passed: both antennas switched on command and returned "
                + "samples (" + baselineDetail + "). This is not a measurement -- "
                + QUICK_CHECK_SAMPLES + " samples a path is far below the floor for a "
                + "quotable delta. Run the full procedure for a result.";
    }

    /** Closing minus opening chip-antenna mean, or NaN when not computable. */
    private double baselineDriftDb() {
        if (openingBaseline.isEmpty() || closingBaseline.isEmpty()) {
            return Double.NaN;
        }
        return mean(closingBaseline) - mean(openingBaseline);
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private void finish(boolean quotable, String note) {
        if (finished) {
            return;
        }
        finished = true;
        phase = Phase.FINISHED;
        // A diagnostic run is never quotable, whatever the blocks did. Enforced
        // here rather than at the call sites so no future path can leak one.
        listener.onFinished(new Outcome(quotable && !diagnostic, note,
                List.copyOf(collected), baselineDriftDb()));
    }

    /** The phase the run is currently in. */
    public Phase phase() {
        return phase;
    }
}
