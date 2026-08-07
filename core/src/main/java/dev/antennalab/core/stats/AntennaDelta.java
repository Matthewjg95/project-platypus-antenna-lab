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
        /** Too few samples, or the two traces are too unevenly sampled, to say. */
        INSUFFICIENT
    }

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
            return delta == 0 ? Confidence.WEAK : Confidence.MODERATE;
        }
        double ratio = Math.abs(delta) / margin;
        if (ratio >= 2.0) {
            return Confidence.STRONG;
        }
        if (ratio >= 1.0) {
            return Confidence.MODERATE;
        }
        return Confidence.WEAK;
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
            case INSUFFICIENT -> "Insufficient or unbalanced data (n=%d/%d); capture more before quoting this"
                    .formatted(chip.count(), external.count());
        };
    }
}
