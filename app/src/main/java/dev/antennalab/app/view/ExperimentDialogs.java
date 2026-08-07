package dev.antennalab.app.view;

import dev.antennalab.core.lab.Dut;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.LabLibrary;
import dev.antennalab.core.lab.Procedure;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dialogs for creating and closing experiments.
 *
 * <p>Both enforce the same rule the domain model does, at the point where it is
 * cheapest to explain: an experiment needs a question, and a conclusion needs
 * text. The OK button stays disabled with a visible reason rather than letting
 * the user submit and catching {@code IllegalArgumentException}.
 */
final class ExperimentDialogs {

    private ExperimentDialogs() {
    }

    /** Ask for a new experiment. Empty when cancelled. */
    static Optional<Experiment> newExperiment(LabLibrary library, Window owner) {
        Dialog<Experiment> dialog = new Dialog<>();
        dialog.setTitle("New experiment");
        dialog.setHeaderText("What are you trying to find out?");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType create = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);

        TextField title = new TextField();
        title.setPromptText("Design C vs chip antenna at 3 m");

        TextArea question = new TextArea();
        question.setPromptText(
                "How much gain does Design C deliver over the chip antenna, measured like-for-like?");
        question.setPrefRowCount(3);
        question.setWrapText(true);

        ComboBox<Procedure> procedure = new ComboBox<>();
        procedure.getItems().addAll(library.procedures());
        procedure.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Procedure p) {
                return p == null ? "(none)" : "%s v%s".formatted(p.name(), p.version());
            }

            @Override
            public Procedure fromString(String s) {
                return null;
            }
        });
        if (!procedure.getItems().isEmpty()) {
            procedure.getSelectionModel().selectFirst();
        }

        // DUT selection as checkboxes: an experiment usually involves two or three,
        // and a multi-select list hides what is currently ticked.
        VBox dutBox = new VBox(3);
        Map<CheckBox, Dut> dutChoices = new LinkedHashMap<>();
        for (Dut dut : library.duts()) {
            CheckBox box = new CheckBox(dut.summary());
            box.setWrapText(true);
            dutChoices.put(box, dut);
            dutBox.getChildren().add(box);
        }
        if (dutChoices.isEmpty()) {
            dutBox.getChildren().add(new Label("No DUTs in the library yet."));
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label("Title"), title);
        grid.addRow(1, new Label("Question"), question);
        grid.addRow(2, new Label("Procedure"), procedure);
        grid.addRow(3, new Label("Under test"), dutBox);
        dialog.getDialogPane().setContent(grid);

        var okButton = dialog.getDialogPane().lookupButton(create);
        okButton.setDisable(true);
        Runnable validate = () -> okButton.setDisable(
                title.getText().isBlank() || question.getText().isBlank());
        title.textProperty().addListener((o, a, b) -> validate.run());
        question.textProperty().addListener((o, a, b) -> validate.run());

        dialog.setResultConverter(button -> {
            if (button != create) {
                return null;
            }
            List<String> dutIds = new ArrayList<>();
            dutChoices.forEach((box, dut) -> {
                if (box.isSelected()) {
                    dutIds.add(dut.id());
                }
            });
            Procedure chosen = procedure.getSelectionModel().getSelectedItem();
            Instant now = Instant.now();
            return Experiment.plan(
                    slugFor(title.getText(), library),
                    title.getText().strip(),
                    question.getText().strip(),
                    chosen == null ? "" : chosen.id(),
                    dutIds,
                    now);
        });

        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Ask for a conclusion and return the concluded experiment. */
    static Optional<Experiment> conclude(Experiment experiment, Window owner) {
        Dialog<Experiment> dialog = new Dialog<>();
        dialog.setTitle("Record conclusion");
        dialog.setHeaderText(experiment.question());
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType record = new ButtonType("Record", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(record, ButtonType.CANCEL);

        TextArea text = new TextArea();
        text.setPromptText("What did the runs actually show? Include the figure and its "
                + "confidence, and say plainly if the answer is 'not distinguishable'.");
        text.setPrefRowCount(6);
        text.setPrefColumnCount(46);
        text.setWrapText(true);

        VBox box = new VBox(8,
                new Label("Answering: " + experiment.question()),
                text);
        box.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(box);

        var okButton = dialog.getDialogPane().lookupButton(record);
        okButton.setDisable(true);
        text.textProperty().addListener((o, a, b) -> okButton.setDisable(text.getText().isBlank()));

        dialog.setResultConverter(button ->
                button == record ? experiment.concludeWith(text.getText().strip(), Instant.now()) : null);

        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /**
     * Derive a readable, unique id from the title.
     *
     * <p>Readable because the library files are meant to be diffed and, in an
     * emergency, hand-edited -- a UUID in a git diff tells you nothing about what
     * changed.
     */
    private static String slugFor(String title, LabLibrary library) {
        String base = title.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "experiment";
        }
        if (library.experiment(base).isEmpty()) {
            return base;
        }
        int suffix = 2;
        while (library.experiment(base + "-" + suffix).isPresent()) {
            suffix++;
        }
        return base + "-" + suffix;
    }
}
