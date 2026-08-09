package dev.antennalab.core.parse;

import dev.antennalab.core.domain.AntennaPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the M5Tab5 antenna firmware's serial log.
 *
 * <p><b>Built against a real capture</b> — {@code tab5-raw-2026-08-08-213501.txt}
 * in the test fixtures, taken from the running device over USB CDC. Nothing here
 * is guessed; every rule below cites what the capture actually contains.
 *
 * <p>The stream is a line-oriented debug log, not a data protocol. Readings are
 * interleaved with boot banners, heartbeat lines, touch events and scan chatter.
 * The lines that matter:
 *
 * <pre>
 * [SAMPLE] enter: INT = -35 dBm     one RSSI reading, tagged with the live path
 * [SAMPLE] enter: EXT = -39 dBm
 * [ANT] -&gt; EXT P0=HIGH ok           RF switch transition (informational)
 * </pre>
 *
 * <p>Design consequences, each traceable to the capture:
 * <ul>
 *   <li><b>The path rides on every sample</b> ({@code INT}/{@code EXT}), so no
 *       mode-tracking state machine is needed — a dropped switch line cannot
 *       misattribute later readings.</li>
 *   <li><b>No device timestamps</b> on samples. The producer stamps on arrival;
 *       at the observed ~1.5–2 s cadence, USB latency is negligible.</li>
 *   <li><b>Whole-dBm values only</b>, consistent with the ESP32's 1 dBm
 *       quantisation (and with the stats layer's BELOW_RESOLUTION floor).</li>
 *   <li><b>Boot banners occur mid-stream</b>: opening the port asserts DTR and
 *       resets the board, so a capture legitimately starts with ROM output.
 *       Everything unrecognised is counted and skipped, never fatal.</li>
 * </ul>
 */
public final class Tab5LogParser {

    /**
     * {@code [SAMPLE] enter: INT = -35 dBm} — anchored to line start so a
     * corrupted half-line glued to a previous fragment cannot produce a reading.
     * The value group tolerates a fractional part in case a future firmware
     * reports tenths; today's capture is integer-only.
     */
    private static final Pattern SAMPLE = Pattern.compile(
            "^\\[SAMPLE] enter: (INT|EXT) = (-?\\d+(?:\\.\\d+)?) dBm");

    /** {@code [ANT] -> EXT P0=HIGH ok} — surfaced as an event for UI/session notes. */
    private static final Pattern SWITCH = Pattern.compile(
            "^\\[ANT] -> (INT|EXT) P0=(HIGH|LOW) ok");

    /** One parsed RSSI reading. */
    public record Reading(AntennaPath path, double rssiDbm) {
    }

    /** An observed RF-switch transition. */
    public record SwitchEvent(AntennaPath to) {
    }

    private final StringBuilder pending = new StringBuilder();
    private long linesSeen;
    private long linesIgnored;

    /**
     * Parse one complete line. Stateless and side-effect free — the chunked
     * entry point below and the tests both funnel through here.
     */
    public static Optional<Reading> parseLine(String line) {
        Matcher m = SAMPLE.matcher(line);
        if (!m.find()) {
            return Optional.empty();
        }
        AntennaPath path = "INT".equals(m.group(1)) ? AntennaPath.CHIP : AntennaPath.EXTERNAL;
        return Optional.of(new Reading(path, Double.parseDouble(m.group(2))));
    }

    /** As {@link #parseLine}, for switch transitions. */
    public static Optional<SwitchEvent> parseSwitch(String line) {
        Matcher m = SWITCH.matcher(line);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new SwitchEvent(
                "INT".equals(m.group(1)) ? AntennaPath.CHIP : AntennaPath.EXTERNAL));
    }

    /**
     * Feed a chunk of raw text as it arrived from the port and get back every
     * completed reading.
     *
     * <p>Serial reads do not respect line boundaries — a read can end mid-line,
     * and the next begins with the rest. This method holds the trailing partial
     * line until its terminator arrives, which is the difference between a
     * parser that works in tests and one that works on a real port.
     */
    public List<Reading> feed(CharSequence chunk) {
        List<Reading> out = new ArrayList<>();
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n' || c == '\r') {
                if (pending.length() > 0) {
                    String line = pending.toString();
                    pending.setLength(0);
                    linesSeen++;
                    Optional<Reading> reading = parseLine(line);
                    if (reading.isPresent()) {
                        out.add(reading.get());
                    } else {
                        linesIgnored++;
                    }
                }
                // Bare CR and CRLF both terminate; the empty-pending check above
                // makes the LF of a CRLF pair a no-op rather than an empty line.
            } else {
                pending.append(c);
            }
        }
        return out;
    }

    /** Lines fully received so far, samples and noise alike. */
    public long linesSeen() {
        return linesSeen;
    }

    /**
     * Lines that were not samples. Overwhelmingly normal — the firmware logs
     * far more chatter than data — but a ratio near 100% with zero samples is
     * the UI's cue that this port probably isn't the antenna firmware.
     */
    public long linesIgnored() {
        return linesIgnored;
    }
}
