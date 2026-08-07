package dev.antennalab.core.stats;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.Session;

/**
 * The headline result: how much better the external antenna is than the chip
 * antenna, and how much that number should be trusted.
 *
 * <p>The delta alone is not a result. Two traces of 40 samples each with 3 dB of
 * spread can show a 2 dB "difference" that is pure noise, and a report that
 * prints such a figure in large type without qualification is misleading. So the
 * confidence interval and the {@link Confidence} grade travel with the number
 * everywhere it goes -- the UI card, the report, the A/B view.
 *
 * @param chip            statistics for the chip-antenna trace.
 * @param external        statistics for the external-antenna trace.
 * @param deltaDb         {@code external.mean - chip.mean}. Positive means the
 *                        external antenna is receiving more strongly.
 * @param marginOfErrorDb half-width of the 95% confidence interval on the delta.
 * @param confidence      qualitative grade; see {@link Confidence}.
 */
public record AntennaDelta(
        TraceStats chip,
        TraceStats external,
        double deltaDb,
        double marginOfErrorDb,
        Confidence confidence) {

    /** How much weight the delta figure can carry. */
    public enum Confidence {
        /** Difference comfortably exceeds its own error bars. */
        STRONG,
        /** Difference exceeds its error bars, but not by much. */
        MODERATE,
        /** Error bars overlap zero: the traces are not distinguishable. */
        WEAK,
        /**
         * Statistically separable, but smaller than the instrument can honestly
         * resolve.
         *
         * <p>This grade exists because statistical significance and physical
         * meaning are different things, and enough samples will make any bias
         * "significant". The ESP32 reports RSSI quantised to 1 dBm, and Project
         * Platypus's own test procedure states that differences under 2 dBm are
         * within measurement noise. A 1.5 dB delta over ten thousand samples has
         * tight error bars and still tells you nothing about the antenna.
         */
        BELOW_RESOLUTION,
        /** Too few samples, or the two traces are too unevenly sampled, to say. */
        INSUFFICIENT
    }

    /**
     * RSSI quantisation step of the ESP32 WiFi stack, in dB.
     *
     * <p>Readings arrive as whole dBm, so no amount of averaging turns this into
     * a continuous measurement -- it bounds what the instrument can see.
     */
    public static final double INSTRUMENT_RESOLUTION_DB = 1.0;

    /**
     * Smallest difference this rig will call real, in dB.
     *
     * <p>From TEST_PROCEDURE.md: "Differences &lt; 2 dBm are within measurement
     * noise and should not be interpreted as real gain changes."
     */
    public static final double MEANINGFUL_DELTA_FLOOR_DB = 2.0;

    /** Below this many samples on either path, no delta is worth grading. */
    public static final int MIN_SAMPLES_PER_PATH = 30;

    /**
     * Largest tolerable ratio between the two sample counts.
     *
     * <p>Unequal counts are not fatal -- the standard error already accounts for
     * them -- but a 10:1 imbalance usually means the two traces were not captured
     * under comparable conditions, which the error bars cannot see. This is the
     * "confidence noted when sample counts differ" rule.
     */
    public static final double MAX_COUNT_IMBALANCE = 3.0;

    /** z for a two-sided 95% interval on a roughly normal mean. */
    private static final double Z_95 = 1.96;

    /**
     * Compute the delta between the two antenna paths of one session.
     *
     * @throws IllegalArgumentException if either path has no samples at all.
     */
    public static AntennaDelta of(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        return of(
                TraceStats.of(session.samplesFor(AntennaPath.CHIP)),
                TraceStats.of(session.samplesFor(AntennaPath.EXTERNAL)));
    }

    /** Compute the delta from two already-summarised traces. */
    public static AntennaDelta of(TraceStats chip, TraceStats external) {
        if (chip == null || external == null) {
            throw new IllegalArgumentException("both traces are required");
        }
        double delta = external.meanDbm() - chip.meanDbm();

        // Standard error of a difference of two independent means is the root of
        // the sum of their squared standard errors. Unequal sample counts fall
        // out of this naturally -- the smaller trace simply contributes more
        // error -- which is why we can report a delta at all when they differ.
        double seDiff = Math.hypot(chip.stdErrorDb(), external.stdErrorDb());
        double margin = Z_95 * seDiff;

        return new AntennaDelta(chip, external, delta, margin, grade(chip, external, delta, margin));
    }

    private static Confidence grade(TraceStats chip, TraceStats external, double delta, double margin) {
        if (chip.count() < MIN_SAMPLES_PER_PATH || external.count() < MIN_SAMPLES_PER_PATH) {
            return Confidence.INSUFFICIENT;
        }
        double imbalance = (double) Math.max(chip.count(), external.count())
                / Math.min(chip.count(), external.count());
        if (imbalance > MAX_COUNT_IMBALANCE) {
            return Confidence.INSUFFICIENT;
        }
        if (margin <= 0) {
            // Zero spread on both traces. Physically implausible for real RSSI;
            // usually means a stuck reading or a mis-parsed constant column.
            return Math.abs(delta) < MEANINGFUL_DELTA_FLOOR_DB
                    ? Confidence.BELOW_RESOLUTION
                    : Confidence.MODERATE;
        }
        double ratio = Math.abs(delta) / margin;

        // Statistics first: if the error bars already overlap zero, "not
        // distinguishable" is the more useful thing to say, and the physical floor
        // adds nothing.
        if (ratio < 1.0) {
            return Confidence.WEAK;
        }

        // The statistics claim a real difference. Now ask whether the hardware
        // could have seen it. Averaging drives the error bars down without limit,
        // so a large enough sample makes any small bias look decisive -- but the
        // ESP32 quantises RSSI to 1 dBm and the project's own procedure says
        // sub-2 dB differences are noise. Reporting STRONG here would be the
        // software lending false authority to a number the instrument cannot
        // support. This check exists precisely for the case where statistical
        // significance and physical meaning disagree.
        if (Math.abs(delta) < MEANINGFUL_DELTA_FLOOR_DB) {
            return Confidence.BELOW_RESOLUTION;
        }

        return ratio >= 2.0 ? Confidence.STRONG : Confidence.MODERATE;
    }

    /**
     * Whether the difference is big enough for this instrument to mean anything.
     *
     * <p>Separate from {@link #isSignificant()} on purpose: that asks whether the
     * statistics can distinguish the traces, this asks whether the hardware can.
     * A result needs both.
     */
    public boolean isAboveInstrumentResolution() {
        return Math.abs(deltaDb) >= MEANINGFUL_DELTA_FLOOR_DB;
    }

    /** True when the 95% interval excludes zero, i.e. the difference is real. */
    public boolean isSignificant() {
        return Math.abs(deltaDb) > marginOfErrorDb;
    }

    /** Lower bound of the 95% confidence interval on the delta. */
    public double lowerBoundDb() {
        return deltaDb - marginOfErrorDb;
    }

    /** Upper bound of the 95% confidence interval on the delta. */
    public double upperBoundDb() {
        return deltaDb + marginOfErrorDb;
    }

    /** The headline as it appears on the card, e.g. {@code "+12.5 dB"}. */
    public String headline() {
        return "%+.1f dB".formatted(deltaDb);
    }

    /**
     * One-line qualification printed under the headline, built by pattern
     * matching on the grade.
     */
    public String qualification() {
        return switch (confidence) {
            case STRONG -> "95%% CI %+.1f to %+.1f dB (n=%d/%d)"
                    .formatted(lowerBoundDb(), upperBoundDb(), chip.count(), external.count());
            case MODERATE -> "Marginal: 95%% CI %+.1f to %+.1f dB (n=%d/%d)"
                    .formatted(lowerBoundDb(), upperBoundDb(), chip.count(), external.count());
            case WEAK -> "Not distinguishable from zero: 95%% CI %+.1f to %+.1f dB (n=%d/%d)"
                    .formatted(lowerBoundDb(), upperBoundDb(), chip.count(), external.count());
            case BELOW_RESOLUTION -> ("Below instrument resolution: %.1f dB is inside the %.0f dB "
                    + "noise floor of a 1 dBm RSSI reading, whatever the sample count says (n=%d/%d)")
                    .formatted(Math.abs(deltaDb), MEANINGFUL_DELTA_FLOOR_DB,
                            chip.count(), external.count());
            case INSUFFICIENT -> "Insufficient or unbalanced data (n=%d/%d); capture more before quoting this"
                    .formatted(chip.count(), external.count());
        };
    }
}
