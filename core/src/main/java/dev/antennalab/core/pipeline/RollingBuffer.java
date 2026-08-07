package dev.antennalab.core.pipeline;

import dev.antennalab.core.domain.RssiSample;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-capacity ring buffer of samples: the rolling window the scope draws.
 *
 * <p>The producer appends at whatever rate the board emits; the chart updater
 * snapshots at frame rate. Those two rates are unrelated, and the buffer is the
 * only place they meet -- so it is the only thing in the pipeline that needs
 * locking. Everything flowing through it ({@link RssiSample}) is an immutable
 * record, which is what keeps that lock down to a single short critical section
 * rather than a copy-on-read of mutable objects.
 *
 * <p>Overwrites oldest-first once full. A capture that runs for an hour uses the
 * same memory as one that runs for a minute; the full history, when recording is
 * on, is accumulated separately by the pipeline.
 */
public final class RollingBuffer {

    private final RssiSample[] entries;
    private final Object lock = new Object();

    /** Total appends ever seen; index modulo capacity gives the write position. */
    private long written;

    public RollingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.entries = new RssiSample[capacity];
    }

    /** Maximum number of samples retained. */
    public int capacity() {
        return entries.length;
    }

    /** Append one sample, evicting the oldest if the window is full. */
    public void add(RssiSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sample is required");
        }
        synchronized (lock) {
            entries[(int) (written % entries.length)] = sample;
            written++;
        }
    }

    /** How many samples are currently retained (at most {@link #capacity()}). */
    public int size() {
        synchronized (lock) {
            return (int) Math.min(written, entries.length);
        }
    }

    /** Total samples ever appended, including those since evicted. */
    public long totalWritten() {
        synchronized (lock) {
            return written;
        }
    }

    /**
     * Point-in-time copy of the window, oldest first.
     *
     * <p>Returns a fresh list so the caller -- typically the JavaFX thread -- can
     * iterate without holding the lock or racing the producer.
     */
    public List<RssiSample> snapshot() {
        synchronized (lock) {
            int n = (int) Math.min(written, entries.length);
            List<RssiSample> out = new ArrayList<>(n);
            long start = written - n;
            for (long i = start; i < written; i++) {
                out.add(entries[(int) (i % entries.length)]);
            }
            return out;
        }
    }

    /** Drop everything, e.g. when the operator clears the scope between runs. */
    public void clear() {
        synchronized (lock) {
            java.util.Arrays.fill(entries, null);
            written = 0;
        }
    }
}
