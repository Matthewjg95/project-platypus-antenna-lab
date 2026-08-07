package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.pipeline.RollingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring buffer, especially its wrap-around behaviour.
 *
 * <p>Off-by-one errors here would show up as a trace that jumps back in time
 * once the window fills -- subtle enough on a moving scope to go unnoticed until
 * it corrupts a recorded session.
 */
class RollingBufferTest {

    private static final Instant T0 = Instant.parse("2026-08-06T12:00:00Z");

    private static RssiSample sample(long sequence) {
        return new RssiSample(sequence, T0.plusMillis(sequence), AntennaPath.CHIP, -60.0 - sequence);
    }

    @Test
    @DisplayName("an unfilled buffer reports only what it holds, oldest first")
    void partiallyFilled() {
        RollingBuffer buffer = new RollingBuffer(10);
        for (int i = 0; i < 4; i++) {
            buffer.add(sample(i));
        }

        List<RssiSample> snapshot = buffer.snapshot();

        assertEquals(4, buffer.size());
        assertEquals(4, buffer.totalWritten());
        assertEquals(List.of(0L, 1L, 2L, 3L), snapshot.stream().map(RssiSample::sequence).toList());
    }

    @Test
    @DisplayName("once full the buffer evicts oldest-first and stays in order")
    void wrapsAroundInOrder() {
        RollingBuffer buffer = new RollingBuffer(5);
        for (int i = 0; i < 8; i++) {
            buffer.add(sample(i));
        }

        List<RssiSample> snapshot = buffer.snapshot();

        assertEquals(5, buffer.size(), "size is capped at capacity");
        assertEquals(8, buffer.totalWritten(), "total counts evicted samples too");
        // Samples 0-2 are gone; what remains must still read oldest -> newest.
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), snapshot.stream().map(RssiSample::sequence).toList());
    }

    @Test
    @DisplayName("wrapping many times over keeps the window correct")
    void survivesManyWraps() {
        RollingBuffer buffer = new RollingBuffer(3);
        for (int i = 0; i < 1_000; i++) {
            buffer.add(sample(i));
        }

        assertEquals(3, buffer.size());
        assertEquals(1_000, buffer.totalWritten());
        assertEquals(List.of(997L, 998L, 999L),
                buffer.snapshot().stream().map(RssiSample::sequence).toList());
    }

    @Test
    @DisplayName("snapshot is a copy, so the caller can iterate while the producer writes")
    void snapshotIsDetached() {
        RollingBuffer buffer = new RollingBuffer(5);
        buffer.add(sample(0));
        List<RssiSample> first = buffer.snapshot();

        buffer.add(sample(1));

        assertEquals(1, first.size(), "an earlier snapshot must not grow");
        assertEquals(2, buffer.snapshot().size());
    }

    @Test
    @DisplayName("clear resets both contents and counters")
    void clearResets() {
        RollingBuffer buffer = new RollingBuffer(4);
        for (int i = 0; i < 6; i++) {
            buffer.add(sample(i));
        }

        buffer.clear();

        assertEquals(0, buffer.size());
        assertEquals(0, buffer.totalWritten());
        assertTrue(buffer.snapshot().isEmpty());
    }

    @Test
    @DisplayName("concurrent writes and snapshots do not corrupt the window")
    void concurrentAccessIsSafe() throws InterruptedException {
        RollingBuffer buffer = new RollingBuffer(256);
        int writes = 20_000;

        // Mirrors the real pipeline: one virtual thread appending, another
        // snapshotting at a different rate.
        Thread writer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < writes; i++) {
                buffer.add(sample(i));
            }
        });
        Thread reader = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 2_000; i++) {
                List<RssiSample> snap = buffer.snapshot();
                // Any null or out-of-order entry means the lock is not doing its job.
                long previous = -1;
                for (RssiSample s : snap) {
                    if (s.sequence() <= previous) {
                        throw new AssertionError("snapshot out of order at " + s.sequence());
                    }
                    previous = s.sequence();
                }
            }
        });

        writer.join();
        reader.join();

        assertEquals(writes, buffer.totalWritten());
        assertEquals(256, buffer.size());
    }
}
