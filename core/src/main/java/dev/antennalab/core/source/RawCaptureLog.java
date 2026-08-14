package dev.antennalab.core.source;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Tees every byte read from the device into a file, before parsing touches it.
 *
 * <p>Two reasons this exists, and both are about evidence:
 *
 * <ul>
 *   <li><b>The raw capture is the measurement.</b> Parsed samples are an
 *   interpretation; if the parser is ever found wanting, the raw log is what
 *   lets a session be re-read rather than re-run. The processed result must
 *   never be the only surviving representation of the data.
 *   <li><b>The Tab5 crashes intermittently</b> — suspected SD/C6 SDIO pin
 *   contention — and the crash cannot be reproduced on demand. A standalone
 *   serial logger cannot watch for it, because the port has one owner and the
 *   app is that owner. So the app itself keeps the log, and whenever the crash
 *   next happens mid-capture, the firmware's dying words are already on disk.
 * </ul>
 *
 * <p><b>Failure policy: the log must never kill the capture.</b> A full disk or
 * a permissions problem costs the raw log — it must not cost the live session
 * too. On the first write failure the log disables itself and stays disabled;
 * {@link #failure()} reports it so the UI can tell the operator rather than
 * failing silently forever.
 */
public final class RawCaptureLog implements AutoCloseable {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path file;
    private OutputStream out;
    private IOException failure;

    /**
     * Open a log in {@code dir}, named by port and wall-clock start time.
     *
     * @throws IOException if the directory or file cannot be created — thrown
     *         rather than swallowed because it happens before any data is at
     *         stake, when the caller can still choose to proceed without a log.
     */
    public RawCaptureLog(Path dir, String portName) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("raw-%s-%s.log".formatted(
                portName.replaceAll("[^A-Za-z0-9]", ""), STAMP.format(Instant.now())));
        this.out = Files.newOutputStream(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Where this capture is being written. */
    public Path file() {
        return file;
    }

    /**
     * Append {@code length} bytes; best-effort.
     *
     * <p>Flushes per chunk on purpose: the log's whole value in a crash is the
     * bytes that arrived immediately before the device died, and those are
     * exactly the bytes an OS buffer would still be holding.
     */
    public void append(byte[] bytes, int length) {
        OutputStream o = out;
        if (o == null) {
            return;
        }
        try {
            o.write(bytes, 0, length);
            o.flush();
        } catch (IOException e) {
            failure = e;
            out = null;
            try {
                o.close();
            } catch (IOException ignored) {
                // Already in the failure path; nothing better to do with a
                // second exception from the same dead stream.
            }
        }
    }

    /** The write failure that disabled this log, if any. */
    public IOException failure() {
        return failure;
    }

    @Override
    public void close() {
        OutputStream o = out;
        out = null;
        if (o != null) {
            try {
                o.close();
            } catch (IOException e) {
                failure = e;
            }
        }
    }
}
