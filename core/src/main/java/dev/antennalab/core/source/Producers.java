package dev.antennalab.core.source;

import dev.antennalab.core.domain.ReplaySource;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;

/**
 * Turns a {@link Source} description into a live {@link SampleProducer}.
 *
 * <p>This is the switch that motivated sealing {@code Source} in the first
 * place. There is no {@code default} branch: javac checks the cases cover every
 * permitted subtype, so the day a fourth source type is added, this method
 * fails to compile and names the gap. That is a materially better failure than
 * a {@code default} that throws at runtime on the one machine where the new
 * source is selected.
 */
public final class Producers {

    private Producers() {
    }

    /**
     * Open a producer for the given source.
     *
     * @throws UnsupportedOperationException for source types whose wire format
     *         has not been pinned down yet -- see the note on each case.
     */
    public static SampleProducer forSource(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        return switch (source) {
            case SyntheticSource spec -> new SyntheticProducer(spec);

            // Deliberately unimplemented. The firmware's serial format has not
            // been captured yet, and guessing at it would produce a parser that
            // looks finished and silently mis-reads real data. This throws until
            // a real capture is in hand to build against.
            case SerialSource(String port, int baud) -> throw new UnsupportedOperationException(
                    ("Serial capture on %s @ %d baud is not implemented yet: the firmware's "
                            + "output format has not been captured. Use a synthetic source until "
                            + "the parser is built against a real sample.")
                            .formatted(port, baud));

            // Same reasoning: the firmware's CSV column layout is not yet known.
            case ReplaySource(var file, var speed) -> throw new UnsupportedOperationException(
                    ("Replay of %s is not implemented yet: the firmware's CSV column layout "
                            + "has not been confirmed. Use a synthetic source until the importer "
                            + "is built against a real file.")
                            .formatted(file.getFileName()));
        };
    }
}
