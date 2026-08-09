package dev.antennalab.core.source;

import com.fazecast.jSerialComm.SerialPort;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.parse.Tab5LogParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Live capture from the M5Tab5 over USB serial.
 *
 * <p>Runs on a pipeline-owned virtual thread and blocks in semi-blocking reads;
 * cancellation is the interrupt from the enclosing {@code StructuredTaskScope},
 * plus {@link #close()} closing the port out from under a blocked read.
 *
 * <p><b>Connect-time reset is expected.</b> Opening the port toggles DTR, which
 * trips the ESP32's auto-reset — the observed capture begins with the boot ROM
 * banner ({@code rst:0x17 CHIP_USB_UART_RESET}). The parser treats banner and
 * log chatter as skippable noise, so the stream simply starts producing samples
 * once the firmware is back up (a few seconds).
 *
 * <p>Samples are stamped on arrival: the firmware prints no per-sample
 * timestamps, and at its ~1.5–2 s cadence USB latency is noise.
 */
public final class SerialProducer implements SampleProducer {

    /** Semi-blocking read window; also the cadence of interrupt/close checks. */
    private static final int READ_TIMEOUT_MS = 500;

    private final SerialSource spec;
    private final Tab5LogParser parser = new Tab5LogParser();

    private volatile SerialPort port;
    private volatile boolean closed;

    public SerialProducer(SerialSource spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        this.spec = spec;
    }

    @Override
    public void produce(SampleSink sink) throws Exception {
        SerialPort p = SerialPort.getCommPort(spec.portName());
        p.setBaudRate(spec.baudRate());
        // Semi-blocking: a read returns after up to READ_TIMEOUT_MS even with no
        // data, which is what lets the loop notice interruption and close()
        // promptly instead of hanging on a silent port.
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
        // Hold DTR/RTS low across the open. The ESP32 auto-reset circuit fires on
        // a DTR pulse, and a connect that reboots the device kicks it back to its
        // menu -- the single worst UX moment this app had. Best effort; if the
        // board resets anyway, the parser eats the boot banner.
        p.clearDTR();
        p.clearRTS();

        if (!p.openPort()) {
            throw new IOException("could not open " + spec.portName()
                    + " — is another serial monitor holding it?");
        }
        port = p;

        byte[] buffer = new byte[4096];
        long sequence = 0;

        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                int n = p.readBytes(buffer, buffer.length);
                if (n < 0) {
                    // The device vanished (unplugged) or the port was closed by
                    // close(). The distinction matters to the UI message only.
                    if (closed) {
                        return;
                    }
                    throw new IOException(spec.portName() + " disconnected");
                }
                if (n == 0) {
                    continue; // quiet interval; loop to re-check interrupt/closed
                }
                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                Instant now = Instant.now();
                for (Tab5LogParser.Reading reading : parser.feed(chunk)) {
                    sink.accept(new RssiSample(sequence++, now, reading.path(), reading.rssiDbm()));
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("serial capture cancelled");
            }
        } finally {
            if (p.isOpen()) {
                p.closePort();
            }
        }
    }

    @Override
    public String describe() {
        return "Serial %s @ %d baud (%d lines seen, %d ignored)"
                .formatted(spec.portName(), spec.baudRate(),
                        parser.linesSeen(), parser.linesIgnored());
    }

    @Override
    public void close() {
        closed = true;
        SerialPort p = port;
        if (p != null && p.isOpen()) {
            // Closing the port unblocks a read in progress, so the loop exits
            // within one read cycle even if the firmware has gone silent.
            p.closePort();
        }
    }
}
