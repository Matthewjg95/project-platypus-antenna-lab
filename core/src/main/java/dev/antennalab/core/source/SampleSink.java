package dev.antennalab.core.source;

import dev.antennalab.core.domain.RssiSample;

/**
 * Where a producer hands finished samples.
 *
 * <p>Declared to throw {@link InterruptedException} on purpose. Interruption is
 * how structured concurrency cancels a running capture: when the scope shuts
 * down, the producer is blocked in here, and letting the exception propagate is
 * what makes "close the port" stop the read loop promptly instead of after the
 * next timeout.
 */
@FunctionalInterface
public interface SampleSink {

    /** Accept one sample; may block if the consumer is behind. */
    void accept(RssiSample sample) throws InterruptedException;
}
