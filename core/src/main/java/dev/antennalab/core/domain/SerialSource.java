package dev.antennalab.core.domain;

/**
 * A live USB serial connection to the board.
 *
 * <p><b>Unverified defaults.</b> {@link #DEFAULT_BAUD} is the ESP32 family's
 * usual console rate, not a value read from the actual firmware. It is a
 * placeholder until the real capture is in hand; the wire format itself is
 * deliberately unimplemented for the same reason. Nothing in this project
 * guesses at the protocol.
 *
 * @param portName OS port identifier, e.g. {@code COM7} on Windows.
 * @param baudRate line rate in bits per second.
 */
public record SerialSource(String portName, int baudRate) implements Source {

    /** ESP32 console default. Must be confirmed against the real firmware. */
    public static final int DEFAULT_BAUD = 115_200;

    public SerialSource {
        if (portName == null || portName.isBlank()) {
            throw new IllegalArgumentException("portName is required");
        }
        if (baudRate <= 0) {
            throw new IllegalArgumentException("baudRate must be positive, got " + baudRate);
        }
    }

    /** Convenience for the common case of connecting at the default rate. */
    public static SerialSource onPort(String portName) {
        return new SerialSource(portName, DEFAULT_BAUD);
    }

    @Override
    public String displayName() {
        return portName;
    }

    @Override
    public boolean isLiveHardware() {
        return true;
    }
}
