package dev.antennalab.core.source;

import dev.antennalab.core.domain.RssiSample;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps a producer so a device that vanishes and comes back does not end the
 * capture.
 *
 * <p>The Tab5 reboots. Sometimes deliberately, sometimes because the antenna
 * applet's WiFi bring-up collides with the mounted SD card on the shared SDIO
 * pins. From the host that looks identical: the USB CDC device disappears,
 * {@code readBytes} returns -1, and the whole capture used to die — taking with
 * it every sample collected before the reboot. Losing ten minutes of a run to a
 * five-second reboot is the wrong trade.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not paper over the gap. Samples are stamped on arrival, so a reboot
 * leaves a visible hole in the timeline rather than a smooth line, and the
 * listener is told on the way down and on the way back up. An automated run that
 * was mid-block when the device rebooted still fails its own checks — the
 * firmware restarts on the internal antenna, and
 * {@code ExperimentRunner} voids a block whose samples change path underneath
 * it. Reconnecting keeps the <em>capture</em> alive; it deliberately does not
 * rescue a <em>measurement</em> that was invalidated by the reboot.
 *
 * <h2>Sequence numbers</h2>
 *
 * <p>Each reconnect builds a fresh delegate, and a fresh {@link SerialProducer}
 * numbers its samples from zero. Passing those through unchanged would put
 * duplicate sequence numbers in one session, which is a silent corruption of the
 * saved record. So this class re-stamps every sample with its own monotonic
 * counter and the delegate's numbering is discarded.
 */
public final class ReconnectingProducer implements SampleProducer, CommandChannel {

    /** Attempts after a loss before the capture is allowed to fail. */
    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    /**
     * Wait between attempts.
     *
     * <p>An ESP32 takes a few seconds to boot and re-enumerate its USB CDC
     * device, so retrying faster than this just fails repeatedly against a port
     * that does not exist yet.
     */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(2);

    /**
     * Samples a reconnected link must deliver before its retry budget resets.
     *
     * <p>More than one on purpose. A board stuck in a boot loop can enumerate,
     * emit a single line and die, over and over; resetting the budget on the
     * first sample would make that flap forever behind a "reconnecting" status
     * that never resolves. Requiring a few samples means a link has to actually
     * work before it earns a fresh allowance.
     */
    public static final int HEALTHY_SAMPLES = 3;

    /** Told when the link drops and when it comes back. */
    public interface Listener {

        /** The device went away; an attempt to get it back is about to be made. */
        default void onConnectionLost(String reason, int attempt, int maxAttempts) {
        }

        /** Samples are flowing again after {@code attempt} attempts. */
        default void onReconnected(int attempt) {
        }

        /** Out of attempts; the capture is ending for real. */
        default void onGaveUp(String reason, int attempts) {
        }
    }

    private final Supplier<SampleProducer> factory;
    private final String label;
    private final boolean commandable;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Listener listener;

    private volatile SampleProducer delegate;
    private volatile boolean closed;
    private volatile int attempt;
    private volatile boolean recovering;

    /** Monotonic across every reconnect -- see the class note on sequencing. */
    private long sequence;

    /** Samples delivered by the current delegate; gates the budget reset. */
    private volatile int samplesThisLife;

    public ReconnectingProducer(Supplier<SampleProducer> factory,
                                boolean commandable,
                                int maxAttempts,
                                Duration retryDelay,
                                Listener listener) {
        this(factory, "source", commandable, maxAttempts, retryDelay, listener);
    }

    /**
     * @param label names the device while no delegate exists. Without it the
     *              status line would stop naming the port during an outage --
     *              exactly when the operator most needs to know which device
     *              went away.
     */
    public ReconnectingProducer(Supplier<SampleProducer> factory,
                                String label,
                                boolean commandable,
                                int maxAttempts,
                                Duration retryDelay,
                                Listener listener) {
        if (factory == null) {
            throw new IllegalArgumentException("factory is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.factory = factory;
        this.label = label == null ? "source" : label;
        this.commandable = commandable;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay == null ? DEFAULT_RETRY_DELAY : retryDelay;
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    /** Wrap a serial source with the default retry policy. */
    public static ReconnectingProducer forSerial(
            dev.antennalab.core.domain.SerialSource spec, Listener listener) {
        return new ReconnectingProducer(() -> new SerialProducer(spec),
                "Serial %s @ %d baud".formatted(spec.portName(), spec.baudRate()),
                true, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY, listener);
    }

    @Override
    public void produce(SampleSink sink) throws Exception {
        Exception last = null;

        while (!closed && !Thread.currentThread().isInterrupted()) {
            SampleProducer d = factory.get();
            delegate = d;
            samplesThisLife = 0;
            try {
                d.produce(resequencing(sink));
                // Returning normally means the source ended on its own terms --
                // a replay file running out, not a device vanishing. Nothing to
                // reconnect to.
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                last = e;
                attempt++;
                if (attempt > maxAttempts) {
                    listener.onGaveUp(String.valueOf(e.getMessage()), attempt - 1);
                    throw e;
                }
                recovering = true;
                listener.onConnectionLost(String.valueOf(e.getMessage()), attempt, maxAttempts);
            } finally {
                delegate = null;
                d.close();
            }

            // Sleep between attempts. Interruption here is cancellation, not a
            // failure, and must propagate rather than being swallowed into
            // another retry.
            Thread.sleep(retryDelay.toMillis());
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("capture cancelled while reconnecting");
        }
        if (last != null && !closed) {
            throw last;
        }
    }

    /**
     * Re-stamp samples with a continuous sequence, and notice the first one
     * after a reconnect.
     *
     * <p>"Reconnected" fires on the first arriving <em>sample</em>, not on a
     * successful port open: an open that succeeds against a board still booting
     * proves nothing, and the operator cares that data is flowing again.
     */
    private SampleSink resequencing(SampleSink out) {
        return sample -> {
            if (recovering) {
                // Tell the operator immediately -- they care that data moved.
                recovering = false;
                listener.onReconnected(attempt);
            }
            if (samplesThisLife < HEALTHY_SAMPLES && ++samplesThisLife == HEALTHY_SAMPLES) {
                // Only now is the budget refilled: ten attempts is the allowance
                // for ONE outage, not for the lifetime of the capture. A bench
                // session that survives four separate reboots is a success and
                // would otherwise die on the fifth. Gated on a link that proved
                // itself, so a boot loop still exhausts its retries.
                attempt = 0;
            }
            out.accept(new RssiSample(sequence++, sample.timestamp(),
                    sample.antenna(), sample.rssiDbm()));
        };
    }

    @Override
    public String describe() {
        SampleProducer d = delegate;
        if (d != null) {
            return d.describe();
        }
        if (closed) {
            return label + " — disconnected";
        }
        return attempt == 0
                ? label + " — not connected yet"
                : label + " — reconnecting (attempt %d of %d)".formatted(attempt, maxAttempts);
    }

    @Override
    public Optional<CommandChannel> commands() {
        return commandable ? Optional.of(this) : Optional.empty();
    }

    /**
     * Forward a command to the live delegate.
     *
     * <p>Throws while disconnected rather than queueing. A command that is
     * buffered and delivered after a reboot would arrive at a device in a
     * different state than the caller believed, and the runner's contract is
     * that an unconfirmed command did not happen.
     */
    @Override
    public void sendCommand(byte[] bytes) throws IOException {
        SampleProducer d = delegate;
        if (d == null) {
            throw new IOException("cannot command: reconnecting to the device");
        }
        CommandChannel channel = d.commands()
                .orElseThrow(() -> new IOException("this source cannot be commanded"));
        channel.sendCommand(bytes);
    }

    @Override
    public void close() {
        closed = true;
        SampleProducer d = delegate;
        if (d != null) {
            d.close();
        }
    }

    /** Attempts made since the last successful sample. Zero when healthy. */
    public int attempts() {
        return attempt;
    }
}
