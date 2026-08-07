package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.ReplaySource;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.SessionMetadata;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelTest {

    private static final Instant T0 = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    @DisplayName("summarise() covers every permitted Source without a default branch")
    void sealedSourceSummaries() {
        assertEquals("Serial COM7 @ 115200 baud",
                Source.summarise(SerialSource.onPort("COM7")));
        assertEquals("Replay run.csv at 1.00x",
                Source.summarise(ReplaySource.realTime(Path.of("captures", "run.csv"))));
        assertTrue(Source.summarise(SyntheticSource.demo()).startsWith("Synthetic (seed 42"));
    }

    @Test
    @DisplayName("only a serial source counts as live hardware")
    void provenanceIsHonest() {
        // The report generator leans on this to avoid presenting a simulation as
        // evidence, so it is worth pinning explicitly.
        assertTrue(SerialSource.onPort("COM3").isLiveHardware());
        assertFalse(SyntheticSource.demo().isLiveHardware());
        assertFalse(ReplaySource.unpaced(Path.of("x.csv")).isLiveHardware());
    }

    @Test
    @DisplayName("synthetic external mean is the chip mean plus the modelled gain")
    void syntheticGainMaths() {
        SyntheticSource s = SyntheticSource.demo();

        assertEquals(12.5, s.externalGainDb(), 1e-9);
        assertEquals(s.chipMeanDbm() + 12.5, s.externalMeanDbm(), 1e-9);
    }

    @Test
    @DisplayName("samples reject impossible values at construction")
    void sampleValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new RssiSample(-1, T0, AntennaPath.CHIP, -60));
        assertThrows(IllegalArgumentException.class,
                () -> new RssiSample(0, null, AntennaPath.CHIP, -60));
        assertThrows(IllegalArgumentException.class,
                () -> new RssiSample(0, T0, AntennaPath.CHIP, Double.NaN));
    }

    @Test
    @DisplayName("implausible-but-finite readings are kept, only flagged")
    void implausibleReadingsAreNotDiscarded() {
        // Silently dropping these would bias the statistics; the UI flags them.
        RssiSample odd = new RssiSample(0, T0, AntennaPath.CHIP, +15.0);

        assertFalse(odd.isPlausible());
        assertTrue(new RssiSample(1, T0, AntennaPath.CHIP, -62.0).isPlausible());
    }

    @Test
    @DisplayName("a session defensively copies its sample list")
    void sessionIsImmutable() {
        List<RssiSample> mutable = new ArrayList<>();
        mutable.add(new RssiSample(0, T0, AntennaPath.CHIP, -62));
        Session session = Session.of(SyntheticSource.demo(), SessionMetadata.untitled(T0), mutable);

        mutable.add(new RssiSample(1, T0, AntennaPath.EXTERNAL, -50));

        assertEquals(1, session.samples().size(),
                "mutating the source list must not affect the session");
        assertThrows(UnsupportedOperationException.class,
                () -> session.samples().add(new RssiSample(2, T0, AntennaPath.CHIP, -61)));
    }

    @Test
    @DisplayName("sessions split samples by antenna path")
    void sessionFiltersByPath() {
        Session session = Session.of(SyntheticSource.demo(), SessionMetadata.untitled(T0), List.of(
                new RssiSample(0, T0, AntennaPath.CHIP, -62),
                new RssiSample(1, T0, AntennaPath.EXTERNAL, -50),
                new RssiSample(2, T0, AntennaPath.CHIP, -63)));

        assertEquals(2, session.countFor(AntennaPath.CHIP));
        assertEquals(1, session.countFor(AntennaPath.EXTERNAL));
        assertTrue(session.hasBothPaths());
        assertFalse(session.isMeasured(), "a synthetic session is not measured data");
    }

    @Test
    @DisplayName("metadata normalises blank free-text but rejects a missing title")
    void metadataValidation() {
        SessionMetadata m = new SessionMetadata("Run 1", 3.0, null, 6, null, null, T0);

        assertEquals("", m.orientation());
        assertEquals("", m.notes());
        assertTrue(m.hasChannel());
        assertFalse(SessionMetadata.untitled(T0).hasChannel());

        assertThrows(IllegalArgumentException.class,
                () -> new SessionMetadata(" ", 3.0, "", 6, "", "", T0));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionMetadata("Run", 3.0, "", 99, "", "", T0));
    }
}
