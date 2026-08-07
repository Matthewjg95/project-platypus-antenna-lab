package dev.antennalab.core.parse;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a parsed {@link CsvTable} into samples, under an explicit
 * {@link ColumnMapping}.
 *
 * <p>The contract is that nothing disappears. Every row either becomes a sample
 * or becomes a {@link ImportReport.Rejection} carrying its line number and the
 * reason -- there is no path through this class that drops a row silently.
 */
public final class CsvSampleImporter {

    /** Rejection text is truncated for display; whole rows can be long. */
    private static final int RAW_PREVIEW_CHARS = 120;

    /**
     * Unsigned 32-bit millisecond counters wrap after about 49.7 days.
     *
     * <p>A backwards jump larger than this is a rollover; a smaller one is
     * genuinely out-of-order data. Distinguishing them matters because the first
     * is normal and the second means something is wrong upstream.
     */
    private static final long MILLIS_ROLLOVER = 1L << 32;

    private CsvSampleImporter() {
    }

    /**
     * Import samples from a table.
     *
     * @param table   the parsed file.
     * @param mapping how to read its columns.
     * @param baseTime wall-clock instant that device-relative timestamps are
     *                 measured from, and the stamp used when the file has no
     *                 usable timestamp column.
     */
    public static ImportReport importSamples(CsvTable table, ColumnMapping mapping, Instant baseTime) {
        if (table == null || mapping == null || baseTime == null) {
            throw new IllegalArgumentException("table, mapping and baseTime are required");
        }

        List<RssiSample> samples = new ArrayList<>();
        List<ImportReport.Rejection> rejections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Resolve every column up front. A missing column is a whole-file problem,
        // and discovering it on row 40,000 rather than immediately would waste the
        // operator's time and bury the reason.
        Optional<Integer> rssiIndex = table.columnIndex(mapping.rssiColumn());
        if (rssiIndex.isEmpty()) {
            return failWholeFile(table, "no column named '" + mapping.rssiColumn()
                    + "'. Columns present: " + String.join(", ", table.headers()));
        }
        Optional<Integer> antennaIndex = mapping.antennaColumn().isEmpty()
                ? Optional.empty()
                : table.columnIndex(mapping.antennaColumn());
        if (!mapping.antennaColumn().isEmpty() && antennaIndex.isEmpty()) {
            return failWholeFile(table, "no column named '" + mapping.antennaColumn()
                    + "'. Columns present: " + String.join(", ", table.headers()));
        }
        Optional<Integer> timeIndex = mapping.timestampColumn().isEmpty()
                ? Optional.empty()
                : table.columnIndex(mapping.timestampColumn());
        if (!mapping.timestampColumn().isEmpty() && timeIndex.isEmpty()) {
            warnings.add("timestamp column '" + mapping.timestampColumn()
                    + "' not found; samples stamped at import time instead");
        }
        Optional<Integer> seqIndex = mapping.sequenceColumn().isEmpty()
                ? Optional.empty()
                : table.columnIndex(mapping.sequenceColumn());

        if (mapping.stampsOnArrival() || timeIndex.isEmpty()) {
            warnings.add("no device timestamps in this file; ordering follows row order "
                    + "and inter-sample timing is not real");
        }

        Set<String> unrecognisedLabels = new HashSet<>();
        long sequence = 0;
        long previousRawMillis = Long.MIN_VALUE;
        long rolloverOffset = 0;
        int implausible = 0;

        for (CsvTable.Row row : table.rows()) {
            String raw = preview(row);

            if (row.width() != table.headers().size()) {
                rejections.add(new ImportReport.Rejection(row.lineNumber(),
                        "row has %d fields but the header has %d"
                                .formatted(row.width(), table.headers().size()),
                        raw));
                continue;
            }

            String rssiCell = row.at(rssiIndex.get()).orElse("");
            if (rssiCell.isBlank()) {
                rejections.add(new ImportReport.Rejection(row.lineNumber(),
                        "empty value in '" + mapping.rssiColumn() + "'", raw));
                continue;
            }
            double rssi;
            try {
                rssi = Double.parseDouble(rssiCell);
            } catch (NumberFormatException e) {
                rejections.add(new ImportReport.Rejection(row.lineNumber(),
                        "'" + rssiCell + "' in '" + mapping.rssiColumn() + "' is not a number",
                        raw));
                continue;
            }
            if (Double.isNaN(rssi) || Double.isInfinite(rssi)) {
                rejections.add(new ImportReport.Rejection(row.lineNumber(),
                        "'" + rssiCell + "' is not a finite reading", raw));
                continue;
            }

            String antennaCell = antennaIndex.map(i -> row.at(i).orElse("")).orElse("");
            Optional<AntennaPath> path = mapping.pathFor(antennaCell);
            if (path.isEmpty()) {
                unrecognisedLabels.add(antennaCell);
                rejections.add(new ImportReport.Rejection(row.lineNumber(),
                        "'" + antennaCell + "' in '" + mapping.antennaColumn()
                                + "' is not mapped to an antenna path", raw));
                continue;
            }

            Instant timestamp;
            if (timeIndex.isPresent() && !mapping.stampsOnArrival()) {
                String timeCell = row.at(timeIndex.get()).orElse("");
                try {
                    ParsedTime parsed = parseTime(timeCell, mapping.timestampKind(), baseTime,
                            previousRawMillis, rolloverOffset);
                    timestamp = parsed.instant();
                    previousRawMillis = parsed.rawMillis();
                    rolloverOffset = parsed.rolloverOffset();
                    if (parsed.rolledOver()) {
                        warnings.add("millisecond counter wrapped at line " + row.lineNumber()
                                + "; timestamps after it were corrected by adding 2^32 ms");
                    }
                } catch (RuntimeException e) {
                    rejections.add(new ImportReport.Rejection(row.lineNumber(),
                            "'" + timeCell + "' in '" + mapping.timestampColumn()
                                    + "' is not a valid " + mapping.timestampKind(), raw));
                    continue;
                }
            } else {
                timestamp = baseTime;
            }

            long seq = sequence++;
            if (seqIndex.isPresent()) {
                String seqCell = row.at(seqIndex.get()).orElse("");
                try {
                    seq = Long.parseLong(seqCell.strip());
                } catch (NumberFormatException e) {
                    // The generated ordinal is a fine substitute; losing a whole
                    // reading over a bad sequence number would be the wrong trade.
                    warnings.add("line " + row.lineNumber() + ": sequence '" + seqCell
                            + "' is not a number, using row order instead");
                }
            }

            RssiSample sample = new RssiSample(Math.max(0, seq), timestamp, path.get(), rssi);
            if (!sample.isPlausible()) {
                implausible++;
            }
            samples.add(sample);
        }

        if (implausible > 0) {
            // Kept, not dropped -- discarding them would bias the statistics. But a
            // cluster of these almost always means the wrong column was mapped.
            warnings.add(implausible + " reading(s) fall outside the plausible RSSI range "
                    + "(-120..0 dBm); check that '" + mapping.rssiColumn()
                    + "' is really the reading column");
        }
        if (!unrecognisedLabels.isEmpty()) {
            warnings.add("unmapped antenna labels: " + String.join(", ", unrecognisedLabels));
        }

        return new ImportReport(samples, rejections,
                unmappedColumns(table, List.of(rssiIndex, antennaIndex, timeIndex, seqIndex)),
                warnings);
    }

    /** Result of reading one timestamp cell, carrying rollover state forward. */
    private record ParsedTime(Instant instant, long rawMillis, long rolloverOffset,
                              boolean rolledOver) {
    }

    private static ParsedTime parseTime(String cell,
                                        ColumnMapping.TimestampKind kind,
                                        Instant baseTime,
                                        long previousRawMillis,
                                        long rolloverOffset) {
        String value = cell.strip();
        return switch (kind) {
            case ISO_INSTANT -> new ParsedTime(Instant.parse(value), Long.MIN_VALUE, 0, false);
            case EPOCH_MILLIS -> new ParsedTime(
                    Instant.ofEpochMilli(Long.parseLong(value)), Long.MIN_VALUE, 0, false);
            case EPOCH_SECONDS -> new ParsedTime(
                    Instant.ofEpochSecond(Long.parseLong(value)), Long.MIN_VALUE, 0, false);
            case MILLIS_SINCE_BOOT -> {
                long millis = Long.parseLong(value);
                long offset = rolloverOffset;
                boolean wrapped = false;
                // A counter that goes backwards has either wrapped or the file is
                // out of order. Treat a large backwards jump as the wrap it almost
                // certainly is, and keep timestamps monotonic across it.
                if (previousRawMillis != Long.MIN_VALUE && millis < previousRawMillis
                        && (previousRawMillis - millis) > MILLIS_ROLLOVER / 2) {
                    offset += MILLIS_ROLLOVER;
                    wrapped = true;
                }
                yield new ParsedTime(baseTime.plusMillis(millis + offset), millis, offset, wrapped);
            }
            case NONE -> new ParsedTime(baseTime, Long.MIN_VALUE, 0, false);
        };
    }

    // Takes a List rather than varargs: a generic-array parameter would need
    // @SafeVarargs and buys nothing at four call sites.
    private static List<String> unmappedColumns(CsvTable table, List<Optional<Integer>> used) {
        Set<Integer> consumed = new HashSet<>();
        for (Optional<Integer> index : used) {
            index.ifPresent(consumed::add);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < table.headers().size(); i++) {
            if (!consumed.contains(i)) {
                out.add(table.headers().get(i));
            }
        }
        return out;
    }

    /** Every row rejected for the same whole-file reason. */
    private static ImportReport failWholeFile(CsvTable table, String reason) {
        List<ImportReport.Rejection> all = table.rows().stream()
                .map(r -> new ImportReport.Rejection(r.lineNumber(), reason, preview(r)))
                .toList();
        return new ImportReport(List.of(), all, table.headers(), List.of(reason));
    }

    private static String preview(CsvTable.Row row) {
        String joined = String.join(",", row.values());
        return joined.length() <= RAW_PREVIEW_CHARS
                ? joined
                : joined.substring(0, RAW_PREVIEW_CHARS) + "...";
    }
}
