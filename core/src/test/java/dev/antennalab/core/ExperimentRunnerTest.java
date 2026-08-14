package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.lab.PlatypusCatalog;
import dev.antennalab.core.run.ExperimentRunner;
import dev.antennalab.core.source.CommandChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automated run. These tests drive the state machine with hand-fed samples
 * — no pipeline, no threads, no hardware — because every rule worth testing is
 * about <em>which samples are accepted and when</em>, and that should be
 * verifiable at full speed.
 */
class ExperimentRunnerTest {

    private static final Instant T0 = Instant.parse("2026-08-11T10:00:00Z");

    /** A command channel that records what was sent and flips a simulated antenna. */
    private static final class FakeDevice implements CommandChannel {
        final List<String> sent = new ArrayList<>();
        AntennaPath live = AntennaPath.CHIP;
        /** Samples of the OLD antenna still in flight after a command. */
        int switchLagSamples = 1;
        boolean failNextWrite;

        @Override
        public void sendCommand(byte[] bytes) throws java.io.IOException {
            if (failNextWrite) {
                throw new java.io.IOException("port closed");
            }
            String cmd = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
            sent.add(cmd);
            pending = "AE".equals(cmd) ? AntennaPath.EXTERNAL : AntennaPath.CHIP;
            lag = switchLagSamples;
        }

        private AntennaPath pending;
        private int lag;

        /** Advance the simulated device by one sample tick. */
        AntennaPath tick() {
            if (pending != null) {
                if (lag-- <= 0) {
                    live = pending;
                    pending = null;
                }
            }
            return live;
        }
    }

    /** Feeds the runner samples from a simulated device until it finishes. */
    private static ExperimentRunner.Outcome drive(ExperimentRunner runner,
                                                  FakeDevice device,
                                                  double chipDbm,
                                                  double extDbm,
                                                  int maxTicks,
                                                  AtomicReference<ExperimentRunner.Outcome> box) {
        runner.start();
        Instant t = T0;
        for (int i = 0; i < maxTicks && !runner.isFinished(); i++) {
            AntennaPath live = device.tick();
            double dbm = live == AntennaPath.CHIP ? chipDbm : extDbm;
            runner.onSample(new RssiSample(i, t, live, dbm));
            t = t.plusMillis(1500);
        }
        return box.get();
    }

    private static ExperimentRunner runnerFor(FakeDevice device,
                                              List<ExperimentRunner.Block> plan,
                                              AtomicReference<ExperimentRunner.Outcome> box) {
        return new ExperimentRunner(plan, Optional.ofNullable(device),
                new ExperimentRunner.Listener() {
                    @Override
                    public void onFinished(ExperimentRunner.Outcome outcome) {
                        box.set(outcome);
                    }
                });
    }

    private static List<ExperimentRunner.Block> smallPlan() {
        return List.of(
                new ExperimentRunner.Block(AntennaPath.CHIP, 5, false),
                new ExperimentRunner.Block(AntennaPath.EXTERNAL, 5, false),
                new ExperimentRunner.Block(AntennaPath.CHIP, 5, false),
                new ExperimentRunner.Block(AntennaPath.EXTERNAL, 5, false),
                new ExperimentRunner.Block(AntennaPath.CHIP, 3, true));
    }

    @Test
    @DisplayName("a clean run commands every switch and collects balanced blocks")
    void happyPath() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device, smallPlan(), box);

        var outcome = drive(runner, device, -35.0, -41.0, 500, box);

        assertNotNull(outcome, "the run must terminate");
        assertTrue(outcome.quotable(), outcome.note());
        // 5+5 chip in the paired blocks, 5+5 external, 3 closing baseline.
        assertEquals(13, outcome.samples().stream()
                .filter(s -> s.antenna() == AntennaPath.CHIP).count());
        assertEquals(10, outcome.samples().stream()
                .filter(s -> s.antenna() == AntennaPath.EXTERNAL).count());
        // Every block boundary issues a command, including the first.
        assertEquals(List.of("AI", "AE", "AI", "AE", "AI"), device.sent);
    }

    @Test
    @DisplayName("samples in flight during a switch are discarded, never mis-attributed")
    void switchLagSamplesAreNotAttributed() {
        FakeDevice device = new FakeDevice();
        // The firmware queues the switch and applies it on its own loop, so
        // several old-antenna samples can arrive after the command.
        device.switchLagSamples = 4;
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device, smallPlan(), box);

        var outcome = drive(runner, device, -35.0, -41.0, 500, box);

        assertTrue(outcome.quotable(), outcome.note());
        // The decisive check: no external-tagged sample carries the chip value,
        // which is exactly what mis-attribution would look like.
        assertTrue(outcome.samples().stream()
                        .filter(s -> s.antenna() == AntennaPath.EXTERNAL)
                        .allMatch(s -> s.rssiDbm() == -41.0),
                "an external block must contain only external readings");
        assertTrue(outcome.samples().stream()
                        .filter(s -> s.antenna() == AntennaPath.CHIP)
                        .allMatch(s -> s.rssiDbm() == -35.0));
    }

    @Test
    @DisplayName("settle samples after a switch are dropped, not collected")
    void settleSamplesAreDropped() {
        FakeDevice device = new FakeDevice();
        device.switchLagSamples = 0;
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        // One block only: 4 external samples requested.
        ExperimentRunner runner = runnerFor(device,
                List.of(new ExperimentRunner.Block(AntennaPath.EXTERNAL, 4, false)), box);

        var outcome = drive(runner, device, -35.0, -41.0, 100, box);

        assertEquals(4, outcome.samples().size(), "exactly the requested count is kept");
        // The run consumed more samples than it kept: SETTLE_SAMPLES were
        // discarded so the radio's post-switch transient never enters the data.
        assertTrue(ExperimentRunner.SETTLE_SAMPLES > 0);
    }

    @Test
    @DisplayName("a run whose baseline drifts is reported as not quotable")
    void driftedBaselineVoidsTheRun() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device, smallPlan(), box);

        // Chip reads -35 at the start; by the closing baseline the room has
        // moved and it reads -45. That 10 dB shift dwarfs any antenna effect.
        runner.start();
        Instant t = T0;
        int i = 0;
        while (!runner.isFinished() && i < 500) {
            AntennaPath live = device.tick();
            // The environment changes mid-run. Tick 15 is comfortably after the
            // opening baseline (collected on ticks 2-6) and comfortably before
            // the closing one (ticks 34-36), so the two disagree by 10 dB.
            boolean late = i > 15;
            double dbm = live == AntennaPath.CHIP ? (late ? -45.0 : -35.0) : -41.0;
            runner.onSample(new RssiSample(i, t, live, dbm));
            t = t.plusMillis(1500);
            i++;
        }

        var outcome = box.get();
        assertNotNull(outcome);
        assertFalse(outcome.quotable(), "a drifted baseline must not be quotable");
        assertTrue(outcome.note().contains("drifted"), outcome.note());
        assertTrue(Math.abs(outcome.baselineDriftDb()) > ExperimentRunner.MAX_BASELINE_DRIFT_DB);
    }

    @Test
    @DisplayName("an antenna that changes mid-block stops the run instead of corrupting it")
    void unexpectedSwitchMidBlockStopsTheRun() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device,
                List.of(new ExperimentRunner.Block(AntennaPath.CHIP, 20, false)), box);

        runner.start();
        Instant t = T0;
        for (int i = 0; i < 10 && !runner.isFinished(); i++) {
            // Settle, then collect on CHIP...
            runner.onSample(new RssiSample(i, t, AntennaPath.CHIP, -35));
            t = t.plusMillis(1500);
        }
        // ...then someone taps EXT on the device's own screen mid-block.
        runner.onSample(new RssiSample(99, t, AntennaPath.EXTERNAL, -41));

        var outcome = box.get();
        assertNotNull(outcome, "the run must terminate rather than silently continue");
        assertFalse(outcome.quotable());
        assertTrue(outcome.note().contains("changed"), outcome.note());
    }

    @Test
    @DisplayName("a switch that never happens times out instead of hanging forever")
    void switchTimeoutEndsTheRun() {
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        // No command channel and an operator who never flips the antenna.
        ExperimentRunner runner = new ExperimentRunner(
                List.of(new ExperimentRunner.Block(AntennaPath.EXTERNAL, 5, false)),
                Optional.empty(),
                new ExperimentRunner.Listener() {
                    @Override
                    public void onFinished(ExperimentRunner.Outcome outcome) {
                        box.set(outcome);
                    }
                });

        runner.start();
        Instant t = T0;
        // Chip samples keep arriving well past the timeout window.
        for (int i = 0; i < 100 && !runner.isFinished(); i++) {
            runner.onSample(new RssiSample(i, t, AntennaPath.CHIP, -35));
            t = t.plusSeconds(2);
        }

        var outcome = box.get();
        assertNotNull(outcome);
        assertFalse(outcome.quotable());
        assertTrue(outcome.note().contains("never switched"), outcome.note());
    }

    @Test
    @DisplayName("guided mode asks the operator, then still waits for confirmation")
    void guidedModeRequestsAndVerifies() {
        var requested = new ArrayList<AntennaPath>();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = new ExperimentRunner(
                List.of(new ExperimentRunner.Block(AntennaPath.EXTERNAL, 3, false)),
                Optional.empty(),
                new ExperimentRunner.Listener() {
                    @Override
                    public void onSwitchRequested(AntennaPath to) {
                        requested.add(to);
                    }

                    @Override
                    public void onFinished(ExperimentRunner.Outcome outcome) {
                        box.set(outcome);
                    }
                });

        runner.start();
        assertEquals(List.of(AntennaPath.EXTERNAL), requested,
                "guided mode must prompt when it cannot command");

        Instant t = T0;
        // The operator takes a few seconds, during which chip samples arrive and
        // are correctly ignored rather than collected as external data.
        for (int i = 0; i < 3; i++) {
            runner.onSample(new RssiSample(i, t, AntennaPath.CHIP, -35));
            t = t.plusMillis(1500);
        }
        assertTrue(box.get() == null, "waiting for the flip is not a failure");

        for (int i = 3; i < 12 && !runner.isFinished(); i++) {
            runner.onSample(new RssiSample(i, t, AntennaPath.EXTERNAL, -41));
            t = t.plusMillis(1500);
        }

        var outcome = box.get();
        assertNotNull(outcome);
        assertTrue(outcome.quotable(), outcome.note());
        assertTrue(outcome.samples().stream().allMatch(s -> s.rssiDbm() == -41.0),
                "nothing collected before the confirmed flip");
    }

    @Test
    @DisplayName("a failed command ends the run with the reason, not a silent stall")
    void commandFailureIsReported() {
        FakeDevice device = new FakeDevice();
        device.failNextWrite = true;
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device, smallPlan(), box);

        runner.start();

        var outcome = box.get();
        assertNotNull(outcome, "a dead port must not leave the run hanging");
        assertFalse(outcome.quotable());
        assertTrue(outcome.note().contains("could not command"), outcome.note());
    }

    @Test
    @DisplayName("planFor reaches the procedure's sample floor on both paths")
    void planMeetsTheSampleFloor() {
        var procedure = PlatypusCatalog.pairedComparisonProcedure();
        var plan = ExperimentRunner.planFor(procedure, 2);

        int chip = plan.stream().filter(b -> b.path() == AntennaPath.CHIP && !b.closingBaseline())
                .mapToInt(ExperimentRunner.Block::target).sum();
        int ext = plan.stream().filter(b -> b.path() == AntennaPath.EXTERNAL)
                .mapToInt(ExperimentRunner.Block::target).sum();

        // Rounding must go UP: a plan landing just under the floor produces a
        // run the statistics layer refuses to grade.
        assertTrue(chip >= procedure.minSamplesPerPath(), chip + " < floor");
        assertTrue(ext >= procedure.minSamplesPerPath(), ext + " < floor");
        assertTrue(plan.get(plan.size() - 1).closingBaseline(),
                "the plan must end with a closing baseline");
        // Interleaved, not one block each -- this is what cancels drift.
        assertEquals(AntennaPath.CHIP, plan.get(0).path());
        assertEquals(AntennaPath.EXTERNAL, plan.get(1).path());
        assertEquals(AntennaPath.CHIP, plan.get(2).path());
    }

    @Test
    @DisplayName("a finished run ignores further samples")
    void finishedRunIgnoresLateSamples() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = runnerFor(device,
                List.of(new ExperimentRunner.Block(AntennaPath.CHIP, 3, false)), box);

        drive(runner, device, -35.0, -41.0, 100, box);
        int collectedAtFinish = box.get().samples().size();

        // The pipeline keeps running after the run completes; those samples
        // must not leak into the recorded result.
        runner.onSample(new RssiSample(999, T0.plusSeconds(600), AntennaPath.CHIP, -99));

        assertEquals(collectedAtFinish, box.get().samples().size());
        assertEquals(ExperimentRunner.Phase.FINISHED, runner.phase());
    }

    // ---- quick check ------------------------------------------------------

    /** Build a quick-check runner wired to the fake device. */
    private static ExperimentRunner quickCheckFor(FakeDevice device,
                                                  AtomicReference<ExperimentRunner.Outcome> box) {
        return ExperimentRunner.quickCheck(Optional.ofNullable(device),
                new ExperimentRunner.Listener() {
                    @Override
                    public void onFinished(ExperimentRunner.Outcome outcome) {
                        box.set(outcome);
                    }
                });
    }

    @Test
    @DisplayName("the quick check exercises the whole loop in under a minute of bench time")
    void quickCheckIsActuallyQuick() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = quickCheckFor(device, box);

        runner.start();
        Instant t = T0;
        int ticks = 0;
        while (!runner.isFinished() && ticks < 500) {
            AntennaPath live = device.tick();
            runner.onSample(new RssiSample(ticks, t, live,
                    live == AntennaPath.CHIP ? -35.0 : -41.0));
            t = t.plusMillis(1500);
            ticks++;
        }

        var outcome = box.get();
        assertNotNull(outcome, "the quick check must terminate");

        // The whole point is the wall clock. At the firmware's ~1.5 s cadence
        // this must land well inside a minute, or it is not a quick check and
        // nobody will reach for it on new hardware.
        Duration elapsed = Duration.between(T0, t);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(60)) < 0,
                "quick check took " + elapsed.toSeconds() + "s of bench time");

        // ...and it must still have proved the thing it exists to prove: both
        // antennas commanded, both confirmed, samples collected on each.
        assertEquals(List.of("AI", "AE", "AI"), device.sent);
        assertTrue(outcome.samples().stream().anyMatch(s -> s.antenna() == AntennaPath.CHIP));
        assertTrue(outcome.samples().stream().anyMatch(s -> s.antenna() == AntennaPath.EXTERNAL));
    }

    @Test
    @DisplayName("a perfect quick check is still not quotable")
    void quickCheckNeverQuotesAResult() {
        FakeDevice device = new FakeDevice();
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = quickCheckFor(device, box);

        // A clean run with a large, stable separation -- the most tempting
        // possible case, and exactly the one that must not produce a figure.
        var outcome = drive(runner, device, -35.0, -55.0, 500, box);

        assertTrue(runner.isDiagnostic());
        assertFalse(outcome.quotable(),
                "a 4-sample-per-path run must never be quotable: " + outcome.note());
        assertTrue(outcome.note().contains("not a measurement"), outcome.note());
    }

    @Test
    @DisplayName("the quick check still reports a genuine wiring failure")
    void quickCheckSurfacesRealFailures() {
        FakeDevice device = new FakeDevice();
        device.failNextWrite = true;
        var box = new AtomicReference<ExperimentRunner.Outcome>();
        ExperimentRunner runner = quickCheckFor(device, box);

        runner.start();

        // Diagnostic runs suppress the *result*, not the diagnosis -- otherwise
        // the one job of a wiring check would be the thing it cannot report.
        assertNotNull(box.get(), "a failed command must end the run");
        assertFalse(box.get().quotable());
        assertTrue(box.get().note().contains("could not command"), box.get().note());
    }
}
