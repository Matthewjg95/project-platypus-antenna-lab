package dev.antennalab.core.domain;

/**
 * Where samples come from.
 *
 * <p><b>This is a description, not a connection.</b> A {@code Source} is an inert
 * value object saying "COM7 at 115200 baud" or "replay this CSV at 4x"; opening
 * it, reading it and closing it is the job of the capture pipeline. Keeping the
 * two apart is what lets a {@code Source} be recorded into a session file, shown
 * in the UI, embedded in a report's metadata block, and compared for equality --
 * none of which you can do with a live handle to a serial port.
 *
 * <p><b>Why sealed.</b> The pipeline has to decide how to open each kind of
 * source, and that decision is a {@code switch} over this type. Sealing it means
 * javac proves the switch is exhaustive: adding a fourth source (a TCP bridge, a
 * second board) turns every such switch into a compile error listing exactly what
 * still needs handling. The alternative -- an abstract method on the interface --
 * would drag file I/O and jSerialComm into the domain model, which is precisely
 * what keeps {@code core} headless and testable.
 *
 * @see SerialSource
 * @see ReplaySource
 * @see SyntheticSource
 */
public sealed interface Source permits SerialSource, ReplaySource, SyntheticSource {

    /** Short label for chart legends, window titles and the status bar. */
    String displayName();

    /**
     * Whether this source involves physical hardware.
     *
     * <p>Reports need to state this plainly: a headline "+12.5 dB" measured from
     * a synthetic source is a demo, not evidence, and the report generator marks
     * it as such rather than letting the two look alike.
     */
    boolean isLiveHardware();

    /**
     * One-line human summary, built by pattern matching over the sealed
     * hierarchy.
     *
     * <p>Record deconstruction patterns let each case pull out exactly the fields
     * it wants to talk about, and because {@code Source} is sealed there is no
     * {@code default} branch -- the compiler checks the switch covers every
     * permitted subtype. This is the Java 26-era idiom doing real work: the
     * moment a new source type is permitted, this method stops compiling.
     */
    static String summarise(Source source) {
        return switch (source) {
            case SerialSource(String port, int baud) ->
                    "Serial %s @ %d baud".formatted(port, baud);
            case ReplaySource(var file, double speed) ->
                    "Replay %s at %.2fx".formatted(file.getFileName(), speed);
            case SyntheticSource s ->
                    "Synthetic (seed %d, %+.1f dB modelled gain)".formatted(s.seed(), s.externalGainDb());
        };
    }
}
