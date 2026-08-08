package dev.antennalab.core.report;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;

import java.util.List;

/**
 * Draws the two-trace RSSI chart as an SVG string.
 *
 * <p><b>Why hand-rolled SVG and not a chart snapshot.</b> {@code core} has no
 * JavaFX, deliberately -- that is what keeps the report generator testable on a
 * headless CI box and callable from anywhere. Snapshotting the ScopeView would
 * couple report generation to a running display. SVG is also the right format
 * for the artefact itself: it embeds inline in the HTML (single self-contained
 * file, no image directory to lose), stays sharp at any zoom, and diffs like
 * text.
 *
 * <p>The rendering mirrors the live scope's conventions -- same colours, same
 * dark instrument background, amber chip trace under cyan external trace -- so
 * the report visibly comes from the same bench as the screen the operator
 * watched.
 */
final class SvgChart {

    // Kept in sync with ScopeView's palette; the report should look like the app.
    private static final String BACKGROUND = "#0B0F14";
    private static final String GRID = "#22303D";
    private static final String GRID_MINOR = "#16202A";
    private static final String TEXT = "#7C93A6";
    private static final String CHIP = "#FFB01F";
    private static final String EXTERNAL = "#22D3EE";

    private static final int PAD_LEFT = 64;
    private static final int PAD_RIGHT = 20;
    private static final int PAD_TOP = 24;
    private static final int PAD_BOTTOM = 40;

    private SvgChart() {
    }

    /**
     * Render both traces against sample index.
     *
     * <p>Sample index, not timestamp, on the X axis: imported firmware CSVs may
     * carry no usable timestamps (the importer flags this), and a report must not
     * imply timing precision the data does not have.
     */
    static String render(List<RssiSample> samples, int width, int height) {
        List<RssiSample> chip = samples.stream()
                .filter(s -> s.antenna() == AntennaPath.CHIP).toList();
        List<RssiSample> external = samples.stream()
                .filter(s -> s.antenna() == AntennaPath.EXTERNAL).toList();

        StringBuilder svg = new StringBuilder();
        svg.append(("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" "
                + "role=\"img\" aria-label=\"RSSI traces, chip versus external antenna\">%n")
                .formatted(width, height));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"").append(BACKGROUND)
                .append("\" rx=\"6\"/>\n");

        if (chip.size() < 2 && external.size() < 2) {
            svg.append(("<text x=\"%d\" y=\"%d\" fill=\"%s\" font-family=\"monospace\" "
                    + "font-size=\"14\" text-anchor=\"middle\">no samples</text>%n")
                    .formatted(width / 2, height / 2, TEXT));
            svg.append("</svg>");
            return svg.toString();
        }

        // Y range across both traces, padded, minimum span enforced -- same
        // policy as the live scope so the two visualisations agree.
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (RssiSample s : samples) {
            min = Math.min(min, s.rssiDbm());
            max = Math.max(max, s.rssiDbm());
        }
        min -= 3;
        max += 3;
        if (max - min < 12) {
            double mid = (max + min) / 2;
            min = mid - 6;
            max = mid + 6;
        }

        int plotW = width - PAD_LEFT - PAD_RIGHT;
        int plotH = height - PAD_TOP - PAD_BOTTOM;

        // Horizontal grid lines with dBm labels, at 8 divisions like the scope.
        for (int i = 0; i <= 8; i++) {
            double y = PAD_TOP + plotH * i / 8.0;
            double dbm = max - (max - min) * i / 8.0;
            String colour = (i == 0 || i == 8) ? GRID : GRID_MINOR;
            svg.append(("<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"%s\"/>%n")
                    .formatted(PAD_LEFT, y, PAD_LEFT + plotW, y, colour));
            svg.append(("<text x=\"%d\" y=\"%.1f\" fill=\"%s\" font-family=\"monospace\" "
                    + "font-size=\"11\" text-anchor=\"end\">%.0f</text>%n")
                    .formatted(PAD_LEFT - 8, y + 4, TEXT, dbm));
        }

        // Vertical grid, 10 divisions.
        for (int i = 0; i <= 10; i++) {
            double x = PAD_LEFT + plotW * i / 10.0;
            String colour = (i == 0 || i == 10) ? GRID : GRID_MINOR;
            svg.append(("<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"%s\"/>%n")
                    .formatted(x, PAD_TOP, x, PAD_TOP + plotH, colour));
        }

        svg.append(("<text x=\"%d\" y=\"%d\" fill=\"%s\" font-family=\"monospace\" "
                + "font-size=\"11\">dBm</text>%n").formatted(10, PAD_TOP - 8, TEXT));
        svg.append(("<text x=\"%d\" y=\"%d\" fill=\"%s\" font-family=\"monospace\" "
                + "font-size=\"11\">sample index →</text>%n")
                .formatted(PAD_LEFT, height - 12, TEXT));

        appendTrace(svg, chip, min, max, plotW, plotH, CHIP);
        appendTrace(svg, external, min, max, plotW, plotH, EXTERNAL);

        // Legend, top-left inside the plot like the scope's channel readout.
        legendRow(svg, PAD_LEFT + 12, PAD_TOP + 18, CHIP, "CH1 chip");
        legendRow(svg, PAD_LEFT + 12, PAD_TOP + 36, EXTERNAL, "CH2 external");

        svg.append("</svg>");
        return svg.toString();
    }

    private static void appendTrace(StringBuilder svg,
                                    List<RssiSample> trace,
                                    double min,
                                    double max,
                                    int plotW,
                                    int plotH,
                                    String colour) {
        if (trace.size() < 2) {
            return;
        }
        StringBuilder points = new StringBuilder();
        int n = trace.size();
        for (int i = 0; i < n; i++) {
            double x = PAD_LEFT + (double) i / (n - 1) * plotW;
            double norm = (trace.get(i).rssiDbm() - min) / (max - min);
            double y = PAD_TOP + plotH - Math.clamp(norm, 0.0, 1.0) * plotH;
            if (i > 0) {
                points.append(' ');
            }
            points.append("%.1f,%.1f".formatted(x, y));
        }
        // Soft glow pass under the sharp line, like the scope's phosphor effect.
        svg.append(("<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"4\" "
                + "stroke-opacity=\"0.18\" stroke-linejoin=\"round\"/>%n")
                .formatted(points, colour));
        svg.append(("<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.4\" "
                + "stroke-linejoin=\"round\"/>%n")
                .formatted(points, colour));
    }

    private static void legendRow(StringBuilder svg, int x, int y, String colour, String label) {
        svg.append(("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" "
                + "stroke-width=\"2\"/>%n").formatted(x, y - 4, x + 16, y - 4, colour));
        svg.append(("<text x=\"%d\" y=\"%d\" fill=\"%s\" font-family=\"monospace\" "
                + "font-size=\"12\" font-weight=\"bold\">%s</text>%n")
                .formatted(x + 22, y, colour, label));
    }
}
