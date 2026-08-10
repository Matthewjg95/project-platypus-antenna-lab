package dev.antennalab.core.source;

/**
 * The write half of a producer that can also be commanded.
 *
 * <p>The Tab5 firmware accepts two-byte command pairs over the same USB-CDC
 * stream it logs on ({@code AE} = external antenna, {@code AI} = internal,
 * {@code AQ} = query; the {@code S} family drives screenshots). Commands are
 * fire-and-forget at this layer — <b>confirmation is always read from the log
 * stream</b>, because the firmware's {@code [ANT] ->} line is the ground truth
 * for what the RF switch actually did. A command that was sent but not
 * confirmed is a command that did not happen.
 */
public interface CommandChannel {

    /** The Tab5's "switch to external antenna" command pair. */
    byte[] CMD_ANT_EXTERNAL = {'A', 'E'};

    /** The Tab5's "switch to internal antenna" command pair. */
    byte[] CMD_ANT_INTERNAL = {'A', 'I'};

    /** The Tab5's "report antenna state" command pair. */
    byte[] CMD_ANT_QUERY = {'A', 'Q'};

    /**
     * Send raw command bytes to the device.
     *
     * @throws java.io.IOException if the port is closed or the write fails —
     *         callers must treat this as "the run cannot continue", not retry
     *         blindly into a dead port.
     */
    void sendCommand(byte[] bytes) throws java.io.IOException;
}
