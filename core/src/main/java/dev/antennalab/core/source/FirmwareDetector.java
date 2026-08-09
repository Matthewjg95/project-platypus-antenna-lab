package dev.antennalab.core.source;

import com.fazecast.jSerialComm.SerialPort;
import dev.antennalab.core.domain.SerialSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Finds the Tab5 antenna firmware among the machine's serial ports, so the app
 * connects itself instead of asking the operator to pick a COM number.
 *
 * <p>Strategy: listen briefly on each plausible port and look for the
 * firmware's log signature. The signature tokens come from the real capture —
 * {@code [SAMPLE]}, {@code [hb]}, {@code [LOOP}, {@code [ANT} — and the capture
 * shows the firmware chatters continuously in <em>every</em> screen (heartbeats
 * and loop counters print even on the menu), so detection works regardless of
 * what mode the device is in.
 *
 * <p>Ports whose descriptive name mentions Bluetooth are skipped outright:
 * they enumerate as serial ports, can take seconds to fail to open, and are
 * never the board.
 *
 * <p>Opens are attempted with DTR/RTS cleared so that merely <em>probing</em> a
 * port does not reset an ESP32 hanging off it. Whether that fully suppresses
 * the auto-reset is hardware-dependent; the parser tolerates a boot banner
 * either way.
 */
public final class FirmwareDetector {

    private static final String[] SIGNATURE = {"[SAMPLE]", "[hb]", "[LOOP", "[ANT", "[SCAN"};

    private FirmwareDetector() {
    }

    /** True when the text contains the antenna firmware's unmistakable chatter. */
    public static boolean looksLikeAntennaFirmware(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        for (String token : SIGNATURE) {
            if (chunk.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan all ports and return the system name of the first one speaking the
     * antenna firmware's log format.
     *
     * <p>Blocking — call from a virtual thread, never the UI thread. Worst case
     * is roughly {@code listenPerPort} times the number of non-Bluetooth ports.
     */
    public static Optional<String> findAntennaPort(Duration listenPerPort) {
        long perPortMs = Math.max(500, listenPerPort.toMillis());
        for (SerialPort port : SerialPort.getCommPorts()) {
            String description = port.getDescriptivePortName();
            if (description != null && description.toLowerCase(java.util.Locale.ROOT)
                    .contains("bluetooth")) {
                continue;
            }
            if (listenSpeaksFirmware(port, perPortMs)) {
                return Optional.of(port.getSystemPortName());
            }
        }
        return Optional.empty();
    }

    private static boolean listenSpeaksFirmware(SerialPort port, long windowMs) {
        port.setBaudRate(SerialSource.DEFAULT_BAUD);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 250, 0);
        // Best effort at a reset-free peek; see class javadoc.
        port.clearDTR();
        port.clearRTS();
        if (!port.openPort()) {
            return false;
        }
        try {
            byte[] buffer = new byte[2048];
            StringBuilder seen = new StringBuilder();
            long deadline = System.nanoTime() + windowMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                int n = port.readBytes(buffer, buffer.length);
                if (n > 0) {
                    seen.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    if (looksLikeAntennaFirmware(seen.toString())) {
                        return true;
                    }
                }
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
            }
            return false;
        } finally {
            port.closePort();
        }
    }
}
