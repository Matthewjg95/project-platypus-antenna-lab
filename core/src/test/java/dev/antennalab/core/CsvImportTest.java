package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.parse.ColumnMapping;
import dev.antennalab.core.parse.CsvReader;
import dev.antennalab.core.parse.CsvSampleImporter;
import dev.antennalab.core.parse.CsvTable;
import dev.antennalab.core.parse.ImportReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import behaviour, with an emphasis on what happens to rows that fail.
 *
 * <p>The rule under test throughout: nothing disappears. A row either becomes a
 * sample or becomes a rejection with a line number and a reason.
 */
class CsvImportTest {

    private static final Instant BASE = Instant.parse("2026-08-07T12:00:00Z");

    private static ImportReport importOf(String csv, ColumnMapping mapping) {
        return CsvSampleImporter.importSamples(CsvReader.read(csv), mapping, BASE);
    }

    @Test
    @DisplayName("the documented Platypus layout imports both antenna paths")
    void platypusLayoutImports() {
        String csv = """
                timestamp_ms,design_id,theta_deg,phi_deg,rssi_dbm,note
                1000,BASELINE,0,0,-40.5,start
                2000,BASELINE,0,0,-41.0,
                3000,C,0,0,-28.0,patch fitted
                4000,C,0,0,-28.4,
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(4, r.samples().size());
        assertTrue(r.isClean());
        assertEquals(2, r.countFor(AntennaPath.CHIP));
        assertEquals(2, r.countFor(AntennaPath.EXTERNAL));
        // theta/phi/note are not part of this mapping; surfacing them lets the
        // operator notice a mis-set mapping rather than wonder where they went.
        assertTrue(r.unmappedColumns().containsAll(java.util.List.of("theta_deg", "phi_deg", "note")));
    }

    @Test
    @DisplayName("device millisecond timestamps become real instants relative to the base")
    void millisSinceBootBecomesWallClock() {
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                61000,BASELINE,-41.0
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(BASE.plusMillis(1000), r.samples().get(0).timestamp());
        assertEquals(BASE.plusMillis(61000), r.samples().get(1).timestamp());
    }

    @Test
    @DisplayName("a wrapped millisecond counter is corrected, not read as time going backwards")
    void millisRolloverIsHandled() {
        // millis() is unsigned 32-bit and wraps after ~49.7 days of uptime. Read
        // naively, the second row lands 49 days BEFORE the first, and any
        // duration or rate computed from the run is nonsense.
        long justBefore = 4_294_967_000L;
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                %d,BASELINE,-40.5
                500,BASELINE,-41.0
                """.formatted(justBefore);

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(2, r.samples().size());
        Instant first = r.samples().get(0).timestamp();
        Instant second = r.samples().get(1).timestamp();
        assertTrue(second.isAfter(first),
                "timestamps must stay monotonic across a counter wrap, got "
                        + first + " then " + second);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("wrapped")),
                "the correction should be reported, not applied silently");
    }

    @Test
    @DisplayName("a non-numeric reading is rejected with its line number, not skipped")
    void badReadingIsRejectedWithLineNumber() {
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                2000,BASELINE,n/a
                3000,BASELINE,-41.0
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(2, r.samples().size());
        assertEquals(1, r.rejections().size());
        ImportReport.Rejection bad = r.rejections().get(0);
        assertEquals(3, bad.lineNumber(), "should point at the actual source line");
        assertTrue(bad.reason().contains("n/a"));
        assertTrue(bad.reason().contains("not a number"));
        assertEquals(3, r.rowsRead());
    }

    @Test
    @DisplayName("an unmapped antenna label is rejected rather than guessed at")
    void unknownAntennaLabelIsRejected() {
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                2000,MYSTERY,-30.0
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(1, r.samples().size());
        assertEquals(1, r.rejections().size());
        assertTrue(r.rejections().get(0).reason().contains("MYSTERY"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("MYSTERY")));
    }

    @Test
    @DisplayName("a ragged row is rejected, never padded or truncated into shape")
    void raggedRowIsRejected() {
        // Padding it would invent a value; truncating would shift the columns.
        // Both produce a plausible-looking sample that is simply wrong.
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                2000,BASELINE
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(1, r.samples().size());
        assertEquals(1, r.rejections().size());
        assertTrue(r.rejections().get(0).reason().contains("2 fields"));
        assertTrue(r.rejections().get(0).reason().contains("header has 3"));
    }

    @Test
    @DisplayName("a missing reading column fails the whole file with the columns it did find")
    void missingColumnFailsLoudly() {
        String csv = """
                timestamp_ms,design_id,signal_strength
                1000,BASELINE,-40.5
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertTrue(r.samples().isEmpty());
        assertEquals(1, r.rejections().size());
        String reason = r.rejections().get(0).reason();
        assertTrue(reason.contains("rssi_dbm"));
        // Listing what IS there turns "it didn't work" into an actionable message.
        assertTrue(reason.contains("signal_strength"));
    }

    @Test
    @DisplayName("implausible readings are kept but flagged as a likely wrong column")
    void implausibleReadingsAreKeptAndFlagged() {
        // Dropping them would bias the statistics; a cluster of them almost always
        // means theta_deg got mapped as the reading.
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,15.0
                2000,BASELINE,30.0
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertEquals(2, r.samples().size(), "implausible readings are kept, not discarded");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("plausible")));
    }

    @Test
    @DisplayName("the summary leads with rejections when there are any")
    void summaryDoesNotHideLosses() {
        String csv = """
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                2000,BASELINE,bad
                3000,BASELINE,also bad
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        // "Imported 1 sample" would be true and misleading.
        assertTrue(r.summary().contains("rejected"), "summary was: " + r.summary());
        assertFalse(r.isClean());
        assertEquals(1.0 / 3, r.acceptanceRate(), 1e-9);
    }

    @Test
    @DisplayName("a file with no timestamps imports, and says the timing is not real")
    void missingTimestampsAreFlagged() {
        ImportReport r = importOf("""
                design_id,rssi_dbm
                BASELINE,-40.5
                C,-28.0
                """, new ColumnMapping("rssi_dbm", "design_id",
                java.util.Map.of("baseline", AntennaPath.CHIP, "c", AntennaPath.EXTERNAL),
                null, "", ColumnMapping.TimestampKind.NONE, ""));

        assertEquals(2, r.samples().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("not real")));
    }

    @Test
    @DisplayName("detection recognises the documented layout")
    void detectionFindsPlatypusLayout() {
        CsvTable t = CsvReader.read("""
                timestamp_ms,design_id,theta_deg,phi_deg,rssi_dbm,note
                1000,BASELINE,0,0,-40.5,
                2000,C,0,0,-28.0,
                """);

        Optional<ColumnMapping> detected = ColumnMapping.detect(t);

        assertTrue(detected.isPresent());
        assertEquals("rssi_dbm", detected.get().rssiColumn());
        assertEquals("design_id", detected.get().antennaColumn());
        assertEquals(ColumnMapping.TimestampKind.MILLIS_SINCE_BOOT, detected.get().timestampKind());
    }

    @Test
    @DisplayName("detection declines rather than guessing when only one path is present")
    void detectionDeclinesOnAmbiguity() {
        // Every row is BASELINE. A mapping claiming to handle both paths would be
        // a fabrication, so detection returns empty and the operator decides.
        CsvTable t = CsvReader.read("""
                timestamp_ms,design_id,rssi_dbm
                1000,BASELINE,-40.5
                2000,BASELINE,-41.0
                """);

        assertTrue(ColumnMapping.detect(t).isEmpty());
    }

    @Test
    @DisplayName("detection declines when there is no reading column at all")
    void detectionDeclinesWithoutRssi() {
        CsvTable t = CsvReader.read("time,design_id,volts\n1,BASELINE,3.3\n");

        assertTrue(ColumnMapping.detect(t).isEmpty());
    }

    @Test
    @DisplayName("a single-path file imports under an explicit fixed path")
    void singlePathFile() {
        ImportReport r = importOf("""
                rssi_dbm
                -40.5
                -41.0
                -40.8
                """, ColumnMapping.singlePath("rssi_dbm", AntennaPath.CHIP));

        assertEquals(3, r.samples().size());
        assertEquals(3, r.countFor(AntennaPath.CHIP));
        assertEquals(0, r.countFor(AntennaPath.EXTERNAL));
    }

    @Test
    @DisplayName("a quoted note containing a comma survives the whole import path")
    void quotedNoteDoesNotCorruptTheImport() {
        // End-to-end version of the parser test: if quoting were mishandled, the
        // note text would land in rssi_dbm and this row would be rejected.
        String csv = """
                timestamp_ms,design_id,rssi_dbm,note
                1000,C,-28.0,"rotated 90 deg, then paused"
                """;

        ImportReport r = importOf(csv, ColumnMapping.platypusTestProcedure());

        assertTrue(r.isClean(), "rejections: " + r.rejections());
        assertEquals(-28.0, r.samples().get(0).rssiDbm(), 1e-9);
    }
}
