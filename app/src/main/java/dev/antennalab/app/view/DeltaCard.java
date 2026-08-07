package dev.antennalab.app.view;

import dev.antennalab.core.stats.AntennaDelta;
import dev.antennalab.core.stats.TraceStats;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * The headline card: delta dB between the two antennas, with its qualification.
 *
 * <p>The confidence grade is never optional here. The whole point of the project
 * is a defensible "+12.5 dB", and a big number with no error bar is the one way
 * this UI could actively mislead its own author.
 */
public final class DeltaCard extends VBox {

    /** Every confidence style class, cleared before the current one is applied. */
    private static final java.util.List<String> CONFIDENCE_CLASSES = java.util.List.of(
            "confidence-strong",
            "confidence-moderate",
            "confidence-weak",
            "confidence-below-resolution",
            "confidence-insufficient");

    private final Label headline = new Label("--.- dB");
    private final Label qualification = new Label("No data captured yet");
    private final Label chipLine = new Label();
    private final Label externalLine = new Label();

    public DeltaCard() {
        setSpacing(2);
        setPadding(new Insets(14));
        getStyleClass().add("delta-card");

        Label caption = new Label("EXTERNAL vs CHIP");
        caption.getStyleClass().add("card-caption");

        headline.getStyleClass().add("delta-headline");
        qualification.getStyleClass().add("delta-qualification");
        qualification.setWrapText(true);

        chipLine.getStyleClass().add("trace-readout");
        externalLine.getStyleClass().add("trace-readout");

        Label spacer = new Label();
        spacer.setPadding(new Insets(6, 0, 0, 0));

        getChildren().addAll(caption, headline, qualification, spacer, chipLine, externalLine);
    }

    /** Show a computed delta. */
    public void update(AntennaDelta delta) {
        headline.setText(delta.headline());
        qualification.setText(delta.qualification());

        // Style keyed off the confidence grade, so a weak result cannot be shown
        // in the same confident green as a strong one.
        headline.getStyleClass().removeAll(CONFIDENCE_CLASSES);
        headline.getStyleClass().add(switch (delta.confidence()) {
            case STRONG -> "confidence-strong";
            case MODERATE -> "confidence-moderate";
            case WEAK -> "confidence-weak";
            case BELOW_RESOLUTION -> "confidence-below-resolution";
            case INSUFFICIENT -> "confidence-insufficient";
        });

        chipLine.setText(format("CH1 chip    ", delta.chip()));
        externalLine.setText(format("CH2 external", delta.external()));
    }

    /** Reset to the empty state, e.g. when the scope is cleared. */
    public void clear() {
        headline.setText("--.- dB");
        qualification.setText("No data captured yet");
        chipLine.setText("");
        externalLine.setText("");
        headline.getStyleClass().removeAll(CONFIDENCE_CLASSES);
    }

    private static String format(String label, TraceStats s) {
        return "%s  mean %.1f  med %.1f  p95 %.1f  sd %.2f  n=%d"
                .formatted(label, s.meanDbm(), s.medianDbm(), s.p95Dbm(), s.stdDevDb(), s.count());
    }
}
