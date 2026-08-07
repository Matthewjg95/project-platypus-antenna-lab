package dev.antennalab.core.lab;

import java.time.Instant;
import java.util.List;

/**
 * Seed entries for the Project Platypus hardware.
 *
 * <p><b>Provenance.</b> Every value here is transcribed from the KiCad project's
 * own {@code README.md} (the design-math section), not read off a photograph and
 * not derived here. That document describes the released revision; see
 * {@link #PROVENANCE}.
 *
 * <p>The panel carries three designs that differ only in how the patch is matched
 * to the feed, on one substrate in one fab run, which is what makes them a
 * genuinely controlled comparison. Design B is deliberately mismatched and exists
 * as a control: it is the design that should look worse, and a rig that cannot
 * show that is not measuring anything.
 *
 * <p><b>What RSSI can and cannot settle.</b> The project's README is explicit
 * that return loss is not signal loss -- even B's 97 ohm feed point costs well
 * under 2 dB of delivered power. So an RSSI A/B/C comparison is not expected to
 * separate the three designs by much; the +12.5 dB headline comes from
 * directivity and from escaping the host enclosure, not from matching finesse.
 * Separating A from B from C needs an S11 sweep. This is recorded here so the
 * software does not invite a conclusion the measurement cannot support.
 */
public final class PlatypusCatalog {

    private PlatypusCatalog() {
    }

    /**
     * Which document these numbers came from.
     *
     * <p>Earlier drafts of the project's notes carry different working values --
     * ordinary churn from an evolving design, since {@code TEST_PROCEDURE.md} was
     * written against an in-progress revision. The released geometry is the one in
     * {@code README.md}, and that is what is recorded here. The note exists so a
     * reader who finds an older figure elsewhere knows which one this library used,
     * not to flag a problem.
     */
    public static final String PROVENANCE =
            "Geometry from the KiCad project README.md (released Rev 7.13.1 design math). "
                    + "Earlier working drafts carry superseded values.";

    /** Board revision this catalogue describes. */
    public static final String REVISION = "7.13.1";

    /** Edge-launch RF jack fitted to all three designs (Cinch/Johnson). */
    public static final String CONNECTOR = "MMCX 135-3711-801";

    /** Substrate shared by all three designs, from the README design math. */
    public static final String SUBSTRATE =
            "FR4, h=1.6mm, er=4.4, tan d~0.02, 2-layer, ENIG, purple mask";

    /** Patch geometry shared by all three designs: W and L from the cavity model. */
    public static final String PATCH_GEOMETRY =
            "W=38.04mm, L=29.44mm, er_eff=4.086, dL=0.742mm, f0=2.3996GHz";

    public static final String DESIGN_A_ID = "platypus-7.13.1-design-a";
    public static final String DESIGN_B_ID = "platypus-7.13.1-design-b";
    public static final String DESIGN_C_ID = "platypus-7.13.1-design-c";
    public static final String CHIP_ANTENNA_ID = "esp32-c6-mini-1u-chip";

    /**
     * Design A: inset feed calculated to land on 50 ohm.
     *
     * <p>Slot width is 6.3 mm by the Salmony rule -- clearance each side of the
     * 3.1 mm feed must be at least the substrate height, so 3.1 + 2x1.6. A slot
     * barely wider than the feed is a common mistake that creates spurious
     * resonances.
     */
    public static Dut designA() {
        return new Dut.PatchAntenna(
                DESIGN_A_ID,
                "Platypus Design A",
                REVISION,
                "A",
                new FeedDesign.InsetFeed(9.81, 6.3, 50.0, true),
                CONNECTOR,
                "Inset feed, calculated match. Rin(y0) = Rin_edge*cos^2(pi*y0/L) solved for "
                        + "50 ohm. " + PATCH_GEOMETRY + ". " + SUBSTRATE + ". " + PROVENANCE);
    }

    /**
     * Design B: inset feed deliberately left at 97 ohm, retained as a control.
     *
     * <p>Its real job is metrology rather than performance. Reading R at f0 for
     * both A and B gives two independent estimates of the edge resistance
     * Rin_edge -- the one genuinely soft input in the whole design chain, assumed
     * 200 ohm where Balanis suggests 300-400. That measurement recalibrates the
     * inset formula for every future design, including the 915 MHz work.
     */
    public static Dut designB() {
        return new Dut.PatchAntenna(
                DESIGN_B_ID,
                "Platypus Design B",
                REVISION,
                "B",
                new FeedDesign.InsetFeed(7.50, 6.3, 97.0, false),
                CONNECTOR,
                "Deliberate mismatch, retained as a control: expected to underperform, and a "
                        + "rig that cannot demonstrate that is not demonstrating anything. Paired "
                        + "with A it also measures Rin_edge, the biggest open assumption in the "
                        + "design. " + PATCH_GEOMETRY + ". " + SUBSTRATE);
    }

    /**
     * Design C: quarter-wave transformer, Zt = sqrt(50*200) = 100 ohm.
     *
     * <p>This is the design that produced the project's headline result.
     */
    public static Dut designC() {
        return new Dut.PatchAntenna(
                DESIGN_C_ID,
                "Platypus Design C",
                REVISION,
                "C",
                new FeedDesign.QuarterWaveTransformer(100.0, 0.709, 17.98),
                CONNECTOR,
                "Quarter-wave transformer, no notch. Zt = sqrt(50*200) = 100 ohm. The design "
                        + "behind the published +12.5 dB result. " + PATCH_GEOMETRY + ". "
                        + SUBSTRATE);
    }

    /** The on-module chip antenna used as the reference path. */
    public static Dut chipAntenna() {
        return new Dut.ModuleAntenna(
                CHIP_ANTENNA_ID,
                "ESP32-C6-MINI-1U chip antenna",
                "ESP32-C6-MINI-1U",
                "On-module reference path, selected by the board's GPIO-controlled RF switch "
                        + "(SPDT, likely SKY13351 -- GPIO number to be confirmed from the Tab5 "
                        + "schematic). No feed geometry under our control, so none is recorded.");
    }

    /**
     * The over-the-air comparison protocol, from {@code TEST_PROCEDURE.md}
     * Phases 1-2.
     *
     * <p>The decision-gate thresholds are the project's own, not invented here.
     */
    public static Procedure pairedComparisonProcedure() {
        return new Procedure(
                "otd-static-comparison",
                "Over-the-air static comparison vs baseline",
                "1.0",
                "Rank a patch design against the M5Tab5 stock chip antenna by RSSI at matched "
                        + "positions, expressing every result as dG = RSSI_measured - RSSI_baseline "
                        + "at the same position.",
                List.of(
                        new Procedure.Step(1,
                                "Lock the AP to 2.4 GHz channel 6, disable beamforming and adaptive "
                                        + "antenna features, set transmit power to maximum and lock it.",
                                "AP settings recorded; power not left on automatic."),
                        new Procedure.Step(2,
                                "Elevate the AP 1.2-1.5 m. Clear large metal objects. Tape a floor "
                                        + "cross with 1-5 m marks in four cardinal directions.",
                                "Walking the path shows RSSI decreasing monotonically with distance, "
                                        + "stable to under 2 dBm over 5 s at a fixed position."),
                        new Procedure.Step(3,
                                "Capture the chip-antenna baseline first, board flat on a "
                                        + "non-metallic surface, orientation fixed.",
                                "Baseline recorded before any external antenna is connected."),
                        new Procedure.Step(4,
                                "Fit the MMCX pigtail and assert the antenna-select GPIO to route RF "
                                        + "to the external port.",
                                "Switch actually asserted -- without it both antennas may be live "
                                        + "simultaneously, which silently corrupts every reading."),
                        new Procedure.Step(5,
                                "At each position hold still 5 s and take 10 readings; record the "
                                        + "median. Stand at least 1 m behind the board, in the same "
                                        + "relative position every time.",
                                "Median of 10, not a single reading."),
                        new Procedure.Step(6,
                                "Repeat for each design at the same marked positions, then compute "
                                        + "dG per position against the baseline.",
                                "Same positions for every design; no design measured at a position "
                                        + "the others were not."),
                        new Procedure.Step(7,
                                "Return to the baseline antenna at the end and confirm it reads close "
                                        + "to where it started.",
                                "If the closing baseline has drifted, the RF environment moved during "
                                        + "the run and the comparison is void.")),
                List.of(
                        "M5Tab5 (ESP32-C6-MINI-1U) with GPIO-controlled RF switch",
                        "SMP-male to MMCX-male pigtail, RG178, ~100 mm",
                        "WiFi AP at fixed position, channel and power",
                        "Rotating platform marked in 15 degree increments",
                        "Measuring tape and masking tape",
                        "Non-metallic surface"),
                // Ten readings per position is the procedure's own unit of measurement;
                // the floor is set well above it so a whole run, not one position, is
                // what gets quoted.
                100,
                3.0,
                6);
    }

    /** Everything above, ready to drop into a fresh library. */
    public static void seed(LabLibrary library) {
        library.put(designA());
        library.put(designB());
        library.put(designC());
        library.put(chipAntenna());
        library.put(pairedComparisonProcedure());
    }

    /**
     * The headline experiment this project exists to answer.
     *
     * <p>Left PLANNED with no conclusion deliberately. The published +12.5 dB was
     * measured before this software existed, and its own README quotes it as
     * -40.5 dBm chip <em>average</em> against -28.0 dBm patch <em>best</em> --
     * an average against a best case, which is not a like-for-like comparison.
     * Reproducing it under this procedure, with both figures computed the same
     * way, is exactly what the application is for.
     */
    public static Experiment headlineExperiment(Instant now) {
        return Experiment.plan(
                "platypus-7.13.1-vs-chip",
                "Platypus Rev 7.13.1 vs ESP32-C6 chip antenna",
                "How much gain does each Platypus patch design deliver over the module's chip "
                        + "antenna, measured like-for-like, and can an over-the-air RSSI test "
                        + "separate A from B from C at all?",
                "otd-static-comparison",
                List.of(CHIP_ANTENNA_ID, DESIGN_A_ID, DESIGN_B_ID, DESIGN_C_ID),
                now);
    }
}
