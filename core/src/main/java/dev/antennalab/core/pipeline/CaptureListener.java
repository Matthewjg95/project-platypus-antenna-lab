package dev.antennalab.core.pipeline;

import dev.antennalab.core.domain.RssiSample;

import java.util.List;

/**
 * Callbacks from a running capture.
 *
 * <p><b>Threading.</b> Every method here is invoked on a pipeline-owned virtual
 * thread, never the JavaFX application thread. UI implementations must hop via
 * {@code Platform.runLater}. Keeping {@code core} free of any JavaFX import is
 * what forces that boundary to be explicit rather than accidental.
 */
public interface CaptureListener {

    /**
     * A new frame of the rolling window is ready to draw.
     *
     * <p>Called at display rate, not sample rate -- the publish stage coalesces
     * however many samples arrived since the last frame into one call, so a
     * 500 Hz capture still only repaints ~60 times a second.
     *
     * @param window oldest-first snapshot of the rolling window.
     */
    void onFrame(List<RssiSample> window);

    /** The capture ended on its own, e.g. a replay file ran out. */
    default void onCompleted() {
    }

    /**
     * The device disappeared; the capture is trying to get it back.
     *
     * <p>Not a failure yet. Samples already captured are retained, and
     * {@link #onFailed} still fires if the retries run out.
     */
    default void onConnectionLost(String reason, int attempt, int maxAttempts) {
    }

    /** Samples are flowing again, {@code attempts} tries after the loss. */
    default void onReconnected(int attempts) {
    }

    /** The capture stopped because something failed. */
    default void onFailed(Throwable cause) {
    }

    /** The capture was cancelled by the operator. */
    default void onCancelled() {
    }
}
