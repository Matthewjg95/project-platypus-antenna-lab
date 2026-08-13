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
    @DisplayName("a statistically solid but sub-2 dB difference is graded BELOW_RESOLUTION")
    void smallDifferenceIsBelowInstrumentResolution() {
        // 1.2 dB apart, 3000 samples each, tight spread. The statistics are
        // emphatic -- the 95% interval is nowhere near zero -- but the ESP32
        // reports RSSI in whole dBm and the project's procedure says under 2 dBm
        // is noise. This is the exact case where significance and meaning diverge,
        // and the software must not launder one into the other.
        TraceStats chip = TraceStats.ofValues(series(51, -62.0, 2.0, 3000));
        TraceStats external = TraceStats.ofValues(series(52, -60.8, 2.0, 3000));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertTrue(d.isSignificant(), "statistics alone should call this separable");
        assertFalse(d.isAboveInstrumentResolution());
        assertEquals(AntennaDelta.Confidence.BELOW_RESOLUTION, d.confidence());
        assertTrue(d.qualification().contains("Below instrument resolution"));
    }

    @Test
    @DisplayName("a large separation is comfortably above the instrument floor")
    void headlineMagnitudeIsResolvable() {
        TraceStats chip = TraceStats.ofValues(series(61, -40.5, 2.2, 400));
        TraceStats external = TraceStats.ofValues(series(62, -28.0, 2.2, 400));

        AntennaDelta d = AntennaDelta.of(chip, external);

        assertTrue(d.isAboveInstrumentResolution());
        assertEquals(AntennaDelta.Confidence.STRONG, d.confidence());
        assertEquals(12.5, d.deltaDb(), 0.6);
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
