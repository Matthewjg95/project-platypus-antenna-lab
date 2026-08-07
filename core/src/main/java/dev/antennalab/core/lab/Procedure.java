package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

import java.util.List;

/**
 * A reusable measurement protocol.
 *
 * <p>The reason a hobby antenna measurement is usually not trustworthy is that
 * the method lived in the builder's head and drifted between runs. A procedure is
 * the method written down, versioned, and referenced by every run that followed
 * it -- so "these two results are comparable" becomes a checkable claim rather
 * than a hope.
 *
 * <p>Procedures are library objects, not session fields: the same
 * "boresight sweep at 3 m" protocol is meant to be reused across antenna
 * revisions and across future RF projects entirely.
 *
 * @param id                 stable identifier referenced by experiments and runs.
 * @param name               short name, e.g. "Boresight A/B at fixed range".
 * @param version            bumped whenever the method changes; runs record which
 *                           version they followed, so a mid-campaign change to the
 *                           method cannot silently invalidate earlier comparisons.
 * @param purpose            what question this protocol is designed to answer.
 * @param steps              ordered instructions.
 * @param requiredEquipment  what must be on the bench, by free-text description.
 * @param minSamplesPerPath  the sample floor below which results are not quotable.
 * @param defaultDistanceMeters suggested separation; 0 when not applicable.
 * @param defaultChannel     suggested 2.4 GHz channel, or 0 for unspecified.
 */
public record Procedure(
        String id,
        String name,
        String version,
        String purpose,
        List<Step> steps,
        List<String> requiredEquipment,
        int minSamplesPerPath,
        double defaultDistanceMeters,
        int defaultChannel) {

    /**
     * One instruction in a procedure.
     *
     * @param ordinal      1-based position.
     * @param instruction  what to do.
     * @param verification how to know it was done right; empty when self-evident.
     */
    public record Step(int ordinal, String instruction, String verification) {

        public Step {
            if (ordinal < 1) {
                throw new IllegalArgumentException("step ordinal is 1-based, got " + ordinal);
            }
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("step instruction is required");
            }
            instruction = instruction.strip();
            verification = verification == null ? "" : verification.strip();
        }

        Json toJson() {
            return Json.object()
                    .put("ordinal", ordinal)
                    .put("instruction", instruction)
                    .put("verification", verification)
                    .build();
        }

        static Step fromJson(Json.Obj o) {
            return new Step(o.intValue("ordinal"), o.str("instruction"), o.strOr("verification", ""));
        }
    }

    public Procedure {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        id = id.strip();
        name = name.strip();
        version = version == null || version.isBlank() ? "1" : version.strip();
        purpose = purpose == null ? "" : purpose.strip();
        steps = List.copyOf(steps);
        requiredEquipment = List.copyOf(requiredEquipment);
        if (minSamplesPerPath < 0) {
            throw new IllegalArgumentException(
                    "minSamplesPerPath cannot be negative, got " + minSamplesPerPath);
        }
        if (defaultDistanceMeters < 0 || Double.isNaN(defaultDistanceMeters)
                || Double.isInfinite(defaultDistanceMeters)) {
            throw new IllegalArgumentException(
                    "defaultDistanceMeters must be finite and >= 0, got " + defaultDistanceMeters);
        }
        if (defaultChannel != 0 && (defaultChannel < 1 || defaultChannel > 14)) {
            throw new IllegalArgumentException(
                    "defaultChannel must be 1-14 or 0 for unspecified, got " + defaultChannel);
        }
        // Ordinals must be 1..n with no gaps: a procedure with a missing step 3 is
        // a transcription error, and silently accepting it means someone on the
        // bench skips a step without noticing.
        for (int i = 0; i < steps.size(); i++) {
            int expected = i + 1;
            if (steps.get(i).ordinal() != expected) {
                throw new IllegalArgumentException(
                        "procedure steps must be numbered 1..n in order; expected ordinal "
                                + expected + " at position " + i + " but found "
                                + steps.get(i).ordinal());
            }
        }
    }

    /** "name v3" -- how a procedure is cited in a report. */
    public String citation() {
        return "%s v%s".formatted(name, version);
    }

    /** True when the run's sample count clears this procedure's floor. */
    public boolean satisfiesSampleFloor(long samplesOnWeakestPath) {
        return samplesOnWeakestPath >= minSamplesPerPath;
    }

    public Json toJson() {
        return Json.object()
                .put("id", id)
                .put("name", name)
                .put("version", version)
                .put("purpose", purpose)
                .put("steps", Json.array(steps.stream().map(Step::toJson).toList()))
                .put("requiredEquipment",
                        Json.array(requiredEquipment.stream().map(Json::of).toList()))
                .put("minSamplesPerPath", minSamplesPerPath)
                .put("defaultDistanceMeters", defaultDistanceMeters)
                .put("defaultChannel", defaultChannel)
                .build();
    }

    public static Procedure fromJson(Json.Obj o) {
        return new Procedure(
                o.str("id"),
                o.str("name"),
                o.strOr("version", "1"),
                o.strOr("purpose", ""),
                o.arrOrEmpty("steps").stream()
                        .map(j -> Step.fromJson((Json.Obj) j))
                        .toList(),
                o.arrOrEmpty("requiredEquipment").stream()
                        .map(j -> ((Json.Str) j).value())
                        .toList(),
                (int) o.numOr("minSamplesPerPath", 0),
                o.numOr("defaultDistanceMeters", 0),
                (int) o.numOr("defaultChannel", 0));
    }
}
