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
     * Build an experiment's checklist: one milestone per device under test,
     * plus the conclusion.
     *
     * <p>A first version mined the procedure's step verifications instead. On
     * the bench that produced boxes describing the <em>method's hygiene</em>
     * ("median of 10, not a single reading") — several of which did not even
     * apply to an automated run — when what the operator actually resumes
     * against is the <em>question's progress</em>: which antennas have been
     * through the test, and has an answer been written. Matt's formulation.
     * The method's own hygiene stays visible as the procedure's reference
     * steps; it does not need tick boxes to be read.
     *
     * @param procedure the method the runs will follow; may be null.
     * @param duts      the devices under test, in presentation order.
     */
    public static List<Milestone> templateFor(Procedure procedure, List<Dut> duts) {
        List<Milestone> out = new ArrayList<>();
        String method = procedure == null ? "the procedure" : procedure.name();
        if (duts != null) {
            for (Dut dut : duts) {
                out.add(new Milestone("Run " + method + " against " + dut.name(), false));
            }
        }
        out.add(new Milestone("Conclusion recorded against the question", false));
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
