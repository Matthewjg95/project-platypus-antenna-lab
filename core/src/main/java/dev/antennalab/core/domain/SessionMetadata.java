package dev.antennalab.core.domain;

import java.time.Instant;

/**
 * The test conditions a run was recorded under.
 *
 * <p>An RSSI delta without its conditions is not a measurement, it is a number.
 * Distance, orientation and channel are the three things that most change the
 * answer, so they are first-class fields rather than free text buried in notes,
 * and the HTML report prints them next to the headline figure.
 *
 * @param title           short name for the run, e.g. "Platypus 7.13 @ 3m boresight".
 * @param distanceMeters  separation between the board and the far end of the link.
 * @param orientation     antenna aspect, e.g. "boresight", "45 deg", "edge-on".
 * @param wifiChannel     2.4 GHz channel in use, 1-14, or {@link #CHANNEL_UNKNOWN}.
 * @param deviceUnderTest what was on the MMCX port, e.g. "Platypus patch Rev 7.13".
 * @param notes           anything else worth recording about the run.
 * @param recordedAt      when the run was captured.
 */
public record SessionMetadata(
        String title,
        double distanceMeters,
        String orientation,
        int wifiChannel,
        String deviceUnderTest,
        String notes,
        Instant recordedAt) {

    /** Used when the channel was not noted; the report renders this as "not recorded". */
    public static final int CHANNEL_UNKNOWN = 0;

    public SessionMetadata {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (distanceMeters < 0 || Double.isNaN(distanceMeters) || Double.isInfinite(distanceMeters)) {
            throw new IllegalArgumentException(
                    "distanceMeters must be finite and >= 0, got " + distanceMeters);
        }
        if (wifiChannel != CHANNEL_UNKNOWN && (wifiChannel < 1 || wifiChannel > 14)) {
            throw new IllegalArgumentException(
                    "wifiChannel must be 1-14 or CHANNEL_UNKNOWN, got " + wifiChannel);
        }
        // Free-text fields are normalised to empty rather than rejected: a run you
        // forgot to annotate is still a run worth keeping.
        orientation = orientation == null ? "" : orientation.strip();
        deviceUnderTest = deviceUnderTest == null ? "" : deviceUnderTest.strip();
        notes = notes == null ? "" : notes.strip();
        if (recordedAt == null) {
            throw new IllegalArgumentException("recordedAt is required");
        }
    }

    /** Minimal metadata for a run captured before the operator filled anything in. */
    public static SessionMetadata untitled(Instant recordedAt) {
        return new SessionMetadata(
                "Untitled run", 0.0, "", CHANNEL_UNKNOWN, "", "", recordedAt);
    }

    /** True when the channel field carries a real value. */
    public boolean hasChannel() {
        return wifiChannel != CHANNEL_UNKNOWN;
    }
}
