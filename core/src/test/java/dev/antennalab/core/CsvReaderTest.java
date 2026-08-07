package dev.antennalab.core;

import dev.antennalab.core.parse.CsvReader;
import dev.antennalab.core.parse.CsvTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV parsing correctness.
 *
 * <p>Most of these are the cases a naive {@code split(",")} gets wrong, and gets
 * wrong <em>silently</em> -- the import succeeds, every column after the mistake
 * is shifted, and the resulting numbers look entirely reasonable.
 */
class CsvReaderTest {

    @Test
    @DisplayName("a quoted note containing a comma does not shift the columns")
    void quotedCommaDoesNotShiftColumns() {
        // The Platypus logger has a free-text note column. One comma typed into it
        // is enough to make split(",") read the note as the RSSI value.
        CsvTable t = CsvReader.read("""
                timestamp_ms,design_id,rssi_dbm,note
                1000,C,-48.2,"moved left, then paused"
                """);

        assertEquals(List.of("timestamp_ms", "design_id", "rssi_dbm", "note"), t.headers());
        assertEquals(1, t.rows().size());
        CsvTable.Row row = t.rows().get(0);
        assertEquals(4, row.width());
        assertEquals("-48.2", row.values().get(2));
        assertEquals("moved left, then paused", row.values().get(3));
    }

    @Test
    @DisplayName("doubled quotes inside a quoted field become one literal quote")
    void doubledQuotesAreUnescaped() {
        CsvTable t = CsvReader.read("""
                design_id,note
                C,"he said ""boresight"" here"
                """);

        assertEquals("he said \"boresight\" here", t.rows().get(0).values().get(1));
    }

    @Test
    @DisplayName("a newline inside a quoted field stays in that field")
    void embeddedNewlineStaysInField() {
        CsvTable t = CsvReader.read("design_id,note\nC,\"line one\nline two\"\n");

        assertEquals(1, t.rows().size(), "an embedded newline must not split the record");
        assertEquals("line one\nline two", t.rows().get(0).values().get(1));
    }

    @Test
    @DisplayName("a UTF-8 BOM does not become part of the first header name")
    void bomIsStripped() {
        // Excel writes a BOM. Left in place, "timestamp_ms" never matches and the
        // importer reports a missing column that is visibly present in an editor.
        CsvTable t = CsvReader.read("﻿timestamp_ms,rssi_dbm\n1000,-48.2\n");

        assertEquals("timestamp_ms", t.headers().get(0));
        assertTrue(t.columnIndex("timestamp_ms").isPresent());
    }

    @Test
    @DisplayName("CRLF, LF and mixed line endings all parse to the same rows")
    void lineEndingsAreHandled() {
        String expectedHeader = "a,b";
        CsvTable crlf = CsvReader.read(expectedHeader + "\r\n1,2\r\n3,4\r\n");
        CsvTable lf = CsvReader.read(expectedHeader + "\n1,2\n3,4\n");
        CsvTable mixed = CsvReader.read(expectedHeader + "\r\n1,2\n3,4\r\n");

        assertEquals(2, crlf.rows().size());
        assertEquals(2, lf.rows().size());
        assertEquals(2, mixed.rows().size());
        assertEquals(List.of("3", "4"), crlf.rows().get(1).values());
        assertEquals(List.of("3", "4"), mixed.rows().get(1).values());
    }

    @Test
    @DisplayName("a final line with no terminator is still a row")
    void unterminatedFinalLineIsKept() {
        CsvTable t = CsvReader.read("a,b\n1,2\n3,4");

        assertEquals(2, t.rows().size());
        assertEquals(List.of("3", "4"), t.rows().get(1).values());
    }

    @Test
    @DisplayName("trailing empty fields are data and are preserved")
    void trailingEmptyFieldsSurvive() {
        // "note" being empty is meaningful; collapsing the row to 3 fields would
        // make it look malformed against a 4-column header.
        CsvTable t = CsvReader.read("timestamp_ms,design_id,rssi_dbm,note\n1000,C,-48.2,\n");

        assertEquals(4, t.rows().get(0).width());
        assertEquals("", t.rows().get(0).values().get(3));
        assertTrue(t.raggedRows().isEmpty());
    }

    @Test
    @DisplayName("blank lines are skipped but ragged rows are reported")
    void blankLinesSkippedRaggedReported() {
        CsvTable t = CsvReader.read("""
                a,b,c
                1,2,3

                4,5
                6,7,8
                """);

        assertEquals(3, t.rows().size(), "the blank line should not become a row");
        assertEquals(1, t.raggedRows().size());
        assertEquals(2, t.raggedRows().get(0).width());
    }

    @Test
    @DisplayName("row line numbers point at the real source line")
    void lineNumbersAreAccurate() {
        CsvTable t = CsvReader.read("a,b\n1,2\n3,4\n5,6\n");

        // Header is line 1, so the first data row is line 2.
        assertEquals(2, t.rows().get(0).lineNumber());
        assertEquals(3, t.rows().get(1).lineNumber());
        assertEquals(4, t.rows().get(2).lineNumber());
    }

    @Test
    @DisplayName("unquoted fields are trimmed but quoted ones are left exactly as written")
    void whitespaceHandling() {
        CsvTable t = CsvReader.read("a,b\n  1  ,\"  padded  \"\n");

        assertEquals("1", t.rows().get(0).values().get(0));
        assertEquals("  padded  ", t.rows().get(0).values().get(1));
    }

    @Test
    @DisplayName("column lookup is case-insensitive but prefers an exact match")
    void columnLookup() {
        CsvTable t = CsvReader.read("RSSI_dBm,rssi_dbm\n-40,-50\n");

        assertEquals(0, t.columnIndex("RSSI_dBm").orElseThrow());
        assertEquals(1, t.columnIndex("rssi_dbm").orElseThrow());
        assertEquals(0, t.columnIndex("rssi_DBM").orElseThrow(), "falls back to case-insensitive");
        assertTrue(t.columnIndex("nope").isEmpty());
    }

    @Test
    @DisplayName("an empty document yields an empty table rather than throwing")
    void emptyInput() {
        assertTrue(CsvReader.read("").headers().isEmpty());
        assertTrue(CsvReader.read("\n\n").headers().isEmpty());
    }
}
