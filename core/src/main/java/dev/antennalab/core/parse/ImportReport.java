package dev.antennalab.core.parse;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;

import java.util.List;

/**
 * What an import actually did.
 *
 * <p>An importer that returns only the samples it managed to read is telling you
 * half the story, and it is the half that never causes an argument. If 40% of a
 * capture was dropped because a column moved, the number that matters is the 40%
 * -- and it has to arrive with the data, not in a log file nobody opens.
 *
 * <p>Every rejected row is reported individually with its source line, so a
 * problem can be found in a text editor rather than guessed at.
 *
 * @param samples          rows that became samples, in file order.
 * @param rejections       rows that did not, each with a line number and reason.
 * @param unmappedColumns  headers the mapping did not consume. Not an error --
 *                         often theta/phi/note -- but worth surfacing, since an
 *                         unexpected name here usually means a mis-set mapping.
 * @param warnings         things that succeeded but deserve a second look.
 */
public record ImportReport(
        List<RssiSample> samples,
        List<Rejection> rejections,
        List<String> unmappedColumns,
        List<String> warnings) {

    /**
     * A row that could not be turned into a sample.
     *
     * @param lineNumber source line, 1-based.
     * @param reason     what was wrong, in words an operator can act on.
     * @param raw        the row as read, truncated for display.
     */
    public record Rejection(int lineNumber, String reason, String raw) {
    }

    public ImportReport {
        samples = List.copyOf(samples);
        rejections = List.copyOf(rejections);
        unmappedColumns = List.copyOf(unmappedColumns);
        warnings = List.copyOf(warnings);
    }

    /** Rows encountered, whether or not they survived. */
    public int rowsRead() {
        return samples.size() + rejections.size();
    }

    /** Fraction of rows that became samples, 0..1. One when the file was empty. */
    public double acceptanceRate() {
        int total = rowsRead();
        return total == 0 ? 1.0 : (double) samples.size() / total;
    }

    /** True when nothing was rejected. */
    public boolean isClean() {
        return rejections.isEmpty();
    }

    /** Samples on one antenna path. */
    public long countFor(AntennaPath path) {
        return samples.stream().filter(s -> s.antenna() == path).count();
    }

    /**
     * One-line summary for the status bar.
     *
     * <p>Leads with the rejection count when there is one. A summary that reads
     * "imported 1,200 samples" when 800 rows were dropped is technically true and
     * practically a lie.
     */
    public String summary() {
        if (rejections.isEmpty()) {
            return "Imported %,d samples (%,d chip / %,d external)".formatted(
                    samples.size(), countFor(AntennaPath.CHIP), countFor(AntennaPath.EXTERNAL));
        }
        return "Imported %,d of %,d rows -- %,d rejected (%.0f%% accepted)".formatted(
                samples.size(), rowsRead(), rejections.size(), acceptanceRate() * 100);
    }

    /** The first few rejections, for a dialog that should not scroll forever. */
    public List<Rejection> firstRejections(int limit) {
        return rejections.stream().limit(Math.max(0, limit)).toList();
    }
}
