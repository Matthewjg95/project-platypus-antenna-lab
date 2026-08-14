package dev.antennalab.core.pipeline;

import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.source.Producers;
import dev.antennalab.core.source.SampleProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs one capture: producer -> queue -> rolling buffer -> chart updater.
 *
 * <h2>Why structured concurrency (JEP 525, preview in JDK 26)</h2>
 *
 * <p>The three stages are not independent jobs, they are one unit of work. If the
 * serial port drops, the buffer and publish stages are pointless and must stop;
 * if the operator closes the port, all three must stop promptly and the port must
 * be released. Written with a plain {@code ExecutorService} that is a pile of
 * manual bookkeeping: hold three futures, cancel the siblings in every failure
 * path, and hope no path was missed. The classic bug -- the reader thread
 * outliving the capture and holding the COM port open, so the next Start fails
 * with "access denied" -- is exactly the thread leak structured concurrency
 * exists to make impossible.
 *
 * <p>Here the scope owns all three subtasks. Leaving the try-with-resources block
 * cannot complete until every forked subtask has finished, by any route: normal
 * return, failure, or interruption. So {@link #stop()} interrupting the owner
 * thread is sufficient to guarantee the port is closed and nothing is left
 * running -- there is no cancellation path to forget, because the block's scope
 * *is* the cancellation path.
 *
 * <p>The stages also shut down in cascade rather than all at once, which matters
 * for replay: when a file runs out, the producer returns, the buffer stage drains
 * what is still queued, and only then does the publisher push a final frame.
 * Nothing captured is lost to an abrupt teardown.
 *
 * <h2>Threading</h2>
 *
 * <p>Every stage runs on a virtual thread. All three spend nearly all their time
 * blocked -- on the port, on the queue, on the frame clock -- which is precisely
 * the workload virtual threads are for. No thread pool is sized anywhere in this
 * project, and no reactive framework is involved: the blocking code reads
 * sequentially because it is sequential.
 */
public final class CapturePipeline implements AutoCloseable {

    /** Samples retained in the rolling window. At 20 Hz/path this is ~50 s of scope. */
    public static final int DEFAULT_WINDOW_SAMPLES = 2_000;

    /** Bounded so a stalled UI applies backpressure instead of exhausting the heap. */
    private static final int QUEUE_CAPACITY = 8_192;

    /** ~60 fps. Repainting faster than this is wasted work. */
    private static final long FRAME_INTERVAL_NANOS = 16_666_667L;

    /** How long the buffer stage waits on an empty queue before re-checking for shutdown. */
    private static final long DRAIN_POLL_MILLIS = 20;

    private final Source source;
    private final CaptureListener listener;
    private final RollingBuffer buffer;
    private final BlockingQueue<RssiSample> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<RssiSample> recorded = Collections.synchronizedList(new ArrayList<>());
    private final List<Marker> markers = Collections.synchronizedList(new ArrayList<>());

    private final AtomicBoolean running = new AtomicBoolean();

    /** Freezes the display only; the buffer keeps filling and recording continues. */
    private volatile boolean paused;

    /** When true, every sample is also accumulated into the full-run history. */
    private volatile boolean recording;

    /** Set by stage 1 on exit so stages 2 and 3 can drain and finish in order. */
    private volatile boolean producerFinished;

    /** Set by stage 2 on exit so stage 3 knows to emit its final frame. */
    private volatile boolean bufferFinished;

    private volatile Thread owner;
    private volatile SampleProducer producer;
    private final SampleProducer injectedProducer;

    /** Per-sample observers; called on the buffer stage's virtual thread. */
    private final List<java.util.function.Consumer<RssiSample>> sampleTaps =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** An operator annotation pinned to a point on the trace. */
    public record Marker(long atSequence, java.time.Instant at, String label) {
    }

    public CapturePipeline(Source source, CaptureListener listener) {
        this(source, listener, DEFAULT_WINDOW_SAMPLES);
    }

    public CapturePipeline(Source source, CaptureListener listener, int windowSamples) {
        this(source, null, listener, windowSamples);
    }

    /**
     * Variant that injects the producer directly instead of resolving it from
     * the source. Exists for the experiment runner's tests, which need a
     * producer whose antenna path obeys {@code CommandChannel} commands — a
     * thing no real {@link Source} description can express.
     */
    public CapturePipeline(Source source,
                           SampleProducer injectedProducer,
                           CaptureListener listener,
                           int windowSamples) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        this.source = source;
        this.injectedProducer = injectedProducer;
        this.listener = listener;
        this.buffer = new RollingBuffer(windowSamples);
    }

    /**
     * Passes link loss and recovery out to the capture listener.
     *
     * <p>The reconnect policy lives in the producer, which knows what a dropped
     * link means; the pipeline only relays it, so the UI has one listener to
     * implement rather than two.
     */
    private final class ReconnectListener
            implements dev.antennalab.core.source.ReconnectingProducer.Listener {

        @Override
        public void onConnectionLost(String reason, int attempt, int maxAttempts) {
            listener.onConnectionLost(reason, attempt, maxAttempts);
        }

        @Override
        public void onReconnected(int attempt) {
            listener.onReconnected(attempt);
        }
    }

    /** Begin capturing. Returns immediately; the work happens on virtual threads. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("capture is already running");
        }
        producerFinished = false;
        bufferFinished = false;
        owner = Thread.ofVirtual()
                .name("capture-" + source.displayName())
                .start(this::run);
    }

    /**
     * The owner thread: opens the scope, forks the three stages, and blocks in
     * {@code join()} until they all finish or it is interrupted.
     */
    private void run() {
        try {
            SampleProducer prod = injectedProducer != null
                    ? injectedProducer
                    : Producers.forSource(source, new ReconnectListener());
            producer = prod;

            // Both resources close in reverse order on exit. The scope closing
            // first guarantees no stage is still touching the producer when the
            // producer is closed -- which is the ordering that makes releasing a
            // serial port safe.
            try (prod; var scope = StructuredTaskScope.open()) {
                scope.fork(() -> {
                    try {
                        // Stage 1: read. The sink is queue::put, so a slow consumer
                        // applies backpressure to the reader rather than dropping.
                        prod.produce(queue::put);
                        return null;
                    } finally {
                        producerFinished = true;
                    }
                });
                scope.fork(() -> {
                    runBufferStage();
                    return null;
                });
                scope.fork(() -> {
                    runPublishStage();
                    return null;
                });

                // Default joiner waits for all subtasks and propagates the first
                // failure after cancelling the rest.
                scope.join();
            }
            listener.onCompleted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onCancelled();
        } catch (StructuredTaskScope.FailedException e) {
            // Unwrap so the UI shows "port disconnected", not a wrapper type.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            listener.onFailed(cause);
        } catch (Exception e) {
            listener.onFailed(e);
        } finally {
            producer = null;
            owner = null;
            running.set(false);
        }
    }

    /** Stage 2: queue -> rolling buffer, plus the full-run history when recording. */
    private void runBufferStage() throws InterruptedException {
        try {
            // Keep going while the producer is alive, then drain whatever is left.
            while (!producerFinished || !queue.isEmpty()) {
                RssiSample sample = queue.poll(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (sample != null) {
                    buffer.add(sample);
                    if (recording) {
                        recorded.add(sample);
                    }
                    for (var tap : sampleTaps) {
                        tap.accept(sample);
                    }
                }
            }
        } finally {
            bufferFinished = true;
        }
    }

    /** Stage 3: rolling buffer -> listener, coalesced to frame rate. */
    private void runPublishStage() throws InterruptedException {
        long nextFrame = System.nanoTime();
        while (!bufferFinished) {
            nextFrame += FRAME_INTERVAL_NANOS;
            long waitNanos = nextFrame - System.nanoTime();
            if (waitNanos > 0) {
                LockSupport.parkNanos(waitNanos);
                if (Thread.interrupted()) {
                    throw new InterruptedException("publish stage cancelled");
                }
            } else {
                nextFrame = System.nanoTime();
            }
            if (!paused) {
                listener.onFrame(buffer.snapshot());
            }
        }
        // One last frame so the trace shows every sample that made it into the
        // buffer, including any drained during shutdown.
        listener.onFrame(buffer.snapshot());
    }

    /**
     * Stop the capture and release the source.
     *
     * <p>Closing the producer asks it to exit its read loop; interrupting the
     * owner unblocks {@code join()}, and the scope's close then cancels and joins
     * any stage still running. Both are needed: the first is the polite request,
     * the second is the guarantee.
     */
    public void stop() {
        SampleProducer p = producer;
        if (p != null) {
            p.close();
        }
        Thread t = owner;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Stop and wait for the capture to fully unwind. */
    @Override
    public void close() {
        stop();
        Thread t = owner;
        if (t != null) {
            try {
                t.join(java.time.Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** True while a capture is in flight. */
    public boolean isRunning() {
        return running.get();
    }

    /** Freeze or unfreeze the display. Capture and recording continue regardless. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** @see #setPaused(boolean) */
    public boolean isPaused() {
        return paused;
    }

    /** Start or stop accumulating the full-run history behind the rolling window. */
    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    /** @see #setRecording(boolean) */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Observe every sample as it lands in the buffer.
     *
     * <p>Called on a pipeline virtual thread at sample rate — the experiment
     * runner's confirmation logic lives here. Keep taps cheap; the frame-rate
     * {@link CaptureListener} remains the right place for UI work.
     */
    public void addSampleTap(java.util.function.Consumer<RssiSample> tap) {
        if (tap != null) {
            sampleTaps.add(tap);
        }
    }

    /** Pin an annotation to the current point on the trace. */
    public void addMarker(String label) {
        markers.add(new Marker(buffer.totalWritten(), java.time.Instant.now(), label));
    }

    /** Markers added so far, in the order they were placed. */
    public List<Marker> markers() {
        synchronized (markers) {
            return List.copyOf(markers);
        }
    }

    /** Everything captured while recording was on. */
    public List<RssiSample> recordedSamples() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    /** Discard the recorded history, e.g. after saving it to a session. */
    public void clearRecording() {
        recorded.clear();
    }

    /**
     * The command channel to the device this capture is reading, when it has
     * one — the write half the experiment runner uses to drive the RF switch
     * while this pipeline keeps reading confirmations off the same stream.
     */
    public java.util.Optional<dev.antennalab.core.source.CommandChannel> commands() {
        SampleProducer p = producer;
        return p == null ? java.util.Optional.empty() : p.commands();
    }

    /** The rolling window, for callers that want to snapshot outside a frame callback. */
    public RollingBuffer buffer() {
        return buffer;
    }

    /** What this pipeline is capturing from. */
    public Source source() {
        return source;
    }
}
