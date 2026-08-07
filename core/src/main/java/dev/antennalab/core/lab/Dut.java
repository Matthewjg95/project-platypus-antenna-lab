package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

/**
 * A device under test: something you can put on the end of the RF path and
 * measure.
 *
 * <p>DUTs live in the {@link LabLibrary}, not in a session. That is the whole
 * point of the experiment-manager model: "Platypus Rev 7.13 Design C" is a thing
 * that exists across projects and across years, and a measurement refers to it by
 * id. Copying its parameters into every session would mean that correcting a
 * transcription error later fixes exactly one run.
 *
 * <p>Sealed so the library reader cannot silently accept a DUT kind it does not
 * understand, and so report code can switch over kinds exhaustively.
 */
public sealed interface Dut {

    /**
     * A microstrip patch antenna, the thing this project actually builds.
     *
     * @param id           stable identifier, referenced by experiments and runs.
     * @param name         human name, e.g. "Platypus Design C".
     * @param revision     board revision, e.g. "7.13.1".
     * @param designLabel  the letter silkscreened on the panel: "A", "B", "C".
     * @param feed         matching structure -- the real difference between designs.
     * @param connector    RF connector part, e.g. "MMCX 135-3711-001".
     * @param notes        anything else worth recording.
     */
    record PatchAntenna(
            String id,
            String name,
            String revision,
            String designLabel,
            FeedDesign feed,
            String connector,
            String notes) implements Dut {

        public PatchAntenna {
            id = requireId(id);
            name = requireText(name, "name");
            revision = nullToEmpty(revision);
            designLabel = nullToEmpty(designLabel);
            connector = nullToEmpty(connector);
            notes = nullToEmpty(notes);
            if (feed == null) {
                throw new IllegalArgumentException("feed is required for a patch antenna");
            }
        }

        @Override
        public String summary() {
            String rev = revision.isEmpty() ? "" : " Rev " + revision;
            return "%s%s -- %s".formatted(name, rev, feed.summary());
        }
    }

    /**
     * An antenna integrated into a module, used as the reference path.
     *
     * <p>The ESP32-C6-MINI-1U's on-module chip antenna is this. It has no feed
     * geometry we control, which is exactly why it does not get a
     * {@link FeedDesign} -- forcing one would be inventing data.
     */
    record ModuleAntenna(
            String id,
            String name,
            String partNumber,
            String notes) implements Dut {

        public ModuleAntenna {
            id = requireId(id);
            name = requireText(name, "name");
            partNumber = nullToEmpty(partNumber);
            notes = nullToEmpty(notes);
        }

        @Override
        public String summary() {
            return partNumber.isEmpty() ? name : "%s (%s)".formatted(name, partNumber);
        }
    }

    /**
     * Anything else on the bench: a filter, an amplifier, a cable, a future
     * antenna type.
     *
     * <p>Deliberately loose. The library has to stay useful for RF projects that
     * do not exist yet, and the alternative to an escape hatch is people writing
     * their real data into a notes field on the wrong type.
     */
    record GenericDut(
            String id,
            String name,
            String category,
            String notes) implements Dut {

        public GenericDut {
            id = requireId(id);
            name = requireText(name, "name");
            category = nullToEmpty(category);
            notes = nullToEmpty(notes);
        }

        @Override
        public String summary() {
            return category.isEmpty() ? name : "%s [%s]".formatted(name, category);
        }
    }

    /** Stable identifier referenced by experiments and runs. */
    String id();

    /** Human-readable name. */
    String name();

    /** Free-text notes. */
    String notes();

    /** One-line description for pickers, legends and reports. */
    String summary();

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /** Serialise, tagging the kind. Exhaustive over the sealed hierarchy. */
    default Json toJson() {
        return switch (this) {
            case PatchAntenna p -> Json.object()
                    .put("kind", "patchAntenna")
                    .put("id", p.id())
                    .put("name", p.name())
                    .put("revision", p.revision())
                    .put("designLabel", p.designLabel())
                    .put("feed", p.feed().toJson())
                    .put("connector", p.connector())
                    .put("notes", p.notes())
                    .build();
            case ModuleAntenna m -> Json.object()
                    .put("kind", "moduleAntenna")
                    .put("id", m.id())
                    .put("name", m.name())
                    .put("partNumber", m.partNumber())
                    .put("notes", m.notes())
                    .build();
            case GenericDut g -> Json.object()
                    .put("kind", "genericDut")
                    .put("id", g.id())
                    .put("name", g.name())
                    .put("category", g.category())
                    .put("notes", g.notes())
                    .build();
        };
    }

    /** Read a DUT back, rejecting unknown kinds rather than degrading them. */
    static Dut fromJson(Json.Obj o) {
        String kind = o.str("kind");
        return switch (kind) {
            case "patchAntenna" -> new PatchAntenna(
                    o.str("id"),
                    o.str("name"),
                    o.strOr("revision", ""),
                    o.strOr("designLabel", ""),
                    FeedDesign.fromJson(o.obj("feed")),
                    o.strOr("connector", ""),
                    o.strOr("notes", ""));
            case "moduleAntenna" -> new ModuleAntenna(
                    o.str("id"),
                    o.str("name"),
                    o.strOr("partNumber", ""),
                    o.strOr("notes", ""));
            case "genericDut" -> new GenericDut(
                    o.str("id"),
                    o.str("name"),
                    o.strOr("category", ""),
                    o.strOr("notes", ""));
            default -> throw new Json.JsonException(
                    "unknown DUT kind '" + kind + "'. This library file may have been written "
                            + "by a newer version of Antenna Lab.");
        };
    }
}
