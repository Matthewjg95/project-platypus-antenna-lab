package dev.antennalab.core;

import dev.antennalab.core.json.Json;
import dev.antennalab.core.lab.Dut;
import dev.antennalab.core.lab.Experiment;
import dev.antennalab.core.lab.FeedDesign;
import dev.antennalab.core.lab.LabLibrary;
import dev.antennalab.core.lab.PlatypusCatalog;
import dev.antennalab.core.lab.Procedure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The experiment library: the part of the app meant to outlive the project.
 *
 * <p>These tests care mostly about durability and round-tripping. A statistics
 * bug produces a wrong number you can recompute; a library bug loses the record
 * of what was built and why, which nothing recovers.
 */
class LabLibraryTest {

    private static final Instant T0 = Instant.parse("2026-08-07T10:00:00Z");

    @Test
    @DisplayName("the three real Platypus designs round-trip through disk intact")
    void seededCatalogSurvivesSaveAndReload(@TempDir Path dir) {
        LabLibrary library = LabLibrary.openOrCreate(dir);
        PlatypusCatalog.seed(library);
        library.put(PlatypusCatalog.headlineExperiment(T0));
        library.save();

        LabLibrary reopened = LabLibrary.openOrCreate(dir);

        assertEquals(4, reopened.duts().size());
        assertEquals(1, reopened.procedures().size());
        assertEquals(1, reopened.experiments().size());

        // Design C is the quarter-wave variant; its geometry must survive exactly.
        Dut c = reopened.dut(PlatypusCatalog.DESIGN_C_ID).orElseThrow();
        Dut.PatchAntenna patch = assertInstanceOf(Dut.PatchAntenna.class, c);
        assertEquals("C", patch.designLabel());
        assertEquals("7.13.1", patch.revision());

        FeedDesign.QuarterWaveTransformer feed =
                assertInstanceOf(FeedDesign.QuarterWaveTransformer.class, patch.feed());
        assertEquals(100.0, feed.transformerImpedanceOhms(), 1e-9);
        assertEquals(0.709, feed.widthMm(), 1e-9);
        // 17.98, not the 18.0 the silkscreen rounds it to. The KiCad README's
        // design math is the authority for these values.
        assertEquals(17.98, feed.lengthMm(), 1e-9);
        assertEquals("MMCX 135-3711-801", patch.connector());
    }

    @Test
    @DisplayName("both inset designs share the 6.3 mm Salmony slot width")
    void insetSlotWidthFollowsSalmony() {
        // Clearance each side of the 3.1 mm feed must be at least the 1.6 mm
        // substrate height: 3.1 + 2*1.6 = 6.3. Designs A and B differ in inset
        // DEPTH, not slot width -- which is what makes them a controlled pair.
        FeedDesign.InsetFeed a = assertInstanceOf(FeedDesign.InsetFeed.class,
                ((Dut.PatchAntenna) PlatypusCatalog.designA()).feed());
        FeedDesign.InsetFeed b = assertInstanceOf(FeedDesign.InsetFeed.class,
                ((Dut.PatchAntenna) PlatypusCatalog.designB()).feed());

        assertEquals(6.3, a.slotWidthMm(), 1e-9);
        assertEquals(6.3, b.slotWidthMm(), 1e-9);
        assertEquals(9.81, a.insetY0Mm(), 1e-9);
        assertEquals(7.50, b.insetY0Mm(), 1e-9);
    }

    @Test
    @DisplayName("the deliberate mismatch on Design B is preserved, not normalised away")
    void deliberateMismatchIsPreserved(@TempDir Path dir) {
        LabLibrary library = LabLibrary.openOrCreate(dir);
        PlatypusCatalog.seed(library);
        library.save();

        Dut.PatchAntenna b = assertInstanceOf(Dut.PatchAntenna.class,
                LabLibrary.openOrCreate(dir).dut(PlatypusCatalog.DESIGN_B_ID).orElseThrow());
        FeedDesign.InsetFeed feed = assertInstanceOf(FeedDesign.InsetFeed.class, b.feed());

        // B exists to underperform. If a reload quietly flipped this to "matched"
        // the control would look like a failed design instead of a working one.
        assertFalse(feed.intendedlyMatched());
        assertEquals(97.0, feed.inputImpedanceOhms(), 1e-9);
    }

    @Test
    @DisplayName("the library file is human-readable and stably ordered")
    void filesAreReadableAndStable(@TempDir Path dir) throws IOException {
        LabLibrary library = LabLibrary.openOrCreate(dir);
        PlatypusCatalog.seed(library);
        library.save();
        String first = Files.readString(dir.resolve("duts.json"), StandardCharsets.UTF_8);

        // Saving again with no changes must produce byte-identical output, or the
        // library churns git history every time the app is opened.
        LabLibrary.openOrCreate(dir).save();
        String second = Files.readString(dir.resolve("duts.json"), StandardCharsets.UTF_8);

        assertEquals(first, second, "repeated saves must be byte-identical");
        assertTrue(first.contains("\n"), "library files should be pretty-printed");
        assertTrue(first.contains("schemaVersion"));
        assertTrue(first.contains("quarterWave"));
    }

    @Test
    @DisplayName("an empty directory opens as an empty library rather than failing")
    void emptyDirectoryIsFine(@TempDir Path dir) {
        LabLibrary library = LabLibrary.openOrCreate(dir.resolve("does-not-exist-yet"));

        assertTrue(library.duts().isEmpty());
        assertEquals("0 DUTs, 0 procedures, 0 experiments", library.summary());
    }

    @Test
    @DisplayName("a corrupt library file fails loudly instead of starting empty")
    void corruptFileIsFatal(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("duts.json"), "{ this is not json", StandardCharsets.UTF_8);

        // Starting from empty would look exactly like data loss to the user, and
        // the next save would make it real.
        assertThrows(Json.JsonException.class, () -> LabLibrary.openOrCreate(dir));
    }

    @Test
    @DisplayName("a newer schema version is refused rather than misread")
    void futureSchemaIsRefused(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("duts.json"),
                "{\"schemaVersion\": 99, \"duts\": []}", StandardCharsets.UTF_8);

        var e = assertThrows(Json.JsonException.class, () -> LabLibrary.openOrCreate(dir));
        assertTrue(e.getMessage().contains("99"));
    }

    @Test
    @DisplayName("an unknown DUT kind is refused with an actionable message")
    void unknownDutKindIsRefused(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("duts.json"),
                "{\"schemaVersion\":1,\"duts\":[{\"kind\":\"horn\",\"id\":\"h1\",\"name\":\"Horn\"}]}",
                StandardCharsets.UTF_8);

        var e = assertThrows(Json.JsonException.class, () -> LabLibrary.openOrCreate(dir));
        assertTrue(e.getMessage().contains("horn"));
        assertTrue(e.getMessage().contains("newer version"));
    }

    @Test
    @DisplayName("dangling references are reported, not thrown, so they can be fixed")
    void validateReportsDanglingReferences(@TempDir Path dir) {
        LabLibrary library = LabLibrary.openOrCreate(dir);
        library.put(PlatypusCatalog.chipAntenna());

        Experiment experiment = Experiment.plan("e1", "Test", "Does it work?",
                "no-such-procedure", List.of(PlatypusCatalog.CHIP_ANTENNA_ID, "no-such-dut"), T0);

        List<String> problems = library.validate(experiment);

        assertEquals(2, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("no-such-procedure")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("no-such-dut")));
        // The valid reference must not be reported.
        assertTrue(problems.stream().noneMatch(p -> p.contains(PlatypusCatalog.CHIP_ANTENNA_ID)));
    }

    @Test
    @DisplayName("procedure steps must be numbered 1..n with no gaps")
    void procedureStepNumberingIsChecked() {
        // A skipped ordinal usually means a step was deleted from the middle, and
        // someone on the bench will follow the list without noticing.
        assertThrows(IllegalArgumentException.class, () -> new Procedure(
                "p", "Broken", "1", "",
                List.of(new Procedure.Step(1, "first", ""),
                        new Procedure.Step(3, "third", "")),
                List.of(), 0, 0, 0));
    }

    @Test
    @DisplayName("the seeded procedure carries a sample floor and cites its version")
    void seededProcedureIsUsable() {
        Procedure p = PlatypusCatalog.pairedComparisonProcedure();

        assertEquals("Over-the-air static comparison vs baseline v1.0", p.citation());
        assertEquals(100, p.minSamplesPerPath());
        assertFalse(p.satisfiesSampleFloor(99));
        assertTrue(p.satisfiesSampleFloor(100));
        assertEquals(7, p.steps().size());
    }

    @Test
    @DisplayName("the closing-baseline step is present -- it is what voids a drifted run")
    void procedureClosesWithBaselineRecheck() {
        Procedure p = PlatypusCatalog.pairedComparisonProcedure();
        Procedure.Step last = p.steps().get(p.steps().size() - 1);

        // Without this step a run during which the RF environment moved looks
        // exactly like a run where the antenna worked.
        assertTrue(last.instruction().contains("Return to the baseline"));
        assertTrue(last.verification().contains("void"));
    }

    @Test
    @DisplayName("an experiment cannot exist without a stated question")
    void questionIsMandatory() {
        assertThrows(IllegalArgumentException.class,
                () -> Experiment.plan("e", "Title", "  ", "proc", List.of(), T0));
    }

    @Test
    @DisplayName("experiment lifecycle moves through run capture to a conclusion")
    void experimentLifecycle() {
        Experiment e = PlatypusCatalog.headlineExperiment(T0);
        assertEquals(Experiment.Status.PLANNED, e.status());
        assertFalse(e.hasData());

        Experiment withRun = e.withRun("session-1", T0.plusSeconds(60));
        assertEquals(Experiment.Status.IN_PROGRESS, withRun.status());
        assertTrue(withRun.hasData());

        // Adding the same run twice must not duplicate it.
        assertEquals(1, withRun.withRun("session-1", T0.plusSeconds(90)).runIds().size());

        Experiment done = withRun.concludeWith("Design C led by 12.5 dB", T0.plusSeconds(120));
        assertEquals(Experiment.Status.CONCLUDED, done.status());
        assertEquals("Design C led by 12.5 dB", done.conclusion());

        // The original is untouched -- records are immutable.
        assertEquals(Experiment.Status.PLANNED, e.status());
    }

    @Test
    @DisplayName("a concluded experiment must carry its conclusion")
    void concludedRequiresConclusion() {
        assertThrows(IllegalArgumentException.class, () -> new Experiment(
                "e", "T", "Q?", "p", List.of(), List.of(),
                Experiment.Status.CONCLUDED, "", T0, T0));
    }

    @Test
    @DisplayName("the headline experiment ships unconcluded, with the +12.5 dB unclaimed")
    void headlineExperimentMakesNoClaimYet() {
        Experiment e = PlatypusCatalog.headlineExperiment(T0);

        // The measurement predates this software. Until a run is imported, the
        // app has no basis for asserting the figure.
        assertEquals(Experiment.Status.PLANNED, e.status());
        assertTrue(e.conclusion().isEmpty());
        assertTrue(e.dutIds().contains(PlatypusCatalog.DESIGN_C_ID));
        assertTrue(e.dutIds().contains(PlatypusCatalog.CHIP_ANTENNA_ID));
    }

    @Test
    @DisplayName("every seeded dimension says which document it came from")
    void geometryCarriesItsProvenance() {
        // Not about flagging disagreement -- about being able to answer "where did
        // 9.81 come from?" in a year without re-deriving it.
        assertTrue(PlatypusCatalog.designA().notes().contains("README.md"));
        assertTrue(PlatypusCatalog.PROVENANCE.contains("README.md"));
    }

    @Test
    @DisplayName("Design C is recorded as the source of the published headline figure")
    void designCCarriesTheHeadline() {
        // IMG_8025 shows Design A mounted on the Tab5, but the README attributes
        // the +12.5 dB to Design C. Recording which design the claim belongs to is
        // the difference between a result and an anecdote.
        assertTrue(PlatypusCatalog.designC().notes().contains("+12.5 dB"));
        assertFalse(PlatypusCatalog.designA().notes().contains("+12.5 dB"));
    }
}
