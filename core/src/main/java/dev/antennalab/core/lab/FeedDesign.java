package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

/**
 * How a patch antenna's feed line is matched to the radiating element.
 *
 * <p>This is the actual structural difference between the Project Platypus
 * designs on the Rev 7.13 panel: A and B are inset-fed with different inset
 * depths, C uses a quarter-wave transformer. Capturing it as a sealed hierarchy
 * rather than a free-text "notes" field is what makes the question "does the
 * quarter-wave transformer beat the matched inset feed?" answerable by the
 * software instead of by memory.
 *
 * <p>Subtypes are nested because they are meaningless apart from this interface
 * -- unlike {@code Source}, whose variants are used independently across the app.
 */
public sealed interface FeedDesign {

    /**
     * Inset (recessed) microstrip feed.
     *
     * <p>Moving the feed point into the patch trades the very high edge impedance
     * down toward the line impedance; how far in you go sets the match. Designs A
     * and B differ only in this depth, which is what makes them a clean pair.
     *
     * @param insetY0Mm            inset depth from the patch edge.
     * @param slotWidthMm          width of the gap either side of the feed line.
     * @param inputImpedanceOhms   design input impedance at the feed point.
     * @param intentionallyMatched    true for a design targeting 50 ohm, false for a
     *                             deliberate mismatch kept as a control.
     */
    record InsetFeed(
            double insetY0Mm,
            double slotWidthMm,
            double inputImpedanceOhms,
            boolean intentionallyMatched) implements FeedDesign {

        public InsetFeed {
            requirePositive(insetY0Mm, "insetY0Mm");
            requirePositive(slotWidthMm, "slotWidthMm");
            requirePositive(inputImpedanceOhms, "inputImpedanceOhms");
        }

        @Override
        public String summary() {
            return "Inset feed y0=%.2fmm slot=%.1fmm Rin=%.0f ohm (%s)".formatted(
                    insetY0Mm, slotWidthMm, inputImpedanceOhms,
                    intentionallyMatched ? "matched" : "deliberate mismatch");
        }
    }

    /**
     * Quarter-wave transformer feed.
     *
     * <p>A lambda/4 section of line at the geometric mean of the two impedances,
     * transforming the patch edge impedance to the feed line. Narrower band than
     * an inset feed in general, which is one of the things a bench comparison is
     * for.
     *
     * @param transformerImpedanceOhms characteristic impedance of the transformer section.
     * @param widthMm                  transformer trace width.
     * @param lengthMm                 transformer length (nominally a quarter wavelength).
     */
    record QuarterWaveTransformer(
            double transformerImpedanceOhms,
            double widthMm,
            double lengthMm) implements FeedDesign {

        public QuarterWaveTransformer {
            requirePositive(transformerImpedanceOhms, "transformerImpedanceOhms");
            requirePositive(widthMm, "widthMm");
            requirePositive(lengthMm, "lengthMm");
        }

        @Override
        public String summary() {
            return "Quarter-wave transformer Z1=%.0f ohm w=%.3fmm L=%.1fmm".formatted(
                    transformerImpedanceOhms, widthMm, lengthMm);
        }
    }

    /**
     * Directly fed, with no matching structure.
     *
     * <p>Present so reference antennas and future non-patch devices fit the model
     * without forcing a fake inset depth on them.
     */
    record DirectFeed(double inputImpedanceOhms) implements FeedDesign {

        public DirectFeed {
            requirePositive(inputImpedanceOhms, "inputImpedanceOhms");
        }

        @Override
        public String summary() {
            return "Direct feed Rin=%.0f ohm".formatted(inputImpedanceOhms);
        }
    }

    /** One-line description for reports, chart legends and the DUT picker. */
    String summary();

    private static void requirePositive(double value, String field) {
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(field + " must be finite and > 0, got " + value);
        }
    }

    /**
     * Serialise, tagging the variant so it can be read back into the right record.
     *
     * <p>Exhaustive switch: adding a fourth feed design breaks this at compile
     * time, which is the point. A library file written by a newer version and read
     * by an older one fails loudly in {@link #fromJson} rather than silently
     * dropping the geometry.
     */
    default Json toJson() {
        return switch (this) {
            case InsetFeed(double y0, double slot, double rin, boolean matched) -> Json.object()
                    .put("type", "inset")
                    .put("insetY0Mm", y0)
                    .put("slotWidthMm", slot)
                    .put("inputImpedanceOhms", rin)
                    .put("intentionallyMatched", matched)
                    .build();
            case QuarterWaveTransformer(double z1, double w, double l) -> Json.object()
                    .put("type", "quarterWave")
                    .put("transformerImpedanceOhms", z1)
                    .put("widthMm", w)
                    .put("lengthMm", l)
                    .build();
            case DirectFeed(double rin) -> Json.object()
                    .put("type", "direct")
                    .put("inputImpedanceOhms", rin)
                    .build();
        };
    }

    /** Read a feed design back, rejecting unknown variants rather than guessing. */
    static FeedDesign fromJson(Json.Obj o) {
        String type = o.str("type");
        return switch (type) {
            case "inset" -> new InsetFeed(
                    o.num("insetY0Mm"),
                    o.num("slotWidthMm"),
                    o.num("inputImpedanceOhms"),
                    o.get("intentionallyMatched")
                            .map(v -> v instanceof Json.Bool b && b.value())
                            .orElse(true));
            case "quarterWave" -> new QuarterWaveTransformer(
                    o.num("transformerImpedanceOhms"),
                    o.num("widthMm"),
                    o.num("lengthMm"));
            case "direct" -> new DirectFeed(o.num("inputImpedanceOhms"));
            default -> throw new Json.JsonException(
                    "unknown feed design type '" + type + "'. This library file may have been "
                            + "written by a newer version of Antenna Lab.");
        };
    }
}
