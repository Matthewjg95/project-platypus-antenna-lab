package dev.antennalab.core.domain;

/**
 * A modelled RSSI stream with no hardware attached.
 *
 * <p>This exists so the application is fully demonstrable -- scope, statistics,
 * A/B view, report export -- with nothing plugged in. That de-risks the demo
 * video and lets UI work proceed while the board is elsewhere.
 *
 * <p><b>Honesty rule.</b> Synthetic output is modelled, not measured. Every
 * artefact generated from it is watermarked as such, and
 * {@link #isLiveHardware()} returns {@code false} so the report generator cannot
 * accidentally present a simulation as evidence. The default gain is seeded from
 * the real bench result for the Platypus patch purely so the demo looks like the
 * hardware it stands in for.
 *
 * @param seed             RNG seed; a fixed seed makes runs reproducible, which
 *                         is what lets the statistics tests assert exact numbers.
 * @param chipMeanDbm      mean RSSI of the chip-antenna path.
 * @param externalGainDb   how much better the external path is, in dB. Defaults
 *                         to the measured Platypus Rev 7.13 figure.
 * @param noiseStdDevDb    per-sample Gaussian spread; real RSSI is noisy and a
 *                         clean sine wave would make the statistics view a lie.
 * @param samplesPerSecond emission rate per antenna path.
 */
public record SyntheticSource(
        long seed,
        double chipMeanDbm,
        double externalGainDb,
        double noiseStdDevDb,
        int samplesPerSecond) implements Source {

    /**
     * Measured advantage of the Project Platypus patch (Rev 7.13) over the
     * ESP32-C6-MINI-1U chip antenna, from the real bench run.
     */
    public static final double PLATYPUS_MEASURED_GAIN_DB = 12.5;

    /** A typical mid-room chip-antenna reading at a couple of metres. */
    public static final double DEFAULT_CHIP_MEAN_DBM = -62.0;

    /** RSSI wanders by a few dB even when nothing moves. */
    public static final double DEFAULT_NOISE_STDDEV_DB = 2.2;

    /** Fast enough to look live, slow enough not to swamp the chart. */
    public static final int DEFAULT_SAMPLES_PER_SECOND = 20;

    public SyntheticSource {
        if (noiseStdDevDb < 0 || Double.isNaN(noiseStdDevDb) || Double.isInfinite(noiseStdDevDb)) {
            throw new IllegalArgumentException(
                    "noiseStdDevDb must be finite and >= 0, got " + noiseStdDevDb);
        }
        if (Double.isNaN(chipMeanDbm) || Double.isInfinite(chipMeanDbm)) {
            throw new IllegalArgumentException("chipMeanDbm must be finite, got " + chipMeanDbm);
        }
        if (Double.isNaN(externalGainDb) || Double.isInfinite(externalGainDb)) {
            throw new IllegalArgumentException("externalGainDb must be finite, got " + externalGainDb);
        }
        if (samplesPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "samplesPerSecond must be positive, got " + samplesPerSecond);
        }
    }

    /** Demo defaults: models the real Platypus-vs-chip result at 20 Hz. */
    public static SyntheticSource demo() {
        return new SyntheticSource(
                42L,
                DEFAULT_CHIP_MEAN_DBM,
                PLATYPUS_MEASURED_GAIN_DB,
                DEFAULT_NOISE_STDDEV_DB,
                DEFAULT_SAMPLES_PER_SECOND);
    }

    /** Same model, different dice -- for a demo that does not repeat itself. */
    public static SyntheticSource demoWithSeed(long seed) {
        return new SyntheticSource(
                seed,
                DEFAULT_CHIP_MEAN_DBM,
                PLATYPUS_MEASURED_GAIN_DB,
                DEFAULT_NOISE_STDDEV_DB,
                DEFAULT_SAMPLES_PER_SECOND);
    }

    /** Mean RSSI of the external path implied by the chip mean plus the gain. */
    public double externalMeanDbm() {
        return chipMeanDbm + externalGainDb;
    }

    @Override
    public String displayName() {
        return "Synthetic";
    }

    @Override
    public boolean isLiveHardware() {
        return false;
    }
}
