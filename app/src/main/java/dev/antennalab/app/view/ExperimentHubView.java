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

import java.util.List;
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
 * <p>The working checklist is the experiment's own <em>milestones</em> —
 * persisted ticks that survive a restart, serving as the resume point after
 * time away and the confidence gate on the conclusion. The procedure's steps
 * render as reference text beneath them: the method, not the to-do list. Tick
 * state on the shared, versioned procedure would leak between experiments.
 */
public final class ExperimentHubView extends BorderPane {

    private final LabLibrary library;
    private final ObservableList<Experiment> experiments = FXCollections.observableArrayList();
    private final ListView<Experiment> experimentList = new ListView<>(experiments);
    private final VBox detail = new VBox(6);

    private final Label libraryStatus = new Label();
    private Consumer<Experiment> onCaptureRequested = e -> { };
    private Consumer<Experiment> onRunRequested = e -> { };

    /**
     * Resolves run ids to stored data. Optional so the render tests can build a
     * hub without touching the filesystem; when absent, runs are listed but
     * cannot be opened.
     */
    private dev.antennalab.core.session.SessionStore sessionStore;

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

    /** Called when the operator asks to run the experiment's procedure automatically. */
    public void setOnRunRequested(Consumer<Experiment> handler) {
        this.onRunRequested = handler == null ? e -> { } : handler;
    }

    /** Give the hub a store so run ids resolve to openable data. */
    public void setSessionStore(dev.antennalab.core.session.SessionStore store) {
        this.sessionStore = store;
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

        Button newAntenna = new Button("New antenna");
        newAntenna.setOnAction(e -> ExperimentDialogs.newDut(library, getScene().getWindow())
                .ifPresent(created -> {
                    library.put(created);
                    library.save();
                    updateStatus("Registered '" + created.name() + "' — available to every experiment");
                }));

        Button save = new Button("Save library");
        save.setOnAction(e -> {
            library.save();
            updateStatus("Saved to " + library.root());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        return new ToolBar(newExperiment, newAntenna, new Separator(), save, spacer);
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
        addMilestoneSection(experiment);
        addProcedureSection(experiment);
        addRunsSection(experiment);
        addConclusionSection(experiment);
    }

    /**
     * The experiment's own checklist: the resume point and the confidence gate.
     *
     * <p>Ticks write straight through to the library and are saved immediately,
     * because a resume point that survives only until the app closes is not a
     * resume point. This replaced an in-memory set that silently forgot every
     * tick on restart — the least defensible kind of state to lose.
     */
    private void addMilestoneSection(Experiment experiment) {
        if (experiment.milestones().isEmpty()) {
            // Experiments created before milestones existed (or without a
            // procedure) have none. Offer the copy explicitly rather than
            // mutating a record just because it was looked at.
            library.procedure(experiment.procedureId()).ifPresent(procedure -> {
                Button adopt = new Button("Add checklist");
                adopt.setOnAction(e -> {
                    // One box per DUT plus the conclusion -- progress against
                    // the question, resolved from the experiment's own DUT list.
                    List<Dut> duts = experiment.dutIds().stream()
                            .map(library::dut)
                            .flatMap(java.util.Optional::stream)
                            .toList();
                    library.put(experiment.withMilestones(
                            dev.antennalab.core.lab.Milestone.templateFor(procedure, duts),
                            java.time.Instant.now()));
                    library.save();
                    refresh();
                });
                detail.getChildren().addAll(spacer(10), sectionTitle("MILESTONES"), adopt);
            });
            return;
        }
        detail.getChildren().addAll(spacer(10), sectionTitle("MILESTONES"));

        boolean complete = experiment.milestonesComplete();
        Label gate = muted(complete
                ? "All boxes ticked — the conclusion can be trusted to this checklist."
                : "Unticked boxes are the to-do list; tick them all before trusting a conclusion.");
        gate.setWrapText(true);
        detail.getChildren().add(gate);

        for (int i = 0; i < experiment.milestones().size(); i++) {
            int index = i;
            var milestone = experiment.milestones().get(i);
            CheckBox box = new CheckBox(milestone.label());
            box.setWrapText(true);
            box.setSelected(milestone.done());
            box.selectedProperty().addListener((obs, was, is) -> {
                library.put(experiment.withMilestoneDone(index, is, java.time.Instant.now()));
                library.save();
                // Re-render so the gate line and the list row reflect the tick.
                refresh();
            });
            box.setPadding(new Insets(3, 0, 3, 0));
            detail.getChildren().add(box);
        }
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

    /**
     * One procedure step as reference text — the method, not a to-do list.
     *
     * <p>These used to be checkboxes backed by an in-memory set, which put tick
     * state on the shared, versioned procedure and forgot it on every restart.
     * Tick state now lives on the experiment as milestones; the steps remain
     * here as the description of how the measurement is made.
     */
    private VBox stepRow(Experiment experiment, Procedure.Step step) {
        Label instruction = new Label("%d. %s".formatted(step.ordinal(), step.instruction()));
        instruction.setWrapText(true);

        VBox row = new VBox(2, instruction);
        if (!step.verification().isEmpty()) {
            Label check = new Label("✓ " + step.verification());
            check.getStyleClass().add("step-verification");
            check.setWrapText(true);
            check.setPadding(new Insets(0, 0, 0, 18));
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
                detail.getChildren().add(runRow(runId));
            }
        }

        boolean concluded = experiment.status() == Experiment.Status.CONCLUDED;

        Button run = new Button("Run experiment");
        run.getStyleClass().add("primary");
        run.setOnAction(e -> onRunRequested.accept(experiment));
        run.setDisable(concluded);

        Button capture = new Button("Free capture…");
        capture.setOnAction(e -> onCaptureRequested.accept(experiment));
        capture.setDisable(concluded);

        HBox actions = new HBox(8, run, capture);
        detail.getChildren().addAll(spacer(6), actions);
    }

    /**
     * One run, expandable into what it actually found.
     *
     * <p>A run id on its own is an assertion; the numbers under it are the
     * evidence. Clicking resolves the id through the session store and shows the
     * counts and delta — or says plainly that the data is missing, which is a
     * real state (a deleted file, a run recorded before persistence existed) and
     * must not look the same as a run with no effect.
     */
    private VBox runRow(String runId) {
        Label header = new Label("▸ " + runId);
        header.getStyleClass().add("trace-readout");
        VBox row = new VBox(2, header);
        Label detailLine = new Label();
        detailLine.getStyleClass().add("step-verification");
        detailLine.setWrapText(true);

        header.setOnMouseClicked(e -> {
            if (row.getChildren().size() > 1) {
                row.getChildren().remove(detailLine);
                header.setText("▸ " + runId);
                return;
            }
            header.setText("▾ " + runId);
            detailLine.setText(describeRun(runId));
            row.getChildren().add(detailLine);
        });
        header.setStyle("-fx-cursor: hand;");
        return row;
    }

    /** Summarise a stored run, or explain why it cannot be summarised. */
    private String describeRun(String runId) {
        if (sessionStore == null) {
            return "no session store attached";
        }
        var found = sessionStore.load(runId);
        if (found.isEmpty()) {
            return "data not found — this run id has no stored session";
        }
        var session = found.get();
        long chip = session.countFor(dev.antennalab.core.domain.AntennaPath.CHIP);
        long ext = session.countFor(dev.antennalab.core.domain.AntennaPath.EXTERNAL);
        String counts = "n=%d chip / %d external".formatted(chip, ext);
        if (!session.hasBothPaths()) {
            return counts + " — one path only, no comparison possible";
        }
        var delta = dev.antennalab.core.stats.AntennaDelta.of(session);
        // The headline never appears without its qualification, here as anywhere.
        return "%s — %s, %s".formatted(counts, delta.headline(), delta.qualification());
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
