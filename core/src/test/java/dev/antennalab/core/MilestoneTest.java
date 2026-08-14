package dev.antennalab.core;

import dev.antennalab.core.json.Json;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.Milestone;
import dev.antennalab.core.lab.PlatypusCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestones: the experiment-owned checklist.
 *
 * <p>The property that matters most here is separation -- ticks belong to one
 * experiment and must never leak through the shared procedure they were copied
 * from, and must survive the JSON round trip, because a resume point that lives
 * only in memory is not a resume point.
 */
class MilestoneTest {

    private static final Instant T0 = Instant.parse("2026-08-14T09:00:00Z");

    @Test
    @DisplayName("the template is one box per DUT plus the conclusion — progress, not method hygiene")
    void templateIsPerDutPlusConclusion() {
        var procedure = PlatypusCatalog.pairedComparisonProcedure();
        var duts = List.of(PlatypusCatalog.designA(), PlatypusCatalog.designB(),
                PlatypusCatalog.designC());

        List<Milestone> template = Milestone.templateFor(procedure, duts);

        // One box per antenna under test, then the answer. A first version
        // mined the procedure's step verifications instead, and on the bench
        // several of those boxes did not even apply to an automated run.
        assertEquals(duts.size() + 1, template.size());
        assertTrue(template.stream().noneMatch(Milestone::done),
                "a fresh template starts fully unticked");
        for (int i = 0; i < duts.size(); i++) {
            assertTrue(template.get(i).label().contains(duts.get(i).name()),
                    "each DUT gets a named box: " + template.get(i).label());
            assertTrue(template.get(i).label().contains(procedure.name()),
                    "the box names the method the run must follow");
        }
        assertEquals("Conclusion recorded against the question",
                template.get(template.size() - 1).label());
    }

    @Test
    @DisplayName("no procedure and no DUTs still yields the conclusion box, not an empty list")
    void degenerateTemplateStillHasTheConclusion() {
        List<Milestone> template = Milestone.templateFor(null, List.of());
        assertEquals(1, template.size());
        assertTrue(template.get(0).label().contains("Conclusion"));
    }

    @Test
    @DisplayName("ticks are copied state: two experiments from one procedure do not share them")
    void ticksDoNotLeakBetweenExperiments() {
        var procedure = PlatypusCatalog.pairedComparisonProcedure();
        var a = Experiment.plan("exp-a", "A", "Q?", procedure.id(), List.of(), T0)
                .withMilestones(Milestone.templateFor(procedure, List.of(PlatypusCatalog.designA())), T0);
        var b = Experiment.plan("exp-b", "B", "Q?", procedure.id(), List.of(), T0)
                .withMilestones(Milestone.templateFor(procedure, List.of(PlatypusCatalog.designA())), T0);

        var aTicked = a.withMilestoneDone(0, true, T0);

        // The whole reason milestones moved off the procedure.
        assertTrue(aTicked.milestones().get(0).done());
        assertFalse(b.milestones().get(0).done(),
                "ticking a box on one experiment must not tick it on another");
        assertFalse(a.milestones().get(0).done(),
                "the original record is immutable; the tick is a new value");
    }

    @Test
    @DisplayName("ticks survive the JSON round trip -- the resume point is durable")
    void ticksSurviveSerialisation() {
        var procedure = PlatypusCatalog.pairedComparisonProcedure();
        var experiment = Experiment
                .plan("exp-rt", "Round trip", "Q?", procedure.id(), List.of(), T0)
                .withMilestones(Milestone.templateFor(procedure, List.of(PlatypusCatalog.designA())), T0)
                .withMilestoneDone(1, true, T0);

        var reread = Experiment.fromJson(
                (Json.Obj) Json.parse(Json.write(experiment.toJson())));

        assertEquals(experiment.milestones(), reread.milestones());
        assertFalse(reread.milestones().get(0).done());
        assertTrue(reread.milestones().get(1).done());
    }

    @Test
    @DisplayName("a library file written before milestones existed still opens")
    void oldFilesReadAsEmptyMilestones() {
        // The exact shape toJson produced before the milestones member existed.
        String old = """
                {"id":"exp-old","title":"Old","question":"Q?","procedureId":"",
                 "dutIds":[],"runIds":[],"status":"PLANNED","conclusion":"",
                 "createdAt":"2026-08-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}""";

        var experiment = Experiment.fromJson((Json.Obj) Json.parse(old));

        assertTrue(experiment.milestones().isEmpty());
        assertFalse(experiment.milestonesComplete(),
                "no milestones is not the same as all milestones ticked");
    }

    @Test
    @DisplayName("the confidence gate opens only when every box is ticked")
    void completionRequiresEveryBox() {
        var experiment = Experiment.plan("exp-c", "C", "Q?", "", List.of(), T0)
                .withMilestones(List.of(
                        new Milestone("antenna mounted", false),
                        new Milestone("baseline captured", false)), T0);

        assertFalse(experiment.milestonesComplete());
        assertFalse(experiment.withMilestoneDone(0, true, T0).milestonesComplete(),
                "one of two is not confidence");
        assertTrue(experiment.withMilestoneDone(0, true, T0)
                .withMilestoneDone(1, true, T0).milestonesComplete());
    }
}
