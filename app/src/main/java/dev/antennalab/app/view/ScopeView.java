package dev.antennalab.app.view;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;

/**
 * The scope: two live RSSI traces on a dark instrument grid.
 *
 * <p>Drawn on a {@link Canvas} rather than a {@code LineChart}. At a few hundred
 * samples per second a LineChart allocates a {@code Data} node per point and
 * relayouts the scene graph on every update, which visibly stutters once the
 * window holds a couple of thousand points. Here each frame is one pass of
 * {@code strokePolyline} over a pre-sized array -- flat cost, no scene-graph
 * churn, and it looks like bench equipment instead of a business chart.
 */
public final class ScopeView extends Region {

    // Instrument palette. Amber for the reference path, cyan for the device under
    // test -- conventional scope channel colours, and distinguishable to the most
    // common forms of colour blindness because they differ in lightness too.
    private static final Color BACKGROUND = Color.web("#0B0F14");
    private static final Color GRID_MINOR = Color.web("#16202A");
    private static final Color GRID_MAJOR = Color.web("#22303D");
    private static final Color AXIS_TEXT = Color.web("#7C93A6");
    private static final Color CHIP_TRACE = Color.web("#FFB01F");
    private static final Color EXTERNAL_TRACE = Color.web("#22D3EE");
    private static final Color MARKER_LINE = Color.web("#E0457B");
    private static final Color NO_SIGNAL_TEXT = Color.web("#3D5163");

    private static final int H_DIVISIONS = 10;
    private static final int V_DIVISIONS = 8;

    private static final double PADDING_LEFT = 56;
    private static final double PADDING_RIGHT = 14;
    private static final double PADDING_TOP = 14;
    private static final double PADDING_BOTTOM = 30;

    /** Y-axis headroom above and below the data, in dB. */
    private static final double Y_PADDING_DB = 3.0;

    /** Never auto-scale tighter than this, or noise fills the screen. */
    private static final double MIN_Y_SPAN_DB = 12.0;

    /**
     * Per-frame easing factor for the auto-scale. Snapping the axis to each
     * frame's min/max makes the whole trace jump every time a single outlier
     * enters or leaves the window; easing keeps it readable.
     */
    private static final double SCALE_EASING = 0.12;

    private final Canvas canvas = new Canvas();

    private List<RssiSample> window = List.of();
    private List<Double> markerPositions = List.of();

    private double displayMinDbm = -90;
    private double displayMaxDbm = -40;
    private boolean scaleInitialised;
    private boolean frozen;

    public ScopeView() {
        getChildren().add(canvas);
        setMinSize(320, 200);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    /**
     * Hand the scope a new frame.
     *
     * <p>Must be called on the JavaFX application thread; the capture pipeline
     * publishes from a virtual thread, so the app hops via {@code Platform.runLater}.
     */
    public void setWindow(List<RssiSample> samples) {
        this.window = samples == null ? List.of() : samples;
        draw();
    }

    /** Fractional 0..1 positions across the window at which to draw marker lines. */
    public void setMarkerPositions(List<Double> positions) {
        this.markerPositions = positions == null ? List.of() : positions;
    }

    /** When frozen the axis stops auto-scaling, so a paused trace holds still. */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);

        double plotW = w - PADDING_LEFT - PADDING_RIGHT;
        double plotH = h - PADDING_TOP - PADDING_BOTTOM;
        if (plotW <= 10 || plotH <= 10) {
            return;
        }

        List<RssiSample> chip = filter(AntennaPath.CHIP);
        List<RssiSample> external = filter(AntennaPath.EXTERNAL);

        updateScale(chip, external);
        drawGrid(g, plotW, plotH);

        if (chip.isEmpty() && external.isEmpty()) {
            drawNoSignal(g, plotW, plotH);
            return;
        }

        int longest = Math.max(chip.size(), external.size());
        drawTrace(g, chip, longest, plotW, plotH, CHIP_TRACE);
        drawTrace(g, external, longest, plotW, plotH, EXTERNAL_TRACE);
        drawMarkers(g, plotW, plotH);
        drawLegend(g, chip, external);
    }

    private List<RssiSample> filter(AntennaPath path) {
        List<RssiSample> out = new ArrayList<>();
        for (RssiSample s : window) {
            if (s.antenna() == path) {
                out.add(s);
            }
        }
        return out;
    }

    /** Ease the visible dBm range toward what the current window actually needs. */
    private void updateScale(List<RssiSample> chip, List<RssiSample> external) {
        if (frozen || (chip.isEmpty() && external.isEmpty())) {
            return;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (RssiSample s : window) {
            min = Math.min(min, s.rssiDbm());
            max = Math.max(max, s.rssiDbm());
        }
        double targetMin = min - Y_PADDING_DB;
        double targetMax = max + Y_PADDING_DB;

        double span = targetMax - targetMin;
        if (span < MIN_Y_SPAN_DB) {
            double mid = (targetMax + targetMin) / 2;
            targetMin = mid - MIN_Y_SPAN_DB / 2;
            targetMax = mid + MIN_Y_SPAN_DB / 2;
        }

        if (!scaleInitialised) {
            displayMinDbm = targetMin;
            displayMaxDbm = targetMax;
            scaleInitialised = true;
        } else {
            displayMinDbm += (targetMin - displayMinDbm) * SCALE_EASING;
            displayMaxDbm += (targetMax - displayMaxDbm) * SCALE_EASING;
        }
    }

    private void drawGrid(GraphicsContext g, double plotW, double plotH) {
        g.setLineWidth(1.0);
        g.setFont(Font.font("Consolas", 10));

        // Horizontal rules with dBm labels.
        for (int i = 0; i <= V_DIVISIONS; i++) {
            double y = PADDING_TOP + plotH * i / V_DIVISIONS;
            g.setStroke(i == 0 || i == V_DIVISIONS ? GRID_MAJOR : GRID_MINOR);
            // +0.5 puts the 1px line on a pixel centre instead of straddling two.
            g.strokeLine(PADDING_LEFT, Math.floor(y) + 0.5, PADDING_LEFT + plotW, Math.floor(y) + 0.5);

            double dbm = displayMaxDbm - (displayMaxDbm - displayMinDbm) * i / V_DIVISIONS;
            g.setFill(AXIS_TEXT);
            g.fillText("%.0f".formatted(dbm), 12, y + 3.5);
        }

        // Vertical rules.
        for (int i = 0; i <= H_DIVISIONS; i++) {
            double x = PADDING_LEFT + plotW * i / H_DIVISIONS;
            g.setStroke(i == 0 || i == H_DIVISIONS ? GRID_MAJOR : GRID_MINOR);
            g.strokeLine(Math.floor(x) + 0.5, PADDING_TOP, Math.floor(x) + 0.5, PADDING_TOP + plotH);
        }

        g.setFill(AXIS_TEXT);
        g.fillText("dBm", 12, PADDING_TOP - 3);
        g.fillText("older", PADDING_LEFT + 2, PADDING_TOP + plotH + 16);
        g.fillText("newer", PADDING_LEFT + plotW - 34, PADDING_TOP + plotH + 16);
    }

    private void drawTrace(GraphicsContext g,
                           List<RssiSample> samples,
                           int longest,
                           double plotW,
                           double plotH,
                           Color colour) {
        if (samples.size() < 2) {
            return;
        }
        int n = samples.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        double span = Math.max(1e-6, displayMaxDbm - displayMinDbm);

        for (int i = 0; i < n; i++) {
            // Each path is plotted against its own ordinal, so the two traces stay
            // aligned even though the RF switch interleaves them in the stream.
            double fraction = longest <= 1 ? 0 : (double) i / (longest - 1);
            xs[i] = PADDING_LEFT + fraction * plotW;
            double norm = (samples.get(i).rssiDbm() - displayMinDbm) / span;
            ys[i] = PADDING_TOP + plotH - Math.clamp(norm, 0.0, 1.0) * plotH;
        }

        // A soft wide pass under the sharp one gives the phosphor glow a scope
        // trace has, at a fraction of the cost of a real blur effect.
        g.setStroke(colour.deriveColor(0, 1, 1, 0.18));
        g.setLineWidth(4.0);
        g.strokePolyline(xs, ys, n);

        g.setStroke(colour);
        g.setLineWidth(1.4);
        g.strokePolyline(xs, ys, n);
    }

    private void drawMarkers(GraphicsContext g, double plotW, double plotH) {
        if (markerPositions.isEmpty()) {
            return;
        }
        g.setStroke(MARKER_LINE);
        g.setLineWidth(1.0);
        g.setLineDashes(4, 4);
        for (double fraction : markerPositions) {
            if (fraction < 0 || fraction > 1) {
                continue;
            }
            double x = PADDING_LEFT + fraction * plotW;
            g.strokeLine(x, PADDING_TOP, x, PADDING_TOP + plotH);
        }
        g.setLineDashes(null);
    }

    private void drawLegend(GraphicsContext g, List<RssiSample> chip, List<RssiSample> external) {
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        double y = PADDING_TOP + 14;
        y = drawLegendRow(g, y, CHIP_TRACE, "CH1 chip", chip);
        drawLegendRow(g, y, EXTERNAL_TRACE, "CH2 external", external);
    }

    private double drawLegendRow(GraphicsContext g, double y, Color colour, String label, List<RssiSample> samples) {
        double x = PADDING_LEFT + 10;
        g.setStroke(colour);
        g.setLineWidth(2.0);
        g.strokeLine(x, y - 4, x + 16, y - 4);
        g.setFill(colour);
        String readout = samples.isEmpty()
                ? label
                : "%s  %.1f dBm".formatted(label, samples.get(samples.size() - 1).rssiDbm());
        g.fillText(readout, x + 22, y);
        return y + 16;
    }

    private void drawNoSignal(GraphicsContext g, double plotW, double plotH) {
        g.setFill(NO_SIGNAL_TEXT);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 15));
        g.fillText("NO SIGNAL  -  press Start", PADDING_LEFT + plotW / 2 - 100, PADDING_TOP + plotH / 2);
    }
}
