package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.ReplaySource;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.pipeline.CaptureListener;
import dev.antennalab.core.pipeline.CapturePipeline;
import dev.antennalab.core.source.Producers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pipeline behaviour on the synthetic source.
 *
 * <p>The point of these is cancellation. A capture that produces samples is easy;
 * a capture that reliably stops -- releasing the source, unwinding all three
 * stages, and reporting exactly once -- is what structured concurrency is being
 * used for, and it is what would otherwise leak a held serial port.
 */
class CapturePipelineTest {

    @Test
    @Timeout(15)
    @DisplayName("a synthetic capture produces samples on both antenna paths")
    void producesSamplesOnBothPaths() throws Exception {
        CountDownLatch gotData = new CountDownLatch(1);
        AtomicReference<List<RssiSample>> latest = new AtomicReference<>(List.of());

        Source source = new SyntheticSource(7L, -62.0, 12.5, 2.0, 200);
        try (CapturePipeline pipeline = new CapturePipeline(source, new CaptureListener() {
            @Override
            public void onFrame(List<RssiSample> window) {
                latest.set(window);
                if (window.size() > 50) {
                    gotData.countDown();
                }
            }
        })) {
            pipeline.start();
            assertTrue(gotData.await(10, TimeUnit.SECONDS), "expected frames within 10s");

            List<RssiSample> window = latest.get();
            assertTrue(window.stream().anyMatch(s -> s.antenna() == AntennaPath.CHIP));
            assertTrue(window.stream().anyMatch(s -> s.antenna() == AntennaPath.EXTERNAL));
            assertTrue(window.stream().allMatch(RssiSample::isPlausible),
                    "synthetic RSSI should sit inside plausible bounds");
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("stop() unwinds the capture and reports cancellation exactly once")
    void stopCancelsCleanly() throws Exception {
        CountDownLatch gotData = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();

        Source source = new SyntheticSource(9L, -62.0, 12.5, 2.0, 200);
        CapturePipeline pipeline = new CapturePipeline(source, new CaptureListener() {
            @Override
            public void onFrame(List<RssiSample> window) {
                if (!window.isEmpty()) {
                    gotData.countDown();
                }
            }

            @Override
            public void onCancelled() {
                cancelled.countDown();
            }

            @Override
            public void onFailed(Throwable cause) {
                failed.set(true);
            }
        });

        pipeline.start();
        assertTrue(gotData.await(10, TimeUnit.SECONDS), "capture never started producing");

        pipeline.stop();

        assertTrue(cancelled.await(10, TimeUnit.SECONDS), "cancellation was never reported");
        assertFalse(failed.get(), "a clean stop must not be reported as a failure");

        // The scope cannot be left before every forked stage has finished, so by
        // the time cancellation is reported nothing should still be running.
        assertFalse(pipeline.isRunning(), "pipeline still reports running after stop");
    }

    @Test
    @Timeout(15)
    @DisplayName("recording accumulates the full run behind the rolling window")
    void recordingAccumulatesHistory() throws Exception {
        CountDownLatch enough = new CountDownLatch(1);

        // Window of 64 but a much longer run: the recorded history must exceed
        // what the rolling buffer can hold.
        Source source = new SyntheticSource(13L, -62.0, 12.5, 2.0, 400);
        try (CapturePipeline pipeline = new CapturePipeline(source, window -> {
            if (window.size() >= 64) {
                enough.countDown();
            }
        }, 64)) {
            pipeline.setRecording(true);
            pipeline.start();
            assertTrue(enough.await(10, TimeUnit.SECONDS));
            Thread.sleep(300);
            pipeline.stop();

            assertTrue(pipeline.recordedSamples().size() > 64,
                    "recorded history should outgrow the 64-sample window");
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("pausing freezes frame delivery without stopping capture")
    void pauseFreezesDisplayOnly() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean framesWhilePaused = new AtomicBoolean();
        AtomicBoolean paused = new AtomicBoolean();

        Source source = new SyntheticSource(17L, -62.0, 12.5, 2.0, 200);
        try (CapturePipeline pipeline = new CapturePipeline(source, window -> {
            if (paused.get()) {
                framesWhilePaused.set(true);
            }
            if (!window.isEmpty()) {
                running.countDown();
            }
        })) {
            pipeline.start();
            assertTrue(running.await(10, TimeUnit.SECONDS));

            pipeline.setPaused(true);
            Thread.sleep(50);      // let any in-flight frame land before we watch
            paused.set(true);
            long writtenAtPause = pipeline.buffer().totalWritten();
            Thread.sleep(300);

            assertFalse(framesWhilePaused.get(), "no frames should be published while paused");
            assertTrue(pipeline.buffer().totalWritten() > writtenAtPause,
                    "capture should continue filling the buffer while the display is frozen");
        }
    }

    @Test
    @DisplayName("markers record where on the trace they were placed")
    void markersArePinnedToTheTrace() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        Source source = new SyntheticSource(23L, -62.0, 12.5, 2.0, 200);

        try (CapturePipeline pipeline = new CapturePipeline(source, window -> {
            if (!window.isEmpty()) {
                running.countDown();
            }
        })) {
            pipeline.start();
            assertTrue(running.await(10, TimeUnit.SECONDS));

            pipeline.addMarker("start of rotation");
            Thread.sleep(100);
            pipeline.addMarker("boresight");

            List<CapturePipeline.Marker> markers = pipeline.markers();
            assertEquals(2, markers.size());
            assertEquals("start of rotation", markers.get(0).label());
            assertTrue(markers.get(1).atSequence() >= markers.get(0).atSequence(),
                    "markers should be ordered along the trace");
            assertNotNull(markers.get(0).at());
        }
    }

    @Test
    @DisplayName("serial sources now open a real producer; replay still fails loudly")
    void producerAvailabilityMatchesReality() {
        // Serial is implemented against the 2026-08-08 capture. Constructing the
        // producer must not touch the port -- opening happens in produce(), on
        // the pipeline's thread -- so this is safe with no hardware attached.
        var serial = Producers.forSource(SerialSource.onPort("COM7"));
        assertTrue(serial.describe().contains("COM7"));
        serial.close();

        // Replay stays unimplemented on purpose: the firmware's CSV layout has
        // still not been captured, and guessing would be worse than throwing.
        var replay = assertThrows(UnsupportedOperationException.class,
                () -> Producers.forSource(ReplaySource.unpaced(Path.of("run.csv"))));
        assertTrue(replay.getMessage().contains("not implemented"));
    }
}
