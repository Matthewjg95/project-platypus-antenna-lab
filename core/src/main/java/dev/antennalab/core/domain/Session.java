package dev.antennalab.core.domain;

import java.util.List;
import java.util.UUID;

/**
 * One complete capture run: where it came from, what conditions it was taken
 * under, and every sample in it.
 *
 * <p>This is the unit the A/B view compares, the session store persists and the
 * report generator renders. A CSV imported from the firmware and a run recorded
 * live in the app both land here, which is what lets them be compared on equal
 * terms.
 *
 * @param id       stable identity, so two sessions with identical contents are
 *                 still distinguishable in the A/B view.
 * @param source   where the samples came from -- carried along so a report can
 *                 state plainly whether it is showing measured or modelled data.
 * @param metadata the test conditions.
 * @param samples  every sample, in capture order. Defensively copied.
 */
public record Session(String id, Source source, SessionMetadata metadata, List<RssiSample> samples) {

    public Session {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata is required");
        }
        // List.copyOf both null-checks and freezes the list, so a Session handed
        // to the report generator on one thread cannot be mutated by the capture
        // thread underneath it. Cheap immutability at the boundary.
        samples = List.copyOf(samples);
    }

    /** A new empty session with a generated id. */
    public static Session empty(Source source, SessionMetadata metadata) {
        return new Session(UUID.randomUUID().toString(), source, metadata, List.of());
    }

    /** A new session carrying the supplied samples, with a generated id. */
    public static Session of(Source source, SessionMetadata metadata, List<RssiSample> samples) {
        return new Session(UUID.randomUUID().toString(), source, metadata, samples);
    }

    /** Just the samples taken on one antenna path. */
    public List<RssiSample> samplesFor(AntennaPath path) {
        return samples.stream().filter(s -> s.antenna() == path).toList();
    }

    /** How many samples this run holds on the given path. */
    public long countFor(AntennaPath path) {
        return samples.stream().filter(s -> s.antenna() == path).count();
    }

    /** True when there is at least one sample on both paths, i.e. a delta is computable. */
    public boolean hasBothPaths() {
        return countFor(AntennaPath.CHIP) > 0 && countFor(AntennaPath.EXTERNAL) > 0;
    }

    /** Whether this run's numbers came off real hardware. */
    public boolean isMeasured() {
        return source.isLiveHardware();
    }

    /** Same session, different sample list -- used while recording. */
    public Session withSamples(List<RssiSample> newSamples) {
        return new Session(id, source, metadata, newSamples);
    }

    /** Same session, edited conditions -- used when the operator fills in the form. */
    public Session withMetadata(SessionMetadata newMetadata) {
        return new Session(id, source, newMetadata, samples);
    }
}
