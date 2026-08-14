package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * One tickable box on an experiment.
 *
 * <p>Milestones are <b>experiment state</b>, not procedure content. The
 * procedure is the shared, versioned method — "how do you measure this, in
 * general" — and tick state on a shared object would mean checking a box on one
 * experiment checks it on every experiment citing that procedure. So the
 * template lives with the method and the ticks live here, copied onto the
 * experiment at creation and owned by it from then on.
 *
 * <p>They serve two purposes, in the operator's own words: a <b>resume
 * point</b> after time away — the unchecked box is the to-do — and a
 * <b>confidence gate</b>: when every box is ticked, the answer to the question
 * has earned trust.
 *
 * @param label what has to be true, phrased so done/not-done is checkable.
 * @param done  whether the operator (or an attached run) has attested to it.
 */
public record Milestone(String label, boolean done) {

    public Milestone {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("a milestone needs a label");
        }
        label = label.strip();
    }

    /** The same milestone, ticked or unticked. */
    public Milestone withDone(boolean value) {
        return value == done ? this : new Milestone(label, value);
    }

    /**
     * Derive the milestone template from a procedure's steps.
     *
     * <p>Each step's {@code verification} is already "how you know this was
     * done right" — which is precisely a checklist item. Steps whose
     * verification is empty fall back to the instruction itself. The procedure
     * needs no new field: the template is a reading of what it already says.
     */
    public static List<Milestone> templateFrom(Procedure procedure) {
        if (procedure == null) {
            return List.of();
        }
        List<Milestone> out = new ArrayList<>();
        for (Procedure.Step step : procedure.steps()) {
            String label = step.verification().isEmpty()
                    ? step.instruction()
                    : step.verification();
            out.add(new Milestone(label, false));
        }
        return List.copyOf(out);
    }

    public Json toJson() {
        return Json.object()
                .put("label", label)
                .put("done", done)
                .build();
    }

    public static Milestone fromJson(Json.Obj o) {
        return new Milestone(o.str("label"), o.boolOr("done", false));
    }
}
