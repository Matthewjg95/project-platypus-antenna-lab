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
 * <p><b>Connecting does not reset the board.</b> Opening a port normally toggles
 * DTR, which trips the ESP32's auto-reset and costs a few seconds of boot before
 * any sample arrives. Holding DTR and RTS low across the open (see
 * {@link #produce}) avoids that entirely — verified on hardware by the firmware's
 * own uptime counter running continuously across a connect, with no boot ROM
 * banner. The parser still treats banner and log chatter as skippable noise, so a
 * capture that <em>does</em> begin with a reboot (a crash, or a manual reset) is
 * handled rather than mis-parsed.
 *
 * <p>Samples are stamped on arrival: the firmware prints no per-sample
 * timestamps, and at its ~1.5–2 s cadence USB latency is noise.
 *
 * <p><b>Every byte is also teed to a raw log</b> under {@code ~/AntennaLab/raw}
 * before parsing — see {@link RawCaptureLog} for why. Best-effort: a capture
 * without a log is degraded, a capture killed by its log would be absurd.
 */
public final class SerialProducer implements SampleProducer, CommandChannel {

    /** Semi-blocking read window; also the cadence of interrupt/close checks. */
    private static final int READ_TIMEOUT_MS = 500;

    /** Where raw captures land; shared with the session store's root. */
    private static final java.nio.file.Path RAW_DIR =
            java.nio.file.Path.of(System.getProperty("user.home"), "AntennaLab", "raw");

    private final SerialSource spec;
    private final Tab5LogParser parser = new Tab5LogParser();

    private volatile SerialPort port;
    private volatile boolean closed;
    private volatile RawCaptureLog rawLog;

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

        RawCaptureLog raw = null;
        try {
            raw = new RawCaptureLog(RAW_DIR, spec.portName());
        } catch (IOException e) {
            // Proceed without a log rather than refusing to capture. describe()
            // does not advertise a file that does not exist.
        }
        rawLog = raw;

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
                if (raw != null) {
                    // Before parsing, so the log holds what the device actually
                    // sent -- including anything the parser would ignore, which
                    // in a crash is precisely the interesting part.
                    raw.append(buffer, n);
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
            if (raw != null) {
                raw.close();
            }
            if (p.isOpen()) {
                p.closePort();
            }
        }
    }

    @Override
    public String describe() {
        RawCaptureLog raw = rawLog;
        String rawNote = raw == null ? ""
                : raw.failure() != null
                        ? " [raw log FAILED: " + raw.failure().getMessage() + "]"
                        : " [raw: " + raw.file().getFileName() + "]";
        return "Serial %s @ %d baud (%d lines seen, %d ignored)%s"
                .formatted(spec.portName(), spec.baudRate(),
                        parser.linesSeen(), parser.linesIgnored(), rawNote);
    }

    @Override
    public java.util.Optional<CommandChannel> commands() {
        return java.util.Optional.of(this);
    }

    @Override
    public void sendCommand(byte[] bytes) throws IOException {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            throw new IOException("cannot command " + spec.portName() + ": port not open");
        }
        int written = p.writeBytes(bytes, bytes.length);
        if (written != bytes.length) {
            throw new IOException("short write to " + spec.portName()
                    + " (" + written + " of " + bytes.length + " bytes)");
        }
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
