package dev.antennalab.app.view;

import dev.antennalab.core.lab.Dut;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.LabLibrary;
import dev.antennalab.core.lab.Procedure;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The experiment hub: the view that makes this an experiment manager rather than
 * a chart.
 *
 * <p>Left, every experiment in the library with its status. Right, the one thing
 * that matters most about the selected experiment -- the question it was opened
 * to answer -- followed by the procedure to follow, the hardware involved, the
 * runs captured so far, and the conclusion if there is one.
 *
 * <p>The procedure is rendered as a working checklist rather than a document.
 * That is deliberate: a protocol you read once and then work from memory is
 * exactly the failure mode that makes hobby RF measurements untrustworthy, and
 * the verification line under each step is the part that usually gets skipped.
 */
public final class ExperimentHubView extends BorderPane {

    private final LabLibrary library;
    private final ObservableList<Experiment> experiments = FXCollections.observableArrayList();
    private final ListView<Experiment> experimentList = new ListView<>(experiments);
    private final VBox detail = new VBox(6);

    /** Steps ticked off in this sitting, keyed "experimentId#ordinal". */
    private final Set<String> completedSteps = new HashSet<>();

    private final Label libraryStatus = new Label();
    private Consumer<Experiment> onCaptureRequested = e -> { };

    public ExperimentHubView(LabLibrary library) {
        if (library == null) {
            throw new IllegalArgumentException("library is required");
        }
        this.library = library;

        experimentList.setCellFactory(v -> new ExperimentCell());
        experimentList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> showDetail(selected));
        experimentList.setMinWidth(260);

        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("detail-scroll");
        detail.setPadding(new Insets(16));

        SplitPane split = new SplitPane(experimentList, detailScroll);
        split.setDividerPositions(0.28);

        setTop(buildToolBar());
        setCenter(split);
        setBottom(buildStatusBar());

        refresh();
    }

    /** Called when the operator asks to capture a run for an experiment. */
    public void setOnCaptureRequested(Consumer<Experiment> handler) {
        this.onCaptureRequested = handler == null ? e -> { } : handler;
    }

    private ToolBar buildToolBar() {
        Button newExperiment = new Button("New experiment");
        newExperiment.getStyleClass().add("primary");
        newExperiment.setOnAction(e -> ExperimentDialogs.newExperiment(library, getScene().getWindow())
                .ifPresent(created -> {
                    library.put(created);
                    library.save();
                    refresh();
                    experimentList.getSelectionModel().select(created);
                }));

        Button save = new Button("Save library");
        save.setOnAction(e -> {
            library.save();
            updateStatus("Saved to " + library.root());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(newExperiment, new Separator(), save, spacer);
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("status-bar");
        libraryStatus.getStyleClass().add("status-text");
        bar.getChildren().add(libraryStatus);
        return bar;
    }

    /** Reload from the library and keep the current selection where possible. */
    public void refresh() {
        Experiment selected = experimentList.getSelectionModel().getSelectedItem();
        experiments.setAll(library.experiments());
        if (selected != null) {
            library.experiment(selected.id()).ifPresent(experimentList.getSelectionModel()::select);
        } else if (!experiments.isEmpty()) {
            experimentList.getSelectionModel().selectFirst();
        }
        updateStatus(library.summary() + "  ·  " + library.root());
        showDetail(experimentList.getSelectionModel().getSelectedItem());
    }

    private void updateStatus(String text) {
        libraryStatus.setText(text);
    }

    private void showDetail(Experiment experiment) {
        detail.getChildren().clear();
        if (experiment == null) {
            Label empty = new Label("No experiment selected.");
            empty.getStyleClass().add("delta-qualification");
            detail.getChildren().add(empty);
            return;
        }

        Label title = new Label(experiment.title());
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);

        Label status = new Label(experiment.status().name());
        status.getStyleClass().addAll("status-chip", statusStyle(experiment.status()));

        // The question comes first and is styled to be unmissable. An experiment
        // whose question you cannot recall is one whose data you cannot interpret.
        Label questionCaption = new Label("QUESTION");
        questionCaption.getStyleClass().add("card-caption");
        Label question = new Label(experiment.question());
        question.getStyleClass().add("detail-question");
        question.setWrapText(true);

        detail.getChildren().addAll(title, status, spacer(8), questionCaption, question);

        // Dangling references are shown, not thrown. A library with a typo is
        // still worth opening and fixing.
        List<String> problems = library.validate(experiment);
        if (!problems.isEmpty()) {
            Label warn = new Label("⚠ " + String.join("; ", problems));
            warn.getStyleClass().add("detail-warning");
            warn.setWrapText(true);
            detail.getChildren().addAll(spacer(8), warn);
        }

        addDutSection(experiment);
        addProcedureSection(experiment);
        addRunsSection(experiment);
        addConclusionSection(experiment);
    }

    private void addDutSection(Experiment experiment) {
        detail.getChildren().addAll(spacer(10), sectionTitle("UNDER TEST"));
        if (experiment.dutIds().isEmpty()) {
            detail.getChildren().add(muted("No hardware recorded for this experiment."));
            return;
        }
        for (String id : experiment.dutIds()) {
            String text = library.dut(id).map(Dut::summary).orElse(id + "  (not in library)");
            Label row = new Label("• " + text);
            row.getStyleClass().add("trace-readout");
            row.setWrapText(true);
            detail.getChildren().add(row);
        }
    }

    private void addProcedureSection(Experiment experiment) {
        detail.getChildren().addAll(spacer(10), sectionTitle("PROCEDURE"));
        var found = library.procedure(experiment.procedureId());
        if (found.isEmpty()) {
            detail.getChildren().add(muted(experiment.procedureId().isEmpty()
                    ? "No procedure attached. Results from this experiment are not comparable "
                            + "to anything else."
                    : "Procedure '" + experiment.procedureId() + "' is not in this library."));
            return;
        }
        Procedure procedure = found.get();
        Label header = new Label("%s  v%s".formatted(procedure.name(), procedure.version()));
        header.getStyleClass().add("trace-readout");
        Label floor = muted("Sample floor: %,d per path · %s"
                .formatted(procedure.minSamplesPerPath(),
                        procedure.defaultChannel() > 0
                                ? "channel " + procedure.defaultChannel()
                                : "channel unspecified"));
        detail.getChildren().addAll(header, floor, spacer(4));

        for (Procedure.Step step : procedure.steps()) {
            detail.getChildren().add(stepRow(experiment, step));
        }
    }

    /** One procedure step as a tickable row with its verification line beneath. */
    private VBox stepRow(Experiment experiment, Procedure.Step step) {
        String key = experiment.id() + "#" + step.ordinal();
        CheckBox box = new CheckBox("%d. %s".formatted(step.ordinal(), step.instruction()));
        box.setWrapText(true);
        box.setSelected(completedSteps.contains(key));
        box.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                completedSteps.add(key);
            } else {
                completedSteps.remove(key);
            }
        });

        VBox row = new VBox(2, box);
        if (!step.verification().isEmpty()) {
            Label check = new Label("✓ " + step.verification());
            check.getStyleClass().add("step-verification");
            check.setWrapText(true);
            check.setPadding(new Insets(0, 0, 0, 24));
            row.getChildren().add(check);
        }
        row.setPadding(new Insets(3, 0, 3, 0));
        return row;
    }

    private void addRunsSection(Experiment experiment) {
        detail.getChildren().addAll(spacer(10), sectionTitle("RUNS"));
        if (experiment.runIds().isEmpty()) {
            detail.getChildren().add(muted("No runs captured yet."));
        } else {
            for (String runId : experiment.runIds()) {
                Label row = new Label("• " + runId);
                row.getStyleClass().add("trace-readout");
                detail.getChildren().add(row);
            }
        }

        Button capture = new Button("Capture a run…");
        capture.setOnAction(e -> onCaptureRequested.accept(experiment));
        capture.setDisable(experiment.status() == Experiment.Status.CONCLUDED);
        detail.getChildren().addAll(spacer(6), capture);
    }

    private void addConclusionSection(Experiment experiment) {
        detail.getChildren().addAll(spacer(10), sectionTitle("CONCLUSION"));
        if (experiment.status() == Experiment.Status.CONCLUDED) {
            Label text = new Label(experiment.conclusion());
            text.getStyleClass().add("detail-conclusion");
            text.setWrapText(true);
            detail.getChildren().add(text);
            return;
        }

        detail.getChildren().add(muted(experiment.hasData()
                ? "Not concluded yet."
                : "Not concluded. No data captured, so there is nothing to conclude from."));

        Button conclude = new Button("Record conclusion…");
        // Deliberately gated on having data. The point of the whole model is that
        // a conclusion is something a run supports, not something you can type.
        conclude.setDisable(!experiment.hasData());
        conclude.setOnAction(e -> ExperimentDialogs.conclude(experiment, getScene().getWindow())
                .ifPresent(concluded -> {
                    library.put(concluded);
                    library.save();
                    refresh();
                }));
        detail.getChildren().addAll(spacer(6), conclude);
    }

    private static String statusStyle(Experiment.Status status) {
        return switch (status) {
            case PLANNED -> "status-planned";
            case IN_PROGRESS -> "status-in-progress";
            case ANALYSING -> "status-analysing";
            case CONCLUDED -> "status-concluded";
            case ABANDONED -> "status-abandoned";
        };
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("delta-qualification");
        label.setWrapText(true);
        return label;
    }

    private static Region spacer(double height) {
        Region region = new Region();
        region.setMinHeight(height);
        return region;
    }

    /** List cell showing the title, its status, and how many runs it holds. */
    private static final class ExperimentCell extends ListCell<Experiment> {
        @Override
        protected void updateItem(Experiment item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label title = new Label(item.title());
            title.getStyleClass().add("cell-title");
            title.setWrapText(true);
            Label sub = new Label("%s · %d run%s".formatted(
                    item.status().name().toLowerCase(java.util.Locale.ROOT),
                    item.runIds().size(),
                    item.runIds().size() == 1 ? "" : "s"));
            sub.getStyleClass().addAll("cell-subtitle", statusStyle(item.status()));
            VBox box = new VBox(1, title, sub);
            box.setPadding(new Insets(5, 4, 5, 4));
            setGraphic(box);
            setText(null);
        }
    }
}
