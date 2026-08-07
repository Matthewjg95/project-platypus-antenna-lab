package dev.antennalab.core.stats;

import dev.antennalab.core.domain.RssiSample;

import java.util.List;

/**
 * Summary statistics for one antenna trace.
 *
 * <p>All values are in dBm except {@link #stdDevDb}, which is a spread and so is
 * in plain dB.
 *
 * @param count     number of samples the summary was computed from.
 * @param meanDbm   arithmetic mean. See the note on averaging in dB below.
 * @param medianDbm 50th percentile.
 * @param minDbm    weakest reading.
 * @param maxDbm    strongest reading.
 * @param p95Dbm    95th percentile, i.e. near the strong end of the distribution.
 * @param stdDevDb  sample standard deviation (Bessel-corrected, n-1).
 * @param stdErrorDb standard error of the mean, {@code stdDev / sqrt(n)}.
 */
public record TraceStats(
        int count,
        double meanDbm,
        double medianDbm,
        double minDbm,
        double maxDbm,
        double p95Dbm,
        double stdDevDb,
        double stdErrorDb) {

    /**
     * Compute statistics over the RSSI values of the supplied samples.
     *
     * <p><b>A note on averaging decibels.</b> dBm is logarithmic, so the
     * arithmetic mean of dBm values is not the same as converting each reading to
     * linear milliwatts, averaging, and converting back. The linear mean is
     * dominated by the strongest readings; the dB-domain mean is what bench
     * instruments and antenna datasheets report, and it is what "this antenna is
     * 12.5 dB better" conventionally means. We use the dB-domain mean, so the
     * headline figure is directly comparable to the way the hardware result was
     * originally quoted.
     *
     * @throws IllegalArgumentException if there are no samples -- an empty trace
     *         has no mean, and returning zeros would quietly poison a report.
     */
    public static TraceStats of(List<RssiSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("cannot compute statistics over zero samples");
        }
        double[] values = samples.stream().mapToDouble(RssiSample::rssiDbm).toArray();
        return ofValues(values);
    }

    /** As {@link #of(List)}, but over raw dBm values. Package-friendly for tests. */
    public static TraceStats ofValues(double[] rawValues) {
        if (rawValues == null || rawValues.length == 0) {
            throw new IllegalArgumentException("cannot compute statistics over zero samples");
        }
        int n = rawValues.length;

        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : rawValues) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / n;

        // Two-pass variance. The one-pass "sum of squares minus square of sum"
        // shortcut loses catastrophic precision when the mean is far from zero --
        // which is exactly our case, since RSSI sits around -60 with a spread of
        // about 2. Two passes over a few thousand doubles costs nothing.
        double sumSquaredDeviation = 0.0;
        for (double v : rawValues) {
            double d = v - mean;
            sumSquaredDeviation += d * d;
        }
        // Bessel's correction: these are samples of a process, not a whole
        // population, so divide by n-1. Undefined for a single sample.
        double variance = n > 1 ? sumSquaredDeviation / (n - 1) : 0.0;
        double stdDev = Math.sqrt(variance);
        double stdError = n > 0 ? stdDev / Math.sqrt(n) : 0.0;

        double[] sorted = rawValues.clone();
        java.util.Arrays.sort(sorted);

        return new TraceStats(
                n,
                mean,
                percentile(sorted, 50.0),
                min,
                max,
                percentile(sorted, 95.0),
                stdDev,
                stdError);
    }

    /**
     * Linear-interpolation percentile over an already-sorted array (the R-7 /
     * spreadsheet {@code PERCENTILE.INC} definition).
     *
     * <p>Interpolating rather than picking a nearest rank matters at our sample
     * counts: a 200-sample trace has no exact 95th-rank element, and rounding to
     * one makes p95 jump in visible steps between otherwise identical runs.
     */
    static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = (percentile / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = rank - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    /** Peak-to-peak spread, in dB. */
    public double rangeDb() {
        return maxDbm - minDbm;
    }
}
