package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.SessionMetadata;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.report.HtmlReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report is the contest evidence artefact, so these tests focus on the ways
 * it could mislead: simulated data passing as measured, a delta shown without
 * its qualification, or a session with one antenna path implying a comparison.
 */
class HtmlReportTest {

    private static final Instant T0 = Instant.parse("2026-08-07T12:00:00Z");

    private static Session pairedSession(boolean measured, int perPath) {
        Random rng = new Random(7);
        List<RssiSample> samples = new ArrayList<>();
        long seq = 0;
        for (int i = 0; i < perPath; i++) {
            samples.add(new RssiSample(seq++, T0.plusMillis(i * 50L), AntennaPath.CHIP,
                    -62.0 + rng.nextGaussian() * 2.0));
            samples.add(new RssiSample(seq++, T0.plusMillis(i * 50L), AntennaPath.EXTERNAL,
                    -49.5 + rng.nextGaussian() * 2.0));
        }
        var source = measured
                ? SerialSource.onPort("COM7")
                : SyntheticSource.demo();
        var meta = new SessionMetadata("Bench run", 3.0, "boresight", 6,
                "Platypus patch Rev 7.13.1", "test notes", T0);
        return Session.of(source, meta, samples);
    }

    @Test
    @DisplayName("a synthetic session is watermarked before any figure appears")
    void syntheticDataIsWatermarked() {
        String html = HtmlReport.render(pairedSession(false, 100));

        assertTrue(html.contains("SIMULATED DATA"),
                "a report from a synthetic source must carry the banner");
        // The banner must come BEFORE the headline figure in document order, so a
        // screenshot of the number necessarily includes the provenance.
        assertTrue(html.indexOf("SIMULATED DATA") < html.indexOf("class=\"delta"),
                "the banner must precede the headline");
        assertTrue(html.contains("SIMULATED / REPLAYED"));
    }

    @Test
    @DisplayName("a measured session carries no simulation banner")
    void measuredDataHasNoBanner() {
        String html = HtmlReport.render(pairedSession(true, 100));

        // A permanent "might be fake" banner would train readers to ignore it,
        // so it must be absent for live hardware.
        assertFalse(html.contains("SIMULATED DATA"));
        assertTrue(html.contains("measured on live hardware"));
    }

    @Test
    @DisplayName("the delta headline never appears without its qualification")
    void deltaCarriesQualification() {
        String html = HtmlReport.render(pairedSession(true, 200));

        assertTrue(html.contains("class=\"delta"));
        assertTrue(html.contains("95% CI"),
                "the confidence interval must be printed with the headline");
        assertTrue(html.contains("n=200/200"));
    }

    @Test
    @DisplayName("a single-path session states no comparison is possible")
    void singlePathSaysSo() {
        List<RssiSample> chipOnly = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            chipOnly.add(new RssiSample(i, T0.plusMillis(i * 50L), AntennaPath.CHIP, -62.0 - i % 3));
        }
        Session session = Session.of(SerialSource.onPort("COM7"),
                SessionMetadata.untitled(T0), chipOnly);

        String html = HtmlReport.render(session);

        assertTrue(html.contains("No comparison possible"));
        assertTrue(html.contains("no samples on this path"),
                "the empty external row must say it is empty, not show zeros");
        assertFalse(html.contains("95% CI"), "no interval may be shown without both paths");
    }

    @Test
    @DisplayName("the chart is inline SVG with both traces, in a self-contained document")
    void chartIsInlineAndSelfContained() {
        String html = HtmlReport.render(pairedSession(true, 100));

        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("polyline"));
        assertTrue(html.contains("#FFB01F"), "chip trace colour present");
        assertTrue(html.contains("#22D3EE"), "external trace colour present");
        // Self-contained: nothing the browser would FETCH. The artefact must
        // render offline, from an attachment, in ten years. Checked by looking
        // for reference-bearing attributes rather than the string "http://",
        // because the SVG xmlns declaration legitimately contains that -- an
        // xmlns is a namespace identifier, never a network request.
        assertFalse(html.matches("(?s).*\\bsrc\\s*=.*"), "no fetched resources (src=)");
        assertFalse(html.matches("(?s).*<link[^>]*href.*"), "no external stylesheets");
        assertFalse(html.contains("@import"), "no imported stylesheets");
        assertFalse(html.contains("url("), "no url() fetches in CSS");
        assertFalse(html.contains("<script"), "no scripts in an evidence artefact");
    }

    @Test
    @DisplayName("test conditions appear, and absent ones say 'not recorded'")
    void conditionsAreReported() {
        String html = HtmlReport.render(pairedSession(true, 60));
        assertTrue(html.contains("Platypus patch Rev 7.13.1"));
        assertTrue(html.contains("3.0 m"));
        assertTrue(html.contains("boresight"));

        String bare = HtmlReport.render(Session.of(SerialSource.onPort("COM7"),
                SessionMetadata.untitled(T0), pairedSession(true, 40).samples()));
        assertTrue(bare.contains("not recorded"),
                "missing conditions must be stated rather than omitted");
    }

    @Test
    @DisplayName("metadata text is HTML-escaped")
    void metadataIsEscaped() {
        var meta = new SessionMetadata("Run <script>alert(1)</script>", 0.0, "",
                SessionMetadata.CHANNEL_UNKNOWN, "", "a & b < c", T0);
        Session session = Session.of(SyntheticSource.demo(), meta, pairedSession(false, 40).samples());

        String html = HtmlReport.render(session);

        assertFalse(html.contains("<script>alert"),
                "user text must not become markup");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("a &amp; b &lt; c"));
    }
}
