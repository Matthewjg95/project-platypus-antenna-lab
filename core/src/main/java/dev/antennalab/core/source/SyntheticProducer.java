package dev.antennalab.core.source;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SyntheticSource;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Generates a believable RSSI stream with no hardware attached.
 *
 * <p>The model has three layers, because a single Gaussian around a mean looks
 * obviously fake on a scope and would make the statistics view meaningless:
 *
 * <ol>
 *   <li><b>Common-mode drift</b> -- a mean-reverting random walk shared by both
 *       antenna paths. Real RSSI wanders together as the multipath environment
 *       changes (someone walks past, the door opens), and it is exactly this
 *       correlation that makes a paired A/B measurement worth more than two
 *       separate runs.</li>
 *   <li><b>A slow sweep</b> -- a long-period sinusoid standing in for an operator
 *       slowly rotating the antenna. Gives the demo video something to look at.</li>
 *   <li><b>Per-sample noise</b> -- independent Gaussian per reading.</li>
 * </ol>
 *
 * <p>The external path sits {@link SyntheticSource#externalGainDb()} above the
 * chip path, seeded from the real bench figure, so the delta-dB card reads
 * roughly what the hardware produces. It is still modelled data, and the
 * {@code Source} it carries says so.
 */
public final class SyntheticProducer implements SampleProducer {

    /** How strongly the drift pulls back toward zero each step; 0.98 => slow wander. */
    private static final double DRIFT_RETENTION = 0.98;

    /** Step size of the drift random walk, in dB. */
    private static final double DRIFT_STEP_DB = 0.45;

    /** Amplitude of the slow sweep, in dB. */
    private static final double SWEEP_AMPLITUDE_DB = 1.8;

    /** Period of the slow sweep, in seconds. */
    private static final double SWEEP_PERIOD_SECONDS = 17.0;

    private final SyntheticSource spec;
    private final Random rng;

    private volatile boolean closed;

    public SyntheticProducer(SyntheticSource spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        this.spec = spec;
        // Seeded so a synthetic run is reproducible: the stats tests assert on
        // exact numbers, which only works if the dice are the same every time.
        this.rng = new Random(spec.seed());
    }

    @Override
    public void produce(SampleSink sink) throws InterruptedException {
        long periodNanos = 1_000_000_000L / spec.samplesPerSecond();
        long nextEmit = System.nanoTime();
        long startNanos = nextEmit;
        long sequence = 0;
        double drift = 0.0;

        while (!closed && !Thread.currentThread().isInterrupted()) {
            drift = drift * DRIFT_RETENTION + rng.nextGaussian() * DRIFT_STEP_DB;

            double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            double sweep = SWEEP_AMPLITUDE_DB
                    * Math.sin(2 * Math.PI * elapsedSeconds / SWEEP_PERIOD_SECONDS);

            // The board's RF switch alternates, so emit a matched pair each tick.
            // Both readings share the same drift and sweep -- only the noise and
            // the antenna gain differ, which is what a real paired capture looks like.
            Instant now = Instant.now();
            for (AntennaPath path : AntennaPath.values()) {
                double base = switch (path) {
                    case CHIP -> spec.chipMeanDbm();
                    case EXTERNAL -> spec.externalMeanDbm();
                };
                double value = base + drift + sweep + rng.nextGaussian() * spec.noiseStdDevDb();
                sink.accept(new RssiSample(sequence++, now, path, round1(value)));
            }

            // Absolute-deadline pacing rather than sleep(period): sleeping a fixed
            // amount each iteration accumulates the loop's own cost as drift, and
            // over a few minutes the "20 Hz" capture is visibly slower than 20 Hz.
            nextEmit += periodNanos;
            long waitNanos = nextEmit - System.nanoTime();
            if (waitNanos > 0) {
                // parkNanos rather than Thread.sleep: on a virtual thread this
                // unmounts the carrier just the same, and it will not swallow the
                // interrupt flag we rely on for cancellation.
                LockSupport.parkNanos(waitNanos);
                if (Thread.interrupted()) {
                    throw new InterruptedException("synthetic capture cancelled");
                }
            } else {
                // We fell behind (debugger, GC pause). Resync instead of trying to
                // catch up with a burst that would distort the timestamps.
                nextEmit = System.nanoTime();
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("synthetic capture cancelled");
        }
    }

    /** RSSI is reported to a tenth of a dB; keep synthetic data equally granular. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    @Override
    public String describe() {
        return "Synthetic RSSI, %d Hz/path, %+.1f dB modelled gain"
                .formatted(spec.samplesPerSecond(), spec.externalGainDb());
    }

    @Override
    public void close() {
        closed = true;
    }
}
