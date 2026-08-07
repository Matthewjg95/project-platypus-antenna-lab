package dev.antennalab.core;

import dev.antennalab.core.stats.AntennaDelta;
import dev.antennalab.core.stats.TraceStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headline delta and, more importantly, its confidence grading.
 *
 * <p>The grading rules are the guard against the project's most embarrassing
 * possible failure mode: printing a large, confident-looking dB figure that is
 * actually indistinguishable from noise.
 */
class AntennaDeltaTest {

    /** Tight, well-sampled traces separated by a clear margin. */
    private static double[] series(long seed, double mean, double sd, int n) {
        Random rng = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + rng.nextGaussian() * sd;
        }
        return out;
    }

    @Test
    @DisplayName("delta is external mean minus chip mean, so an improvement is positive")
    void deltaSign() {
        TraceStats chip = TraceStats.ofValues(new double[] {-62, -62, -62, -62});
        TraceStats external = TraceStats.ofValues(new double[] {-50, -50, -50, -50});

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(12.0, d.deltaDb(), 1e-9);
        assertTrue(d.headline().startsWith("+"), "an improvement should read as +N dB");
    }

    @Test
    @DisplayName("a large, well-sampled separation grades STRONG")
    void wellSeparatedTracesAreStrong() {
        TraceStats chip = TraceStats.ofValues(series(1, -62.0, 2.2, 400));
        TraceStats external = TraceStats.ofValues(series(2, -49.5, 2.2, 400));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(AntennaDelta.Confidence.STRONG, d.confidence());
        assertTrue(d.isSignificant());
        assertEquals(12.5, d.deltaDb(), 0.6);
    }

    @Test
    @DisplayName("two traces from the same distribution are not distinguishable")
    void identicalDistributionsGradeWeak() {
        TraceStats chip = TraceStats.ofValues(series(11, -62.0, 2.2, 400));
        TraceStats external = TraceStats.ofValues(series(12, -62.0, 2.2, 400));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(AntennaDelta.Confidence.WEAK, d.confidence());
        assertFalse(d.isSignificant());
        assertTrue(d.qualification().contains("Not distinguishable"));
    }

    @Test
    @DisplayName("too few samples grades INSUFFICIENT no matter how big the gap looks")
    void tinySamplesAreInsufficient() {
        TraceStats chip = TraceStats.ofValues(new double[] {-62, -61, -63});
        TraceStats external = TraceStats.ofValues(new double[] {-40, -41, -39});

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(AntennaDelta.Confidence.INSUFFICIENT, d.confidence());
        assertTrue(d.qualification().contains("Insufficient"));
    }

    @Test
    @DisplayName("badly unbalanced sample counts are flagged even when both are large")
    void imbalancedCountsAreInsufficient() {
        // 500 vs 50 is a 10:1 imbalance -- past the 3:1 limit. Both traces are
        // individually big enough, so only the imbalance rule catches this.
        TraceStats chip = TraceStats.ofValues(series(21, -62.0, 2.0, 500));
        TraceStats external = TraceStats.ofValues(series(22, -49.5, 2.0, 50));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(AntennaDelta.Confidence.INSUFFICIENT, d.confidence());
    }

    @Test
    @DisplayName("mildly unbalanced counts are still gradeable")
    void mildImbalanceIsAcceptable() {
        TraceStats chip = TraceStats.ofValues(series(31, -62.0, 2.0, 300));
        TraceStats external = TraceStats.ofValues(series(32, -49.5, 2.0, 150));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertEquals(AntennaDelta.Confidence.STRONG, d.confidence());
    }

    @Test
    @DisplayName("the confidence interval brackets the delta and is reported in the qualification")
    void confidenceIntervalIsReported() {
        TraceStats chip = TraceStats.ofValues(series(41, -62.0, 2.2, 200));
        TraceStats external = TraceStats.ofValues(series(42, -49.5, 2.2, 200));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertTrue(d.lowerBoundDb() < d.deltaDb());
        assertTrue(d.upperBoundDb() > d.deltaDb());
        assertTrue(d.qualification().contains("95% CI"),
                "qualification should state the interval, was: " + d.qualification());
        assertTrue(d.qualification().contains("n=200/200"));
    }
}
