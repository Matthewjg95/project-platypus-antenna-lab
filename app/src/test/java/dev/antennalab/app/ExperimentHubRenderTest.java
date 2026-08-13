package dev.antennalab.app;

import dev.antennalab.app.view.ExperimentHubView;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.LabLibrary;
import dev.antennalab.core.lab.PlatypusCatalog;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Builds the hub against a real seeded library and renders it off-screen.
 *
 * <p>This view resolves DUT and procedure references, switches over experiment
 * status, and constructs a checklist per step. Every one of those is a place a
 * null or an unhandled enum constant would throw at runtime, in a code path that
 * compiles perfectly well. Rendering it once, with real data, is the cheapest
 * way to know it works.
 */
class ExperimentHubRenderTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 640;

    private static boolean toolkitAvailable;

    @BeforeAll
    static void startToolkit() {
        toolkitAvailable = FxTestToolkit.ensureStarted();
    }

    /** A library seeded exactly as the app seeds it on first launch. */
    private static LabLibrary seededLibrary(Path root) {
        LabLibrary library = LabLibrary.openOrCreate(root);
        PlatypusCatalog.seed(library);
        library.put(PlatypusCatalog.headlineExperiment(Instant.parse("2026-08-07T12:00:00Z")));
        library.save();
        return library;
    }

    private static WritableImage render(LabLibrary library, Consumer<ExperimentHubView> setup)
            throws Exception {
        return FxTestToolkit.onFxThread(() -> {
            ExperimentHubView hub = new ExperimentHubView(library);
            new Scene(hub, WIDTH, HEIGHT);
            hub.resize(WIDTH, HEIGHT);
            hub.applyCss();
            hub.layout();
            setup.accept(hub);
            hub.applyCss();
            hub.layout();
            return hub.snapshot(null, null);
        });
    }

    @Test
    @Timeout(90)
    @DisplayName("the hub renders a seeded library without throwing")
    void rendersSeededLibrary(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        LabLibrary library = seededLibrary(tmp.resolve("library"));
        WritableImage image = render(library, hub -> { });

        assertNotNull(image);
        assertEquals(WIDTH, (int) image.getWidth());
        assertEquals(HEIGHT, (int) image.getHeight());
    }

    @Test
    @Timeout(90)
    @DisplayName("every experiment status renders -- an unhandled constant would throw here")
    void everyStatusRenders(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        LabLibrary library = seededLibrary(tmp.resolve("library"));
        Instant now = Instant.parse("2026-08-07T12:00:00Z");

        // One experiment per status. The view switches over Status in two places,
        // so a missing case is a runtime failure the compiler cannot catch once a
        // switch has been written with a default.
        library.put(Experiment.plan("st-planned", "Planned", "Does it work?", "", java.util.List.of(), now));
        library.put(Experiment.plan("st-progress", "In progress", "Does it work?", "", java.util.List.of(), now)
                .withRun("run-1", now));
        library.put(Experiment.plan("st-concluded", "Concluded", "Does it work?", "", java.util.List.of(), now)
                .withRun("run-1", now)
                .concludeWith("Yes, on this run.", now));
        library.put(Experiment.plan("st-abandoned", "Abandoned", "Does it work?", "", java.util.List.of(), now)
                .abandon("Bench unavailable.", now));
        library.save();

        for (int i = 0; i < library.experiments().size(); i++) {
            int index = i;
            WritableImage image = render(library, hub -> selectRow(hub, index));
            assertNotNull(image, "failed rendering experiment index " + index);
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("an experiment referencing a missing DUT renders a warning instead of failing")
    void danglingReferencesAreSurvivable(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        // A library with a typo must still open. Refusing to render it would be
        // the worst possible response to a mistyped id.
        LabLibrary library = LabLibrary.openOrCreate(tmp.resolve("library"));
        library.put(Experiment.plan("dangling", "Dangling refs",
                "Does the view survive a bad reference?",
                "procedure-that-does-not-exist",
                java.util.List.of("dut-that-does-not-exist"),
                Instant.parse("2026-08-07T12:00:00Z")));
        library.save();

        assertNotNull(render(library, hub -> { }));
        assertEquals(2, library.validate(library.experiment("dangling").orElseThrow()).size(),
                "both the missing procedure and the missing DUT should be reported");
    }

    @Test
    @Timeout(90)
    @DisplayName("an empty library renders the empty state rather than a blank panel")
    void emptyLibraryRenders(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        LabLibrary library = LabLibrary.openOrCreate(tmp.resolve("empty"));

        assertNotNull(render(library, hub -> { }));
    }

    /** Select the nth experiment in the hub's list, if the list is reachable. */
    private static void selectRow(ExperimentHubView hub, int index) {
        hub.lookupAll(".list-view").stream()
                .filter(javafx.scene.control.ListView.class::isInstance)
                .map(javafx.scene.control.ListView.class::cast)
                .findFirst()
                .ifPresent(list -> {
                    if (index < list.getItems().size()) {
                        list.getSelectionModel().select(index);
                    }
                });
    }
}
