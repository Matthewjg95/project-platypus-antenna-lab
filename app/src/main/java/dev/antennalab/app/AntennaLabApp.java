package dev.antennalab.app;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.pipeline.CaptureListener;
import dev.antennalab.core.pipeline.CapturePipeline;
import dev.antennalab.core.stats.AntennaDelta;
import dev.antennalab.core.stats.TraceStats;
import dev.antennalab.app.view.DeltaCard;
import dev.antennalab.app.view.ScopeView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
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

    private CapturePipeline pipeline;
    private int frameCounter;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildToolBar());
        root.setCenter(scope);
        root.setRight(buildSidePanel());
        root.setBottom(buildStatusBar());

        setRunningState(false);

        Scene scene = new Scene(root, 1180, 720);
        var css = AntennaLabApp.class.getResource("/dev/antennalab/app/instrument.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("Antenna Lab - RF test bench");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();
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
        Label sourceValue = new Label("Synthetic (no hardware)");
        sourceValue.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(
                sourceLabel, sourceValue,
                new Separator(),
                startButton, stopButton, pauseButton,
                new Separator(),
                markerButton, clearButton,
                spacer);
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
        // Days 1-2 milestone: synthetic only. Serial and replay sources are wired
        // through the same pipeline, but their producers stay unimplemented until
        // the firmware's real output has been captured.
        Source source = SyntheticSource.demoWithSeed(System.nanoTime());

        pipeline = new CapturePipeline(source, new UiCaptureListener());
        pipeline.setRecording(true);
        pipeline.start();

        sourceStatus.setText(Source.summarise(source));
        provenanceStatus.setText("SIMULATED - not measured data");
        setRunningState(true);
    }

    private void stopCapture() {
        if (pipeline != null) {
            pipeline.stop();
        }
        setRunningState(false);
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
