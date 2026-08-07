package dev.antennalab.core.parse;

import dev.antennalab.core.domain.AntennaPath;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a CSV's columns become {@link dev.antennalab.core.domain.RssiSample}s.
 *
 * <p><b>Why this is data rather than code.</b> The firmware's exact column layout
 * is not confirmed, and hard-coding a guess would produce an importer that looks
 * finished and mis-reads real files. A mapping the caller supplies means the
 * importer never assumes anything: it either finds the named columns or reports
 * precisely which ones it could not find. When the real format is confirmed it
 * becomes one more named profile here, and files that predate it still import.
 *
 * <p>{@link #detect(CsvTable)} makes an attempt from common column names, but it
 * is a convenience for the operator, not a fallback the importer relies on -- a
 * failed detection asks the user rather than picking something plausible.
 *
 * @param rssiColumn      column holding the reading in dBm. Required.
 * @param antennaColumn   column identifying which antenna was live. When empty,
 *                        every row maps to {@link #fixedPath}.
 * @param antennaValues   cell value to antenna path, matched case-insensitively.
 * @param fixedPath       used when {@code antennaColumn} is empty -- for files
 *                        that hold one antenna each.
 * @param timestampColumn column holding the timestamp. When empty, samples are
 *                        stamped on import and flagged as such.
 * @param timestampKind   how to read that column.
 * @param sequenceColumn  optional explicit sequence number, for drop detection.
 */
public record ColumnMapping(
        String rssiColumn,
        String antennaColumn,
        Map<String, AntennaPath> antennaValues,
        AntennaPath fixedPath,
        String timestampColumn,
        TimestampKind timestampKind,
        String sequenceColumn) {

    /** How to interpret the timestamp column. */
    public enum TimestampKind {
        /**
         * Milliseconds since the device booted, e.g. Arduino {@code millis()}.
         *
         * <p>Relative, so it needs a base instant to become a wall-clock time.
         * Critically, it also <em>wraps</em>: an unsigned 32-bit millisecond
         * counter rolls over after about 49.7 days of uptime.
         */
        MILLIS_SINCE_BOOT,
        /** Milliseconds since the Unix epoch. */
        EPOCH_MILLIS,
        /** Seconds since the Unix epoch. */
        EPOCH_SECONDS,
        /** ISO-8601 instant, e.g. {@code 2026-08-07T12:00:00Z}. */
        ISO_INSTANT,
        /** No usable timestamp; stamp on arrival. */
        NONE
    }

    /** Column names commonly used for the reading, in preference order. */
    public static final List<String> RSSI_CANDIDATES =
            List.of("rssi_dbm", "rssi", "rssi_median_dbm", "dbm", "signal", "level");

    /** Column names commonly used to identify the antenna or design. */
    public static final List<String> ANTENNA_CANDIDATES =
            List.of("design_id", "design", "antenna", "antenna_path", "path", "dut");

    /** Column names commonly used for the timestamp. */
    public static final List<String> TIMESTAMP_CANDIDATES =
            List.of("timestamp_ms", "timestamp", "time_ms", "millis", "time", "epoch_ms");

    /** Column names commonly used for a sequence number. */
    public static final List<String> SEQUENCE_CANDIDATES =
            List.of("seq", "sequence", "n", "index", "sample");

    public ColumnMapping {
        if (rssiColumn == null || rssiColumn.isBlank()) {
            throw new IllegalArgumentException("rssiColumn is required");
        }
        rssiColumn = rssiColumn.strip();
        antennaColumn = antennaColumn == null ? "" : antennaColumn.strip();
        timestampColumn = timestampColumn == null ? "" : timestampColumn.strip();
        sequenceColumn = sequenceColumn == null ? "" : sequenceColumn.strip();

        Map<String, AntennaPath> normalised = new LinkedHashMap<>();
        if (antennaValues != null) {
            antennaValues.forEach((k, v) -> {
                if (k != null && v != null) {
                    normalised.put(k.strip().toLowerCase(java.util.Locale.ROOT), v);
                }
            });
        }
        antennaValues = Map.copyOf(normalised);

        if (timestampKind == null) {
            timestampKind = TimestampKind.NONE;
        }
        if (antennaColumn.isEmpty() && fixedPath == null) {
            throw new IllegalArgumentException(
                    "either antennaColumn or fixedPath must be given, otherwise there is no way "
                            + "to know which antenna a row belongs to");
        }
        if (!antennaColumn.isEmpty() && normalised.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennaColumn '" + antennaColumn + "' was given with no value mapping, so no "
                            + "row could ever be classified");
        }
    }

    /** Resolve a cell value to an antenna path. */
    public Optional<AntennaPath> pathFor(String cellValue) {
        if (antennaColumn.isEmpty()) {
            return Optional.of(fixedPath);
        }
        if (cellValue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                antennaValues.get(cellValue.strip().toLowerCase(java.util.Locale.ROOT)));
    }

    /** True when timestamps must be synthesised at import time. */
    public boolean stampsOnArrival() {
        return timestampColumn.isEmpty() || timestampKind == TimestampKind.NONE;
    }

    /**
     * The layout documented in Project Platypus's {@code TEST_PROCEDURE.md}.
     *
     * <p><b>Unconfirmed.</b> This is transcribed from the logging sketch in that
     * document (Rev 1.0), not from a capture off the current firmware. It is
     * offered as a starting point for the operator to confirm, which is why it is
     * a named profile rather than the default.
     *
     * <p>{@code timestamp_ms,design_id,theta_deg,phi_deg,rssi_dbm,note}, where
     * {@code design_id} is {@code BASELINE} for the chip antenna and a design
     * letter for the external patch.
     */
    public static ColumnMapping platypusTestProcedure() {
        Map<String, AntennaPath> values = new LinkedHashMap<>();
        values.put("baseline", AntennaPath.CHIP);
        values.put("chip", AntennaPath.CHIP);
        values.put("a", AntennaPath.EXTERNAL);
        values.put("b", AntennaPath.EXTERNAL);
        values.put("c", AntennaPath.EXTERNAL);
        return new ColumnMapping(
                "rssi_dbm",
                "design_id",
                values,
                null,
                "timestamp_ms",
                TimestampKind.MILLIS_SINCE_BOOT,
                "");
    }

    /** A mapping for a file that holds a single antenna's readings. */
    public static ColumnMapping singlePath(String rssiColumn, AntennaPath path) {
        return new ColumnMapping(rssiColumn, "", Map.of(), path, "", TimestampKind.NONE, "");
    }

    /**
     * Best-effort guess at a mapping from the table's headers.
     *
     * <p>Returns empty rather than guessing when the reading column cannot be
     * identified, or when an antenna column is found whose values are not
     * recognised. Half-detecting a layout and importing anyway is how a file gets
     * read wrong quietly, which is the one outcome worth more than a little
     * operator friction to avoid.
     */
    public static Optional<ColumnMapping> detect(CsvTable table) {
        if (table == null || table.headers().isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> rssiIndex = table.firstColumnIndex(RSSI_CANDIDATES);
        if (rssiIndex.isEmpty()) {
            return Optional.empty();
        }
        String rssi = table.headers().get(rssiIndex.get()).strip();

        String timestamp = table.firstColumnIndex(TIMESTAMP_CANDIDATES)
                .map(i -> table.headers().get(i).strip())
                .orElse("");
        TimestampKind kind = switch (timestamp.toLowerCase(java.util.Locale.ROOT)) {
            case "" -> TimestampKind.NONE;
            case "timestamp_ms", "time_ms", "millis" -> TimestampKind.MILLIS_SINCE_BOOT;
            case "epoch_ms" -> TimestampKind.EPOCH_MILLIS;
            default -> TimestampKind.ISO_INSTANT;
        };

        String sequence = table.firstColumnIndex(SEQUENCE_CANDIDATES)
                .map(i -> table.headers().get(i).strip())
                .orElse("");

        Optional<Integer> antennaIndex = table.firstColumnIndex(ANTENNA_CANDIDATES);
        if (antennaIndex.isEmpty()) {
            // No antenna column: a single-path file. Which path it is cannot be
            // guessed from the data, so the operator has to say.
            return Optional.empty();
        }

        // Only claim a mapping if the values in that column are ones we recognise.
        String antenna = table.headers().get(antennaIndex.get()).strip();
        Map<String, AntennaPath> values = new LinkedHashMap<>();
        for (CsvTable.Row row : table.rows()) {
            row.at(antennaIndex.get()).ifPresent(raw -> {
                String key = raw.strip().toLowerCase(java.util.Locale.ROOT);
                if (key.isEmpty() || values.containsKey(key)) {
                    return;
                }
                switch (key) {
                    case "baseline", "chip", "internal", "stock" -> values.put(key, AntennaPath.CHIP);
                    case "a", "b", "c", "external", "patch", "mmcx" ->
                            values.put(key, AntennaPath.EXTERNAL);
                    default -> {
                        // Unrecognised label. Left out deliberately so the caller is
                        // forced to decide rather than have it silently classified.
                    }
                }
            });
        }
        boolean hasChip = values.containsValue(AntennaPath.CHIP);
        boolean hasExternal = values.containsValue(AntennaPath.EXTERNAL);
        if (!hasChip || !hasExternal) {
            return Optional.empty();
        }
        return Optional.of(new ColumnMapping(
                rssi, antenna, values, null, timestamp, kind, sequence));
    }
}
