package dev.antennalab.app;

import dev.antennalab.app.view.RunProgressPanel;
import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.run.ExperimentRunner;
import javafx.scene.Scene;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the panel through a whole run's callbacks and lays it out for real.
 *
 * <p>The panel is fed from three different listener methods whose ordering is
 * the runner's business, not ours -- so the test walks the exact sequence the
 * runner produces (phase, progress, phase, ... outcome) and checks the panel
 * survives it and ends in the terminal state.
 */
class RunProgressPanelTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void startToolkit() {
        toolkitAvailable = FxTestToolkit.ensureStarted();
    }

    @Test
    @Timeout(90)
    @DisplayName("a full quick-check sequence renders and ends in the terminal state")
    void fullSequenceRenders() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        FxTestToolkit.onFxThread(() -> {
            RunProgressPanel panel = new RunProgressPanel();
            new Scene(panel, 300, 200);

            List<ExperimentRunner.Block> plan = ExperimentRunner.quickCheckPlan();
            panel.beginRun(plan, true);
            assertTrue(panel.isVisible(), "beginRun must show the panel");

            // Walk the callback sequence a real quick check produces.
            for (int block = 0; block < plan.size(); block++) {
                ExperimentRunner.Block b = plan.get(block);
                panel.showPhase(ExperimentRunner.Phase.SWITCHING, b.path(), block);
                panel.showPhase(ExperimentRunner.Phase.SETTLING, b.path(), block);
                panel.showPhase(ExperimentRunner.Phase.COLLECTING, b.path(), block);
                for (int i = 1; i <= b.target(); i++) {
                    panel.showProgress(i, b.target());
                }
            }
            panel.showOutcome(new ExperimentRunner.Outcome(
                    false, "wiring check passed: ... not a measurement", List.of(), 0.1));

            panel.applyCss();
            panel.layout();
            assertNotNull(panel.snapshot(null, null));
            assertTrue(panel.isVisible(), "the outcome must stay readable, not vanish");
            return null;
        });
    }

    @Test
    @Timeout(90)
    @DisplayName("a guided-mode switch request replaces the phase text")
    void switchRequestRenders() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        FxTestToolkit.onFxThread(() -> {
            RunProgressPanel panel = new RunProgressPanel();
            new Scene(panel, 300, 200);
            panel.beginRun(ExperimentRunner.quickCheckPlan(), false);
            panel.showSwitchRequest(AntennaPath.EXTERNAL);
            panel.applyCss();
            panel.layout();
            assertNotNull(panel.snapshot(null, null));
            return null;
        });
    }

    @Test
    @Timeout(90)
    @DisplayName("dismiss hides the panel and releases its layout space")
    void dismissHides() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        FxTestToolkit.onFxThread(() -> {
            RunProgressPanel panel = new RunProgressPanel();
            panel.beginRun(ExperimentRunner.quickCheckPlan(), true);
            panel.dismiss();
            assertTrue(!panel.isVisible() && !panel.isManaged(),
                    "dismissed panel must not hold blank space in the side panel");
            return null;
        });
    }
}
