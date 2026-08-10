package dev.antennalab.core.source;

/**
 * The reading half of a capture: pulls from somewhere and pushes samples out.
 *
 * <p>One instance per run. Implementations block; they are always executed on a
 * virtual thread owned by the capture pipeline's task scope, so blocking is
 * cheap and cancellation is by interrupt.
 */
public interface SampleProducer extends AutoCloseable {

    /**
     * Read until the source is exhausted or the thread is interrupted.
     *
     * <p>Returning normally means "this source ended" (a replay file ran out).
     * Throwing {@link InterruptedException} means "we were cancelled", which the
     * pipeline treats as a clean stop rather than a failure.
     */
    void produce(SampleSink sink) throws Exception;

    /** Human-readable name for status display and error messages. */
    String describe();

    /**
     * The command channel to the device, when this producer has one.
     *
     * <p>Empty for synthetic and replay sources — there is nothing to command.
     * Callers that need remote control (the experiment runner's hands-free
     * tier) check this and fall back to guided prompts when absent.
     */
    default java.util.Optional<CommandChannel> commands() {
        return java.util.Optional.empty();
    }

    /** Release the port, file handle or timer. Must be idempotent. */
    @Override
    void close();
}
