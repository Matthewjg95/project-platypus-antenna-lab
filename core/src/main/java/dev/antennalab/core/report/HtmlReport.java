package dev.antennalab.core.report;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.SessionMetadata;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.stats.AntennaDelta;
import dev.antennalab.core.stats.TraceStats;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Renders one session as a single self-contained HTML file.
 *
 * <p>This is the evidence artefact: the thing attached to a contest entry, a
 * forum post, or a build log to substantiate a claim about an antenna. That
 * purpose drives every choice here:
 *
 * <ul>
 *   <li><b>One file, nothing external.</b> The chart is inline SVG, the styling
 *       is an inline stylesheet, there is no JavaScript and no CDN. The file
 *       still renders in ten years, offline, from an email attachment.</li>
 *   <li><b>Provenance is unmissable.</b> A report from a synthetic or replayed
 *       source carries a banner saying so before any numbers appear. The most
 *       damaging thing this generator could do is let modelled data pass as
 *       measured.</li>
 *   <li><b>The delta never appears without its qualification.</b> Same rule as
 *       the UI card, same source of truth ({@link AntennaDelta}).</li>
 *   <li><b>Absent data is stated, not padded.</b> A session with one antenna
 *       path gets per-trace statistics and an explicit note that no comparison
 *       is possible -- not a delta computed against nothing.</li>
 * </ul>
 */
public final class HtmlReport {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(ZoneId.systemDefault());

    private HtmlReport() {
    }

    /** Render the session to a complete HTML document. */
    public static String render(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }

        SessionMetadata meta = session.metadata();
        Optional<AntennaDelta> delta = session.hasBothPaths()
                ? Optional.of(AntennaDelta.of(session))
                : Optional.empty();

        StringBuilder h = new StringBuilder(16_384);
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        h.append("<title>").append(esc(meta.title())).append(" - Antenna Lab report</title>\n");
        h.append("<style>\n").append(STYLESHEET).append("</style>\n</head>\n<body>\n");

        h.append("<main>\n");
        header(h, session);
        provenanceBanner(h, session);
        delta.ifPresentOrElse(
                d -> headline(h, d),
                () -> singlePathNote(h, session));
        chart(h, session);
        statsTable(h, session, delta.orElse(null));
        conditions(h, meta, session);
        footer(h, session);
        h.append("</main>\n</body>\n</html>\n");
        return h.toString();
    }

    // ------------------------------------------------------------------ blocks

    private static void header(StringBuilder h, Session session) {
        h.append("<header>\n<p class=\"kicker\">ANTENNA LAB · RF TEST REPORT</p>\n");
        h.append("<h1>").append(esc(session.metadata().title())).append("</h1>\n");
        h.append("<p class=\"muted\">Recorded ")
                .append(esc(TIMESTAMP.format(session.metadata().recordedAt())))
                .append(" · Source: ").append(esc(Source.summarise(session.source())))
                .append("</p>\n</header>\n");
    }

    /**
     * The banner that keeps this generator honest.
     *
     * <p>Rendered before any figure, in the channel-amber used for warnings, so a
     * screenshot of the headline necessarily includes the provenance. Absent
     * entirely for live hardware -- a permanent "this might be fake" banner would
     * train readers to ignore it.
     */
    private static void provenanceBanner(StringBuilder h, Session session) {
        if (session.isMeasured()) {
            return;
        }
        h.append("<div class=\"banner\">SIMULATED DATA — this report was generated from a ")
                .append(session.source() instanceof dev.antennalab.core.domain.SyntheticSource
                        ? "synthetic model" : "replayed capture")
                .append(", not from live hardware. Figures below demonstrate the instrument, "
                        + "not the antenna.</div>\n");
    }

    private static void headline(StringBuilder h, AntennaDelta d) {
        String confidenceClass = switch (d.confidence()) {
            case STRONG -> "strong";
            case MODERATE -> "moderate";
            case WEAK -> "weak";
            case BELOW_RESOLUTION, INSUFFICIENT -> "insufficient";
        };
        h.append("<section class=\"headline\">\n");
        h.append("<p class=\"kicker\">EXTERNAL vs CHIP</p>\n");
        h.append("<p class=\"delta ").append(confidenceClass).append("\">")
                .append(esc(d.headline())).append("</p>\n");
        h.append("<p class=\"qualification\">").append(esc(d.qualification())).append("</p>\n");
        h.append("</section>\n");
    }

    private static void singlePathNote(StringBuilder h, Session session) {
        long chip = session.countFor(AntennaPath.CHIP);
        long external = session.countFor(AntennaPath.EXTERNAL);
        h.append("<section class=\"headline\">\n<p class=\"kicker\">EXTERNAL vs CHIP</p>\n");
        h.append("<p class=\"delta insufficient\">n/a</p>\n");
        h.append("<p class=\"qualification\">No comparison possible: this session holds ")
                .append(chip).append(" chip and ").append(external)
                .append(" external samples. Both paths are required for a delta.</p>\n");
        h.append("</section>\n");
    }

    private static void chart(StringBuilder h, Session session) {
        h.append("<section>\n<h2>Traces</h2>\n<figure>\n");
        h.append(SvgChart.render(session.samples(), 960, 420));
        h.append("\n<figcaption>RSSI per sample, both antenna paths, plotted against sample "
                + "index. Chart is generated from the identical sample list the statistics "
                + "below are computed from.</figcaption>\n</figure>\n</section>\n");
    }

    private static void statsTable(StringBuilder h, Session session, AntennaDelta delta) {
        h.append("<section>\n<h2>Statistics</h2>\n<table>\n<thead><tr>")
                .append("<th>Trace</th><th>n</th><th>Mean</th><th>Median</th><th>Min</th>")
                .append("<th>Max</th><th>p95</th><th>Std dev</th><th>Std err</th>")
                .append("</tr></thead>\n<tbody>\n");
        statsRow(h, "CH1 chip", statsFor(session, AntennaPath.CHIP, delta == null ? null : delta.chip()));
        statsRow(h, "CH2 external",
                statsFor(session, AntennaPath.EXTERNAL, delta == null ? null : delta.external()));
        h.append("</tbody>\n</table>\n");
        h.append("<p class=\"muted\">All values in dBm except the spreads (dB). Mean is the "
                + "dB-domain arithmetic mean -- the convention antenna datasheets quote. "
                + "Percentile is linear-interpolation (R-7); standard deviation is "
                + "sample-based (n−1).</p>\n</section>\n");
    }

    private static TraceStats statsFor(Session session, AntennaPath path, TraceStats precomputed) {
        if (precomputed != null) {
            return precomputed;
        }
        var samples = session.samplesFor(path);
        return samples.isEmpty() ? null : TraceStats.of(samples);
    }

    private static void statsRow(StringBuilder h, String label, TraceStats s) {
        if (s == null) {
            h.append("<tr><td>").append(esc(label))
                    .append("</td><td>0</td><td colspan=\"7\" class=\"muted\">no samples on this path</td></tr>\n");
            return;
        }
        h.append("<tr><td>").append(esc(label)).append("</td>")
                .append("<td>").append(s.count()).append("</td>")
                .append(td(s.meanDbm())).append(td(s.medianDbm()))
                .append(td(s.minDbm())).append(td(s.maxDbm())).append(td(s.p95Dbm()))
                .append("<td>").append("%.2f".formatted(s.stdDevDb())).append("</td>")
                .append("<td>").append("%.3f".formatted(s.stdErrorDb())).append("</td>")
                .append("</tr>\n");
    }

    private static String td(double dbm) {
        return "<td>" + "%.1f".formatted(dbm) + "</td>";
    }

    private static void conditions(StringBuilder h, SessionMetadata meta, Session session) {
        h.append("<section>\n<h2>Test conditions</h2>\n<table class=\"conditions\">\n<tbody>\n");
        conditionRow(h, "Device under test",
                meta.deviceUnderTest().isEmpty() ? "not recorded" : meta.deviceUnderTest());
        conditionRow(h, "Distance",
                meta.distanceMeters() > 0 ? "%.1f m".formatted(meta.distanceMeters()) : "not recorded");
        conditionRow(h, "Orientation",
                meta.orientation().isEmpty() ? "not recorded" : meta.orientation());
        conditionRow(h, "Wi-Fi channel",
                meta.hasChannel() ? String.valueOf(meta.wifiChannel()) : "not recorded");
        conditionRow(h, "Data source", Source.summarise(session.source()));
        conditionRow(h, "Provenance", session.isMeasured()
                ? "measured on live hardware"
                : "SIMULATED / REPLAYED — not a measurement");
        if (!meta.notes().isEmpty()) {
            conditionRow(h, "Notes", meta.notes());
        }
        h.append("</tbody>\n</table>\n");
        h.append("<p class=\"muted\">Fields marked \"not recorded\" were not captured for this "
                + "run. A comparison is only as good as the match between its conditions; "
                + "recording them is what makes two runs comparable.</p>\n</section>\n");
    }

    private static void conditionRow(StringBuilder h, String name, String value) {
        h.append("<tr><th>").append(esc(name)).append("</th><td>")
                .append(esc(value)).append("</td></tr>\n");
    }

    private static void footer(StringBuilder h, Session session) {
        h.append("<footer><p class=\"muted\">Generated by Antenna Lab · session ")
                .append(esc(session.id()))
                .append(" · ").append(session.samples().size()).append(" samples</p></footer>\n");
    }

    /** Minimal HTML escaping for text nodes and attribute values. */
    static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Inline stylesheet, same instrument language as the app: near-black chassis,
     * amber/cyan channels, monospace numbers. Print styles keep it usable on
     * paper, because build logs still get printed.
     */
    private static final String STYLESHEET = """
            :root { color-scheme: dark; }
            * { box-sizing: border-box; }
            body { margin: 0; background: #0B0F14; color: #C9D8E4;
                   font: 14px/1.55 "Segoe UI", system-ui, sans-serif; }
            main { max-width: 1000px; margin: 0 auto; padding: 32px 20px 48px; }
            header h1 { margin: 2px 0 4px; font-size: 26px; }
            h2 { font-size: 13px; letter-spacing: 0.08em; text-transform: uppercase;
                 color: #7C93A6; border-bottom: 1px solid #22303D; padding-bottom: 6px;
                 margin: 34px 0 14px; }
            .kicker { font-size: 11px; font-weight: 700; letter-spacing: 0.1em;
                      color: #7C93A6; margin: 0; }
            .muted { color: #7C93A6; font-size: 12px; }
            .banner { background: #3A2A12; border: 1px solid #FFB01F; color: #FFB01F;
                      border-radius: 4px; padding: 10px 14px; margin: 18px 0;
                      font-weight: 600; font-size: 13px; }
            .headline { background: #141C24; border: 1px solid #22303D; border-radius: 6px;
                        padding: 18px 22px; margin: 18px 0; }
            .delta { font-family: Consolas, "Courier New", monospace; font-size: 44px;
                     font-weight: 700; margin: 2px 0; }
            .delta.strong { color: #4ADE80; }
            .delta.moderate { color: #FFB01F; }
            .delta.weak { color: #F87171; }
            .delta.insufficient { color: #7C93A6; }
            .qualification { color: #7C93A6; font-size: 13px; margin: 0; }
            figure { margin: 0; }
            figure svg { width: 100%; height: auto; }
            figcaption { color: #7C93A6; font-size: 12px; margin-top: 6px; }
            table { border-collapse: collapse; width: 100%;
                    font-family: Consolas, "Courier New", monospace; font-size: 13px; }
            th, td { text-align: right; padding: 7px 12px; border-bottom: 1px solid #16202A; }
            th:first-child, td:first-child { text-align: left; }
            thead th { color: #7C93A6; font-weight: 600; }
            .conditions th { color: #7C93A6; font-weight: 600; width: 200px;
                             vertical-align: top; }
            .conditions td { text-align: left; }
            footer { margin-top: 40px; border-top: 1px solid #22303D; padding-top: 12px; }
            @media print {
                :root { color-scheme: light; }
                body { background: #fff; color: #111; }
                .banner { background: #fff; color: #8a5a00; border-color: #8a5a00; }
                .headline { background: #fff; border-color: #999; }
                h2, .kicker, .muted, .qualification, figcaption { color: #444; }
                th, td { border-color: #ccc; }
            }
            """;
}
