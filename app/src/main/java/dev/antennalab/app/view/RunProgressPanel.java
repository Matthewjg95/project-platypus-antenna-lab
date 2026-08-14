package dev.antennalab.app.view;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.run.ExperimentRunner;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows an automated run as a run, not as a status-bar caption.
 *
 * <p>A ten-minute procedure narrated through one line of small text is
 * indistinguishable from a hang — the operator cannot tell "collecting block 3"
 * from "wedged since block 3". This panel gives the run a visible shape: every
 * block in the plan as a segment, the live block highlighted with its progress,
 * the phase in words, and a rough time remaining so walking away is an informed
 * decision rather than an act of faith.
 *
 * <p><b>All mutators must be called on the FX thread</b> — the runner's
 * callbacks arrive on the pipeline thread and the caller hops. This class does
 * not hop internally, so it stays testable without a live pipeline.
 *
 * <p>The ETA is labelled approximate and computed from the observed sample
 * cadence rather than a configured rate — the firmware's actual pace is the
 * only pace that matters, and it is not constant across scan modes.
 */
public final class RunProgressPanel extends VBox {

    private final Label heading = new Label();
    private final Label phaseLabel = new Label();
    private final Label etaLabel = new Label();
    private final HBox segmentRow = new HBox(4);
    private final ProgressBar blockProgress = new ProgressBar(0);
    private final Label blockLabel = new Label();

    private final List<Label> segments = new ArrayList<>();
    private List<ExperimentRunner.Block> plan = List.of();

    /** Samples finished in completed blocks; excludes the live block. */
    private int samplesDone;
    private int samplesTotal;
    private int currentBlock = -1;

    /** Exponential moving average of seconds per sample, from arrival times. */
    private double emaSecondsPerSample = Double.NaN;
    private long lastProgressNanos;

    public RunProgressPanel() {
        getStyleClass().add("run-progress");
        setSpacing(6);
        setPadding(new Insets(10));
        setVisible(false);
        setManaged(false);

        heading.getStyleClass().add("section-title");
        phaseLabel.setWrapText(true);
        etaLabel.getStyleClass().add("muted");
        blockProgress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(blockProgress, Priority.ALWAYS);

        HBox progressLine = new HBox(8, blockProgress, blockLabel);
        getChildren().addAll(heading, segmentRow, phaseLabel, progressLine, etaLabel);
    }

    /** Begin displaying a run. Shows the panel. */
    public void beginRun(List<ExperimentRunner.Block> plan, boolean diagnostic) {
        this.plan = List.copyOf(plan);
        this.samplesTotal = plan.stream().mapToInt(ExperimentRunner.Block::target).sum();
        this.samplesDone = 0;
        this.currentBlock = -1;
        this.emaSecondsPerSample = Double.NaN;
        this.lastProgressNanos = 0;

        heading.setText(diagnostic ? "QUICK CHECK" : "AUTOMATED RUN");
        phaseLabel.setText("Starting…");
        etaLabel.setText("");
        blockProgress.setProgress(0);
        blockLabel.setText("");

        segments.clear();
        segmentRow.getChildren().clear();
        for (ExperimentRunner.Block block : plan) {
            Label seg = new Label(segmentText(block));
            seg.getStyleClass().addAll("run-segment",
                    block.path() == AntennaPath.CHIP ? "seg-chip" : "seg-external");
            segments.add(seg);
            segmentRow.getChildren().add(seg);
        }

        setVisible(true);
        setManaged(true);
    }

    private static String segmentText(ExperimentRunner.Block block) {
        if (block.closingBaseline()) {
            return "BASE ×" + block.target();
        }
        return (block.path() == AntennaPath.CHIP ? "CHIP" : "EXT") + " ×" + block.target();
    }

    /** Mirror of {@link ExperimentRunner.Listener#onPhase}. */
    public void showPhase(ExperimentRunner.Phase phase, AntennaPath target, int blockIndex) {
        if (blockIndex != currentBlock) {
            // A new block began; the previous one is complete by definition.
            if (currentBlock >= 0 && currentBlock < plan.size()) {
                markDone(currentBlock);
                samplesDone += plan.get(currentBlock).target();
            }
            currentBlock = blockIndex;
            markLive(blockIndex);
            blockProgress.setProgress(0);
            blockLabel.setText("0/" + plan.get(blockIndex).target());
        }

        phaseLabel.setText(switch (phase) {
            case SWITCHING -> "Switching to " + target.displayName()
                    + " — waiting for the device to confirm";
            case SETTLING -> "Confirmed on " + target.displayName() + " — settling";
            case COLLECTING -> "Collecting on " + target.displayName();
            case FINISHED -> "Finished";
        });
    }

    /** Mirror of {@link ExperimentRunner.Listener#onProgress}. */
    public void showProgress(int collected, int target) {
        blockProgress.setProgress(target == 0 ? 0 : (double) collected / target);
        blockLabel.setText(collected + "/" + target);

        long now = System.nanoTime();
        if (lastProgressNanos != 0) {
            double seconds = (now - lastProgressNanos) / 1e9;
            // Ignore gaps that are clearly a switch or an outage, not cadence.
            if (seconds < 30) {
                emaSecondsPerSample = Double.isNaN(emaSecondsPerSample)
                        ? seconds
                        : emaSecondsPerSample * 0.8 + seconds * 0.2;
            }
        }
        lastProgressNanos = now;

        int remaining = samplesTotal - samplesDone - collected;
        if (!Double.isNaN(emaSecondsPerSample) && remaining > 0) {
            long totalSeconds = Math.round(remaining * emaSecondsPerSample);
            etaLabel.setText("~" + (totalSeconds >= 90
                    ? (totalSeconds + 30) / 60 + " min left"
                    : totalSeconds + " s left"));
        }
    }

    /** Guided mode: the operator has to flip the antenna by hand. */
    public void showSwitchRequest(AntennaPath to) {
        phaseLabel.setText("SWITCH THE ANTENNA TO "
                + to.displayName().toUpperCase(java.util.Locale.ROOT)
                + " on the device — the run waits for its confirmation");
    }

    /** Terminal state. The panel stays visible so the outcome can be read. */
    public void showOutcome(ExperimentRunner.Outcome outcome) {
        if (currentBlock >= 0 && currentBlock < segments.size() && outcome.quotable()) {
            markDone(currentBlock);
        }
        heading.setText(outcome.quotable() ? "RUN COMPLETE" : "RUN ENDED");
        phaseLabel.setText(outcome.note());
        etaLabel.setText(outcome.samples().size() + " samples kept");
        blockProgress.setProgress(1);
    }

    /** Hide, e.g. when a fresh manual capture starts. */
    public void dismiss() {
        setVisible(false);
        setManaged(false);
    }

    private void markLive(int index) {
        if (index < segments.size()) {
            segments.get(index).getStyleClass().add("seg-live");
        }
    }

    private void markDone(int index) {
        Label seg = segments.get(index);
        seg.getStyleClass().remove("seg-live");
        seg.getStyleClass().add("seg-done");
    }
}
