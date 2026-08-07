package dev.antennalab.core.domain;

import java.nio.file.Path;

/**
 * Plays a previously captured CSV back through the same pipeline as live hardware.
 *
 * <p>Replay is not a debugging convenience bolted on at the end -- it is how the
 * app stays demonstrable with no board attached, and how a capture recorded in
 * the field gets re-examined at the desk. Because it feeds the identical
 * pipeline, anything that renders live also renders on replay.
 *
 * @param csvFile         the capture to read.
 * @param speedMultiplier wall-clock playback rate. {@code 1.0} honours the
 *                        original inter-sample timing; higher is faster;
 *                        {@link #AS_FAST_AS_POSSIBLE} skips pacing entirely,
 *                        which is what the import path and tests use.
 */
public record ReplaySource(Path csvFile, double speedMultiplier) implements Source {

    /** Sentinel speed meaning "do not pace, emit as fast as the reader can go". */
    public static final double AS_FAST_AS_POSSIBLE = 0.0;

    public ReplaySource {
        if (csvFile == null) {
            throw new IllegalArgumentException("csvFile is required");
        }
        if (speedMultiplier < 0 || Double.isNaN(speedMultiplier) || Double.isInfinite(speedMultiplier)) {
            throw new IllegalArgumentException(
                    "speedMultiplier must be finite and >= 0, got " + speedMultiplier);
        }
    }

    /** Replay honouring the original recording's timing. */
    public static ReplaySource realTime(Path csvFile) {
        return new ReplaySource(csvFile, 1.0);
    }

    /** Read the whole file with no pacing -- used by CSV import and by tests. */
    public static ReplaySource unpaced(Path csvFile) {
        return new ReplaySource(csvFile, AS_FAST_AS_POSSIBLE);
    }

    /** True when playback should ignore the recorded inter-sample delays. */
    public boolean isUnpaced() {
        return speedMultiplier == AS_FAST_AS_POSSIBLE;
    }

    @Override
    public String displayName() {
        return csvFile.getFileName().toString();
    }

    @Override
    public boolean isLiveHardware() {
        return false;
    }
}
