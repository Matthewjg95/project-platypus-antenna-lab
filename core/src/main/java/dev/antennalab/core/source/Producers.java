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

            // Implemented against the real capture of 2026-08-08 (see
            // Tab5LogParser and its fixture). The long-standing deliberate
            // UnsupportedOperationException is finally gone.
            case SerialSource spec -> new SerialProducer(spec);

            // Same reasoning: the firmware's CSV column layout is not yet known.
            case ReplaySource(var file, var speed) -> throw new UnsupportedOperationException(
                    ("Replay of %s is not implemented yet: the firmware's CSV column layout "
                            + "has not been confirmed. Use a synthetic source until the importer "
                            + "is built against a real file.")
                            .formatted(file.getFileName()));
        };
    }
}
