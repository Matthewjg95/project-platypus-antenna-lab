package dev.antennalab.core.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV parsing, done properly.
 *
 * <p>Splitting on commas is correct until the day a {@code note} field contains
 * one, and then it is silently, permanently wrong -- every column after the note
 * shifts by one, RSSI values become design ids, and the import still "succeeds".
 * The Project Platypus logger has a free-text {@code note} column, so this is not
 * a hypothetical.
 *
 * <p>Handles, because real files do all of these:
 * <ul>
 *   <li>quoted fields containing commas, newlines and doubled quotes</li>
 *   <li>CRLF, LF and lone-CR line endings, mixed within one file</li>
 *   <li>a UTF-8 byte order mark, which Excel writes and which otherwise becomes
 *       part of the first header name and makes that column unfindable</li>
 *   <li>trailing empty fields, which are data and are preserved</li>
 *   <li>a final line with no terminator</li>
 * </ul>
 *
 * <p>Rows of unexpected width are kept and reported rather than padded or
 * truncated -- see {@link CsvTable}.
 */
public final class CsvReader {

    /** U+FEFF, written by Excel and by some ESP32 SD libraries. */
    private static final char BOM = '﻿';

    private CsvReader() {
    }

    /** Parse CSV text. The first non-empty line is taken as the header. */
    public static CsvTable read(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        // Strip the BOM before anything else. Left in place it silently becomes a
        // prefix of the first header name, so "timestamp_ms" never matches and the
        // importer reports a missing column that is plainly there in a text editor.
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }

        List<List<String>> records = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();

        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int line = 1;
        int recordStartLine = 1;
        String detectedEnding = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is one literal quote.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    fieldWasQuoted = true;
                }
                case ',' -> {
                    current.add(finish(field, fieldWasQuoted));
                    field.setLength(0);
                    fieldWasQuoted = false;
                }
                case '\r', '\n' -> {
                    if (detectedEnding == null) {
                        detectedEnding = c == '\r'
                                ? (i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r")
                                : "\n";
                    }
                    // Consume the LF of a CRLF pair so it does not open a blank record.
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    current.add(finish(field, fieldWasQuoted));
                    field.setLength(0);
                    fieldWasQuoted = false;
                    if (!isBlankRecord(current)) {
                        records.add(List.copyOf(current));
                        lineNumbers.add(recordStartLine);
                    }
                    current.clear();
                    line++;
                    recordStartLine = line;
                }
                default -> field.append(c);
            }
        }

        // A last line with no terminator is still a record.
        current.add(finish(field, fieldWasQuoted));
        if (!isBlankRecord(current)) {
            records.add(List.copyOf(current));
            lineNumbers.add(recordStartLine);
        }

        if (records.isEmpty()) {
            return new CsvTable(List.of(), List.of(), detectedEnding);
        }

        List<String> headers = records.get(0);
        List<CsvTable.Row> rows = new ArrayList<>(records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            rows.add(new CsvTable.Row(lineNumbers.get(i), records.get(i)));
        }
        return new CsvTable(headers, rows, detectedEnding);
    }

    /** Read a file as UTF-8. */
    public static CsvTable read(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
        return read(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Finish a field.
     *
     * <p>Unquoted fields are trimmed, because {@code a, b, c} is overwhelmingly
     * meant as three values rather than two with leading spaces. Quoted fields
     * are returned exactly as written -- if someone went to the trouble of
     * quoting {@code " note "}, the spaces are deliberate.
     */
    private static String finish(StringBuilder field, boolean wasQuoted) {
        String value = field.toString();
        return wasQuoted ? value : value.strip();
    }

    /**
     * A record that is entirely empty, i.e. a blank line.
     *
     * <p>Dropped rather than reported: trailing newlines and blank separator
     * lines are formatting, not missing data, and reporting them as malformed
     * rows would bury the rejections that actually matter.
     */
    private static boolean isBlankRecord(List<String> record) {
        return record.size() <= 1 && (record.isEmpty() || record.get(0).isEmpty());
    }
}
