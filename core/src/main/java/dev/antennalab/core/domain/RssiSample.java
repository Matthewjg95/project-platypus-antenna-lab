package dev.antennalab.core.domain;

import java.time.Instant;

/**
 * A single RSSI reading from the board.
 *
 * <p>This is the atom of the whole application: the serial parser produces these,
 * the ring buffer holds these, the chart draws these, the statistics consume
 * these, and the report tabulates these.
 *
 * <p><b>Why a record.</b> Samples arrive at a few hundred per second and get
 * passed across threads between the reader, the ring buffer and the JavaFX
 * chart updater. A record gives us shallow immutability for free, which means no
 * defensive copying and no synchronisation on the sample itself -- the only
 * thing that needs guarding is the buffer, not its contents. The generated
 * {@code equals}/{@code hashCode} also make the parser tests read as plain data
 * comparisons against the captured fixtures.
 *
 * <p><b>Schema stability.</b> Field set is deliberately minimal until real
 * captures land. Anything the firmware reports that turns out to be per-sample
 * rather than per-session gets added here; anything constant for a run belongs
 * in {@link SessionMetadata}.
 *
 * @param sequence   monotonic index within its capture run, starting at 0. Used
 *                   for sample-index alignment in the A/B view and to detect
 *                   drops without relying on wall-clock time.
 * @param timestamp  when the sample was observed.
 * @param antenna    which RF path was live.
 * @param rssiDbm    received signal strength in dBm. Conventionally negative;
 *                   around -30 dBm is very strong, -90 dBm is near the floor.
 */
public record RssiSample(long sequence, Instant timestamp, AntennaPath antenna, double rssiDbm) {

    /**
     * Loosest plausible RSSI bounds. Deliberately wide -- this is a sanity check
     * to catch a mis-parsed column, not a calibration limit. A real ESP32 will
     * not report +20 dBm, and a parser that hands us one has read the wrong field.
     */
    public static final double MIN_PLAUSIBLE_DBM = -120.0;

    /** @see #MIN_PLAUSIBLE_DBM */
    public static final double MAX_PLAUSIBLE_DBM = 0.0;

    public RssiSample {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0, got " + sequence);
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        if (antenna == null) {
            throw new IllegalArgumentException("antenna is required");
        }
        if (Double.isNaN(rssiDbm) || Double.isInfinite(rssiDbm)) {
            throw new IllegalArgumentException("rssiDbm must be finite, got " + rssiDbm);
        }
    }

    /**
     * Whether this reading sits inside the physically plausible window.
     *
     * <p>Intentionally a query rather than a constructor check: a capture that
     * contains an implausible value is still a capture worth keeping and showing,
     * and silently dropping samples would quietly bias the statistics. The UI
     * flags these; it does not discard them.
     */
    public boolean isPlausible() {
        return rssiDbm >= MIN_PLAUSIBLE_DBM && rssiDbm <= MAX_PLAUSIBLE_DBM;
    }
}
