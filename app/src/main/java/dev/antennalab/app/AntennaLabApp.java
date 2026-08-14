package dev.antennalab.app;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.LabLibrary;
import dev.antennalab.core.lab.PlatypusCatalog;
import dev.antennalab.core.pipeline.CaptureListener;
import dev.antennalab.core.pipeline.CapturePipeline;
import dev.antennalab.core.stats.AntennaDelta;
import dev.antennalab.core.stats.TraceStats;
import dev.antennalab.app.view.DeltaCard;
import dev.antennalab.app.view.ExperimentHubView;
import dev.antennalab.app.view.ScopeView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * Antenna Lab main window.
 *
 * <p>Layout is deliberately instrument-like: scope fills the space, controls sit
 * on a chassis bar above it, and the numbers that matter live in a fixed panel on
 * the right where they do not move around as the trace does.
 */
public final class AntennaLabApp extends Application {

    /** Recompute statistics every N frames. At 60 fps this is about 4 Hz. */
    private static final int STATS_EVERY_N_FRAMES = 15;

    private final ScopeView scope = new ScopeView();
    private final DeltaCard deltaCard = new DeltaCard();

    private final Label sourceStatus = new Label("Idle");
    private final Label sampleStatus = new Label("0 samples");
    private final Label provenanceStatus = new Label();

    private final Button startButton = new Button("Start");
    private final Button stopButton = new Button("Stop");
    private final ToggleButton pauseButton = new ToggleButton("Pause");
    private final Button markerButton = new Button("Marker");
    private final Button clearButton = new Button("Clear");
    private final Button exportButton = new Button("Export report…");
    private final Button quickCheckButton = new Button("Quick check");
    private final javafx.scene.control.ComboBox<SourceOption> sourceCombo =
            new javafx.scene.control.ComboBox<>();

    /** The last completed capture, exportable until the next one starts. */
    private Session lastSession;

    private CapturePipeline pipeline;
    private int frameCounter;

    private LabLibrary library;
    private dev.antennalab.core.session.SessionStore sessionStore;
    private ExperimentHubView hub;

    /** Non-null while an automated procedure run is in flight. */
    private dev.antennalab.core.run.ExperimentRunner runner;
    private TabPane tabs;
    private Tab scopeTab;

    /** Experiment the next capture will be recorded against, if any. */
    private Experiment activeExperiment;

    @Override
    public void start(Stage stage) {
        BorderPane scopePane = new BorderPane();
        scopePane.setTop(buildToolBar());
        scopePane.setCenter(scope);
        scopePane.setRight(buildSidePanel());
        scopePane.setBottom(buildStatusBar());

        setRunningState(false);

        // The library is opened once at startup and shared. A first run gets the
        // Project Platypus hardware seeded so the hub is not an empty shell.
        library = LabLibrary.openOrCreate(LabLibrary.defaultRoot());
        if (library.duts().isEmpty() && library.experiments().isEmpty()) {
            PlatypusCatalog.seed(library);
            library.put(PlatypusCatalog.headlineExperiment(java.time.Instant.now()));
            library.save();
        }
        sessionStore = dev.antennalab.core.session.SessionStore.openOrCreate(
                dev.antennalab.core.session.SessionStore.defaultRoot());
        hub = new ExperimentHubView(library);
        hub.setSessionStore(sessionStore);
        hub.setOnRunRequested(this::startAutomatedRun);
        // Selecting "capture a run" from the hub switches to the scope, so the two
        // halves of the app are one workflow rather than two apps in a window.
        hub.setOnCaptureRequested(experiment -> {
            activeExperiment = experiment;
            tabs.getSelectionModel().select(scopeTab);
            sourceStatus.setText("Ready to capture for: " + experiment.title());
        });

        scopeTab = new Tab("Scope", scopePane);
        scopeTab.setClosable(false);
        Tab hubTab = new Tab("Experiments", hub);
        hubTab.setClosable(false);
        tabs = new TabPane(scopeTab, hubTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane(tabs);

        Scene scene = new Scene(root, 1180, 720);
        var css = AntennaLabApp.class.getResource("/dev/antennalab/app/instrument.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        // The window icon is separate from the packaged launcher's icon: jpackage
        // sets the taskbar/Explorer icon on the exe, but the running JavaFX stage
        // has its own, and without this the window shows the generic Java one.
        var iconStream = AntennaLabApp.class.getResourceAsStream("/dev/antennalab/app/antenna-lab-256.png");
        if (iconStream != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconStream));
        }

        stage.setTitle("Antenna Lab - RF test bench");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();

        autoDetectAndConnect();
    }

    /**
     * Find the antenna firmware among the serial ports and connect to it,
     * unprompted. The operator should never have to know what a COM port is;
     * the dropdown remains only as the manual override.
     *
     * <p>Runs on a virtual thread: detection listens on each candidate port for
     * up to ~2 s, which must never block the UI.
     */
    private void autoDetectAndConnect() {
        sourceStatus.setText("Scanning for antenna hardware…");
        Thread.ofVirtual().name("firmware-detect").start(() -> {
            var found = dev.antennalab.core.source.FirmwareDetector
                    .findAntennaPort(java.time.Duration.ofSeconds(2));
            Platform.runLater(() -> found.ifPresentOrElse(portName -> {
                refreshSources();
                sourceCombo.getItems().stream()
                        .filter(o -> o.source() instanceof SerialSource s
                                && s.portName().equals(portName))
                        .findFirst()
                        .ifPresent(option -> {
                            sourceCombo.getSelectionModel().select(option);
                            sourceStatus.setText("Antenna detected on " + portName);
                            if (pipeline == null || !pipeline.isRunning()) {
                                startCapture();
                            }
                        });
            }, () -> sourceStatus.setText(
                    "No antenna hardware found — synthetic source selected")));
        });
    }

    private ToolBar buildToolBar() {
        startButton.getStyleClass().add("primary");
        startButton.setOnAction(e -> startCapture());
        stopButton.setOnAction(e -> stopCapture());
        pauseButton.setOnAction(e -> {
            boolean paused = pauseButton.isSelected();
            if (pipeline != null) {
                pipeline.setPaused(paused);
            }
            scope.setFrozen(paused);
            pauseButton.setText(paused ? "Resume" : "Pause");
        });
        markerButton.setOnAction(e -> {
            if (pipeline != null) {
                pipeline.addMarker("M" + (pipeline.markers().size() + 1));
                refreshMarkers();
            }
        });
        clearButton.setOnAction(e -> {
            if (pipeline != null) {
                pipeline.buffer().clear();
            }
            scope.setWindow(List.of());
            scope.setMarkerPositions(List.of());
            deltaCard.clear();
        });

        Label sourceLabel = new Label("Source:");
        refreshSources();
        Button rescanButton = new Button("⟳");
        rescanButton.setOnAction(e -> refreshSources());

        exportButton.setOnAction(e -> exportReport());
        exportButton.setDisable(true);

        quickCheckButton.setOnAction(e -> startQuickCheck());
        quickCheckButton.setTooltip(new Tooltip(
                "Command both antennas and confirm the device reports them back. "
                        + "About 30 seconds. Proves the rig is wired up; measures nothing."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(
                sourceLabel, sourceCombo, rescanButton,
                new Separator(),
                startButton, stopButton, pauseButton,
                new Separator(),
                quickCheckButton,
                new Separator(),
                markerButton, clearButton,
                new Separator(),
                exportButton,
                spacer);
    }

    /** One entry in the source picker: a label and the Source it describes. */
    private record SourceOption(String label, Source source) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Repopulate the source picker: synthetic first, then every serial port.
     *
     * <p>Bluetooth "serial" links appear in the OS port list too, so the
     * descriptive name is shown alongside — the Tab5 enumerates as a USB Serial
     * Device, which is how the operator tells COM6 from a headset.
     */
    private void refreshSources() {
        SourceOption previous = sourceCombo.getValue();
        sourceCombo.getItems().setAll(new SourceOption(
                "Synthetic (no hardware)", SyntheticSource.demoWithSeed(System.nanoTime())));
        for (var port : com.fazecast.jSerialComm.SerialPort.getCommPorts()) {
            String name = port.getSystemPortName();
            sourceCombo.getItems().add(new SourceOption(
                    "%s — %s".formatted(name, port.getDescriptivePortName()),
                    SerialSource.onPort(name)));
        }
        // Keep the operator's selection across a rescan when it still exists.
        sourceCombo.getSelectionModel().select(
                previous == null ? sourceCombo.getItems().get(0)
                        : sourceCombo.getItems().stream()
                                .filter(o -> o.label().equals(previous.label()))
                                .findFirst().orElse(sourceCombo.getItems().get(0)));
    }

    /**
     * Write the last capture as a self-contained HTML report.
     *
     * <p>Report generation is a {@code core} call with no UI dependency; the app
     * merely chooses the file. The write happens on a virtual thread because a
     * large session can serialise tens of thousands of samples to SVG, and the
     * one unforgivable UI sin in bench software is freezing the scope.
     */
    private void exportReport() {
        Session session = lastSession;
        if (session == null) {
            return;
        }
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export report");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("HTML report", "*.html"));
        chooser.setInitialFileName("antenna-lab-report.html");
        java.io.File target = chooser.showSaveDialog(scope.getScene().getWindow());
        if (target == null) {
            return;
        }
        exportButton.setDisable(true);
        Thread.ofVirtual().name("report-export").start(() -> {
            try {
                String html = dev.antennalab.core.report.HtmlReport.render(session);
                java.nio.file.Files.writeString(target.toPath(), html,
                        java.nio.charset.StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    sourceStatus.setText("Report written: " + target.getName());
                    exportButton.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    sourceStatus.setText("Report failed: " + ex.getMessage());
                    exportButton.setDisable(false);
                });
            }
        });
    }

    private VBox buildSidePanel() {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("side-panel");
        panel.setPadding(new Insets(12));

        Label title = new Label("MEASUREMENT");
        title.getStyleClass().add("section-title");

        panel.getChildren().addAll(title, deltaCard);
        return panel;
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("status-bar");
        sourceStatus.getStyleClass().add("status-text");
        sampleStatus.getStyleClass().add("status-text");
        provenanceStatus.getStyleClass().addAll("status-text", "simulated");
        bar.getChildren().addAll(sourceStatus, sampleStatus, provenanceStatus);
        return bar;
    }

    private void startCapture() {
        if (pipeline != null && pipeline.isRunning()) {
            return;
        }
        SourceOption chosen = sourceCombo.getValue();
        Source source = chosen != null ? chosen.source()
                : SyntheticSource.demoWithSeed(System.nanoTime());

        pipeline = new CapturePipeline(source, new UiCaptureListener());
        pipeline.setRecording(true);
        pipeline.start();

        sourceStatus.setText(Source.summarise(source));
        // Provenance is styled as well as worded: measured data must not share a
        // colour with simulation. Connecting no longer resets the board -- the
        // producer holds DTR/RTS low across the open -- so samples start arriving
        // at the firmware's own cadence rather than after a boot.
        provenanceStatus.getStyleClass().removeAll("simulated", "live");
        if (source.isLiveHardware()) {
            provenanceStatus.setText("LIVE - measured on hardware");
            provenanceStatus.getStyleClass().add("live");
        } else {
            provenanceStatus.setText("SIMULATED - not measured data");
            provenanceStatus.getStyleClass().add("simulated");
        }
        setRunningState(true);
    }

    private void stopCapture() {
        if (pipeline != null) {
            // An automated run persists itself through the runner's outcome;
            // stopping mid-run must not also record a partial one under a second
            // id, or one bench run would appear twice in the record.
            boolean midRun = runner != null && !runner.isFinished();
            runner = null;
            pipeline.stop();
            if (!midRun) {
                recordRunAgainstExperiment();
            } else {
                sourceStatus.setText("Run stopped early — nothing recorded");
            }
        }
        setRunningState(false);
    }

    /**
     * Attach the finished capture to the experiment it was started for.
     *
     * <p>This is the join that makes the hub a manager rather than a viewer: a
     * capture that is not linked to a question is the thing this whole model
     * exists to stop producing.
     *
     * <p>A run with no samples is deliberately not recorded. An experiment listing
     * runs that contain nothing would overstate how much evidence it has, which is
     * the one direction the record must never err in.
     */
    private void recordRunAgainstExperiment() {
        if (activeExperiment == null || pipeline == null) {
            return;
        }
        recordRun(pipeline.recordedSamples(), null);
    }

    /**
     * Persist samples as a run of the active experiment.
     *
     * <p>The session is saved <em>first</em>, under the same id the experiment
     * will reference. Attaching a run id before the data exists is how you get
     * an experiment that claims evidence it cannot produce — so if the write
     * fails, nothing is attached and the operator is told.
     *
     * @param note appended to the run's stored notes, e.g. an automated run's
     *             validity verdict; null for a free capture.
     */
    private void recordRun(java.util.List<RssiSample> samples, String note) {
        if (samples == null || samples.isEmpty()) {
            sourceStatus.setText("Nothing captured; no run recorded");
            return;
        }
        String runId = "run-" + java.time.Instant.now().toString().replaceAll("[:.]", "-");
        var meta = new dev.antennalab.core.domain.SessionMetadata(
                activeExperiment != null ? activeExperiment.title() : "Ad-hoc capture",
                0.0, "", dev.antennalab.core.domain.SessionMetadata.CHANNEL_UNKNOWN,
                activeExperiment != null ? String.join(", ", activeExperiment.dutIds()) : "",
                note == null ? "" : note,
                java.time.Instant.now());
        Session session = new Session(runId, pipeline.source(), meta, samples);

        try {
            sessionStore.save(session);
        } catch (RuntimeException ex) {
            sourceStatus.setText("Run NOT recorded — could not save session: " + ex.getMessage());
            return;
        }
        lastSession = session;
        exportButton.setDisable(false);

        if (activeExperiment != null) {
            library.experiment(activeExperiment.id()).ifPresent(current -> {
                Experiment updated = current.withRun(runId, java.time.Instant.now());
                library.put(updated);
                library.save();
                activeExperiment = updated;
                hub.refresh();
            });
        }
        sourceStatus.setText("Recorded %,d samples as %s".formatted(samples.size(), runId));
    }

    /**
     * Run an experiment's procedure automatically, end to end.
     *
     * <p>Switches the view to the scope so the operator watches the run happen,
     * starts capture if it is not already running, then hands samples to an
     * {@link dev.antennalab.core.run.ExperimentRunner} through the pipeline's
     * sample tap. The runner owns correctness — confirmed switches, settle
     * discards, balanced blocks, closing baseline — and this method owns only
     * the UI and the persistence that follows.
     */
    private void startAutomatedRun(Experiment experiment) {
        var procedure = library.procedure(experiment.procedureId());
        if (procedure.isEmpty()) {
            sourceStatus.setText("Cannot run: procedure '" + experiment.procedureId()
                    + "' is not in this library");
            tabs.getSelectionModel().select(scopeTab);
            return;
        }
        activeExperiment = experiment;
        tabs.getSelectionModel().select(scopeTab);

        if (pipeline == null || !pipeline.isRunning()) {
            startCapture();
        }
        if (pipeline == null || !pipeline.isRunning()) {
            sourceStatus.setText("Cannot run: no capture source is available");
            return;
        }

        var plan = dev.antennalab.core.run.ExperimentRunner.planFor(procedure.get(), 2);
        boolean commanded = pipeline.commands().isPresent();

        runner = new dev.antennalab.core.run.ExperimentRunner(
                plan, pipeline.commands(), new RunListener(commanded, false));
        attachRunnerTap();
        pipeline.setRecording(true);
        runner.start();

        sourceStatus.setText(commanded
                ? "Running procedure — driving the antenna switch"
                : "Running procedure — you will be prompted to switch antennas");
    }

    /**
     * Run the short wiring check: command both antennas, confirm both, stop.
     *
     * <p>This exists because the real procedure takes ten minutes before it tells
     * you anything, which is the wrong first thing to try on hardware that has
     * never completed a run. It needs no experiment and records nothing — it
     * answers "is this rig wired up?", not "which antenna is better".
     */
    private void startQuickCheck() {
        activeExperiment = null;
        tabs.getSelectionModel().select(scopeTab);

        if (pipeline == null || !pipeline.isRunning()) {
            startCapture();
        }
        if (pipeline == null || !pipeline.isRunning()) {
            sourceStatus.setText("Cannot run: no capture source is available");
            return;
        }

        boolean commanded = pipeline.commands().isPresent();
        runner = dev.antennalab.core.run.ExperimentRunner.quickCheck(
                pipeline.commands(), new RunListener(commanded, true));
        attachRunnerTap();
        // Deliberately NOT recording: a wiring check is not evidence, and a
        // session full of it would be clutter in the library.
        runner.start();

        sourceStatus.setText(commanded
                ? "Quick check — commanding both antennas, about 30 seconds"
                : "Quick check — you will be prompted to switch antennas");
    }

    /**
     * Feed the pipeline's samples to whatever runner is active.
     *
     * <p>The tap delivers samples on the pipeline's own thread; the runner is a
     * pure state machine, so it runs there and only the UI hops to JavaFX.
     */
    private void attachRunnerTap() {
        pipeline.addSampleTap(s -> {
            var active = runner;
            if (active != null && !active.isFinished()) {
                active.onSample(s);
            }
        });
    }

    /** Bridges runner progress to the UI and persists the finished run. */
    private final class RunListener implements dev.antennalab.core.run.ExperimentRunner.Listener {
        private final boolean commanded;
        private final boolean diagnostic;

        RunListener(boolean commanded, boolean diagnostic) {
            this.commanded = commanded;
            this.diagnostic = diagnostic;
        }

        @Override
        public void onPhase(dev.antennalab.core.run.ExperimentRunner.Phase phase,
                            AntennaPath target, int blockIndex, int blockCount) {
            Platform.runLater(() -> sourceStatus.setText(
                    "Block %d/%d — %s %s".formatted(
                            blockIndex + 1, blockCount, phase, target.displayName())));
        }

        @Override
        public void onProgress(int collected, int target) {
            Platform.runLater(() -> sampleStatus.setText(
                    "run: %d/%d in block".formatted(collected, target)));
        }

        @Override
        public void onSwitchRequested(AntennaPath to) {
            // Guided mode. The run does not proceed on this instruction alone --
            // it still waits for the device to report the new path.
            Platform.runLater(() -> sourceStatus.setText(
                    "SWITCH THE ANTENNA TO " + to.displayName().toUpperCase(java.util.Locale.ROOT)
                            + " — waiting for the device to confirm"));
        }

        @Override
        public void onFinished(dev.antennalab.core.run.ExperimentRunner.Outcome outcome) {
            Platform.runLater(() -> {
                runner = null;
                if (pipeline != null) {
                    pipeline.stop();
                }
                setRunningState(false);

                if (diagnostic) {
                    // The note already says whether the loop worked and, if it
                    // did, that it measured nothing. Nothing is persisted: a
                    // wiring check is not evidence about an antenna.
                    sourceStatus.setText(outcome.note());
                    return;
                }

                if (outcome.quotable()) {
                    recordRun(outcome.samples(), "Automated run (%s switching): %s"
                            .formatted(commanded ? "commanded" : "guided", outcome.note()));
                } else {
                    // A void run is still saved -- discarding the evidence for a
                    // failure would make the failure unauditable -- but it is
                    // NOT attached to the experiment, so it can never be quoted.
                    saveVoidRun(outcome);
                }
            });
        }
    }

    /**
     * Persist a run that failed its own validity checks, without attaching it.
     *
     * <p>Keeping the data matters: "the baseline drifted 10 dB" is a finding
     * about the bench, and throwing it away makes the failure unexaminable.
     * Not attaching it matters more: an experiment must never list a run whose
     * numbers it would be wrong to quote.
     */
    private void saveVoidRun(dev.antennalab.core.run.ExperimentRunner.Outcome outcome) {
        if (outcome.samples().isEmpty()) {
            sourceStatus.setText("Run failed: " + outcome.note());
            return;
        }
        String voidId = "void-" + java.time.Instant.now().toString().replaceAll("[:.]", "-");
        var meta = new dev.antennalab.core.domain.SessionMetadata(
                "VOID: " + (activeExperiment != null ? activeExperiment.title() : "run"),
                0.0, "", dev.antennalab.core.domain.SessionMetadata.CHANNEL_UNKNOWN,
                "", "Run not quotable: " + outcome.note(), java.time.Instant.now());
        try {
            sessionStore.save(new Session(voidId, pipeline.source(), meta, outcome.samples()));
        } catch (RuntimeException ignored) {
            // Saving the evidence for a failed run is best-effort; the verdict
            // below is the part the operator must not miss.
        }
        sourceStatus.setText("RUN NOT QUOTABLE — " + outcome.note()
                + " (data kept as " + voidId + ", not attached)");
    }

    private void setRunningState(boolean running) {
        startButton.setDisable(running);
        stopButton.setDisable(!running);
        pauseButton.setDisable(!running);
        markerButton.setDisable(!running);
        if (!running) {
            pauseButton.setSelected(false);
            pauseButton.setText("Pause");
            scope.setFrozen(false);
        }
    }

    private void refreshMarkers() {
        if (pipeline == null) {
            return;
        }
        long total = pipeline.buffer().totalWritten();
        int windowSize = pipeline.buffer().size();
        long windowStart = total - windowSize;
        List<Double> positions = pipeline.markers().stream()
                .map(m -> windowSize <= 1 ? -1.0 : (double) (m.atSequence() - windowStart) / windowSize)
                .filter(p -> p >= 0 && p <= 1)
                .toList();
        scope.setMarkerPositions(positions);
    }

    /**
     * Bridges the pipeline's virtual threads to the JavaFX application thread.
     *
     * <p>{@code core} has no JavaFX dependency at all, so this is the single
     * place the thread hop happens -- and because it is the only place, it cannot
     * be forgotten somewhere deeper in the stack.
     */
    private final class UiCaptureListener implements CaptureListener {

        @Override
        public void onFrame(List<RssiSample> window) {
            Platform.runLater(() -> {
                scope.setWindow(window);
                sampleStatus.setText("%,d samples".formatted(pipeline == null ? 0 : pipeline.buffer().totalWritten()));
                refreshMarkers();
                if (frameCounter++ % STATS_EVERY_N_FRAMES == 0) {
                    updateStats(window);
                }
            });
        }

        @Override
        public void onCancelled() {
            Platform.runLater(() -> {
                sourceStatus.setText("Stopped");
                setRunningState(false);
            });
        }

        @Override
        public void onCompleted() {
            Platform.runLater(() -> {
                sourceStatus.setText("Source exhausted");
                setRunningState(false);
            });
        }

        @Override
        public void onFailed(Throwable cause) {
            Platform.runLater(() -> {
                sourceStatus.setText("Failed: " + cause.getMessage());
                setRunningState(false);
            });
        }
    }

    private void updateStats(List<RssiSample> window) {
        List<RssiSample> chip = window.stream().filter(s -> s.antenna() == AntennaPath.CHIP).toList();
        List<RssiSample> external = window.stream().filter(s -> s.antenna() == AntennaPath.EXTERNAL).toList();
        if (chip.isEmpty() || external.isEmpty()) {
            return;
        }
        deltaCard.update(AntennaDelta.of(TraceStats.of(chip), TraceStats.of(external)));
    }

    @Override
    public void stop() {
        // Guarantees the capture unwinds -- and, once serial is wired up, that the
        // COM port is released -- even if the window is closed mid-run.
        if (pipeline != null) {
            pipeline.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
