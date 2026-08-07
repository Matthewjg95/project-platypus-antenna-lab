package dev.antennalab.core.parse;

import java.util.List;
import java.util.Optional;

/**
 * A CSV file, parsed but not interpreted.
 *
 * <p>Header names and cell values exactly as they appeared, with the source line
 * number kept on every row. Nothing here knows what an RSSI reading is.
 *
 * <p><b>Why the split matters.</b> Reading a file and understanding a file are
 * different problems with different failure modes, and fusing them is how
 * importers end up silently wrong. A parser that also interprets has to decide
 * what to do with a row it cannot understand while it is still mid-file, and the
 * convenient answer is always "skip it". Keeping the table lossless means every
 * row survives to the interpretation stage, where a rejection can be reported
 * with a line number and a reason instead of vanishing.
 *
 * @param headers    column names in file order, as written.
 * @param rows       every data row, including ones whose width does not match
 *                   the header -- those are reported, never quietly repaired.
 * @param lineEnding what the file actually used, preserved for diagnostics.
 */
public record CsvTable(List<String> headers, List<Row> rows, String lineEnding) {

    /**
     * One data row.
     *
     * @param lineNumber 1-based line in the source file, for error messages that
     *                   a human can act on.
     * @param values     cells as parsed, quotes resolved, in file order.
     */
    public record Row(int lineNumber, List<String> values) {

        public Row {
            values = List.copyOf(values);
        }

        /** Cell at an index, or empty when the row is short. */
        public Optional<String> at(int index) {
            return index >= 0 && index < values.size()
                    ? Optional.of(values.get(index))
                    : Optional.empty();
        }

        public int width() {
            return values.size();
        }
    }

    public CsvTable {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
        lineEnding = lineEnding == null ? "\n" : lineEnding;
    }

    /**
     * Index of a column by name, case-insensitively and ignoring surrounding
     * whitespace.
     *
     * <p>Case-insensitive because a header is written by a human (or by firmware
     * a human wrote) and {@code RSSI_dBm} versus {@code rssi_dbm} is not a
     * difference worth failing an import over. Exact-match-first, so a file with
     * both spellings still resolves deterministically.
     */
    public Optional<Integer> columnIndex(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.strip();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).strip().equals(wanted)) {
                return Optional.of(i);
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).strip().equalsIgnoreCase(wanted)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /** First matching column from the candidates, in preference order. */
    public Optional<Integer> firstColumnIndex(List<String> candidates) {
        for (String candidate : candidates) {
            Optional<Integer> found = columnIndex(candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Rows whose width does not match the header count. */
    public List<Row> raggedRows() {
        return rows.stream().filter(r -> r.width() != headers.size()).toList();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
