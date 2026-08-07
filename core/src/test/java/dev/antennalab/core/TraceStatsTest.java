package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.stats.TraceStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Statistics maths, checked against hand-computed values.
 *
 * <p>These are deliberately arithmetic rather than property-based: the headline
 * of the whole project is a number produced by this class, so it is worth
 * knowing the exact answer for a case small enough to verify on paper.
 */
class TraceStatsTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("mean, median, min and max over a known series")
    void basicMoments() {
        TraceStats s = TraceStats.ofValues(new double[] {1, 2, 3, 4, 5});

        assertEquals(5, s.count());
        assertEquals(3.0, s.meanDbm(), EPSILON);
        assertEquals(3.0, s.medianDbm(), EPSILON);
        assertEquals(1.0, s.minDbm(), EPSILON);
        assertEquals(5.0, s.maxDbm(), EPSILON);
        assertEquals(4.0, s.rangeDb(), EPSILON);
    }

    @Test
    @DisplayName("standard deviation uses Bessel's correction (n-1), not n")
    void sampleStandardDeviation() {
        // Deviations from mean 3 are -2,-1,0,1,2 -> sum of squares 10.
        // Sample variance = 10 / (5-1) = 2.5, so sd = sqrt(2.5).
        // The population form would give sqrt(2.0) = 1.4142, which is the bug
        // this test exists to catch.
        TraceStats s = TraceStats.ofValues(new double[] {1, 2, 3, 4, 5});

        assertEquals(Math.sqrt(2.5), s.stdDevDb(), EPSILON);
        assertEquals(Math.sqrt(2.5) / Math.sqrt(5), s.stdErrorDb(), EPSILON);
    }

    @Test
    @DisplayName("p95 interpolates between ranks rather than snapping to one")
    void percentileInterpolates() {
        // rank = 0.95 * (5-1) = 3.8 -> 0.2 * sorted[3] + 0.8 * sorted[4]
        //      = 0.2*4 + 0.8*5 = 4.8
        TraceStats s = TraceStats.ofValues(new double[] {1, 2, 3, 4, 5});

        assertEquals(4.8, s.p95Dbm(), EPSILON);
    }

    @Test
    @DisplayName("median of an even-length series is the midpoint of the two centre values")
    void evenLengthMedian() {
        TraceStats s = TraceStats.ofValues(new double[] {10, 20, 30, 40});

        assertEquals(25.0, s.medianDbm(), EPSILON);
    }

    @Test
    @DisplayName("unsorted input is handled -- percentiles sort internally")
    void inputNeedNotBeSorted() {
        TraceStats sorted = TraceStats.ofValues(new double[] {1, 2, 3, 4, 5});
        TraceStats shuffled = TraceStats.ofValues(new double[] {4, 1, 5, 3, 2});

        assertEquals(sorted.medianDbm(), shuffled.medianDbm(), EPSILON);
        assertEquals(sorted.p95Dbm(), shuffled.p95Dbm(), EPSILON);
        assertEquals(sorted.meanDbm(), shuffled.meanDbm(), EPSILON);
    }

    @Test
    @DisplayName("a single sample has zero spread, not NaN")
    void singleSampleHasNoSpread() {
        TraceStats s = TraceStats.ofValues(new double[] {-61.5});

        assertEquals(1, s.count());
        assertEquals(-61.5, s.meanDbm(), EPSILON);
        assertEquals(-61.5, s.medianDbm(), EPSILON);
        assertEquals(-61.5, s.p95Dbm(), EPSILON);
        // n-1 would divide by zero; the implementation must special-case this.
        assertEquals(0.0, s.stdDevDb(), EPSILON);
    }

    @Test
    @DisplayName("precision holds for realistic RSSI values far from zero")
    void precisionAtRealisticOffsets() {
        // The naive one-pass variance formula loses most of its significant
        // digits here, because the mean is large relative to the spread.
        double[] values = {-62.1, -61.9, -62.0, -62.2, -61.8};
        TraceStats s = TraceStats.ofValues(values);

        assertEquals(-62.0, s.meanDbm(), 1e-12);
        assertEquals(Math.sqrt(0.1 / 4.0), s.stdDevDb(), 1e-12);
    }

    @Test
    @DisplayName("an empty trace throws rather than reporting zeros")
    void emptyTraceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TraceStats.ofValues(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> TraceStats.of(List.of()));
    }

    @Test
    @DisplayName("computing from samples reads the rssiDbm field")
    void computesFromSamples() {
        Instant t = Instant.parse("2026-08-06T12:00:00Z");
        List<RssiSample> samples = List.of(
                new RssiSample(0, t, AntennaPath.CHIP, -60.0),
                new RssiSample(1, t, AntennaPath.CHIP, -62.0),
                new RssiSample(2, t, AntennaPath.CHIP, -64.0));

        TraceStats s = TraceStats.of(samples);

        assertEquals(3, s.count());
        assertEquals(-62.0, s.meanDbm(), EPSILON);
    }
}
