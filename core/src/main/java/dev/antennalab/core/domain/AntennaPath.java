package dev.antennalab.core.domain;

/**
 * Which way the board's RF switch was routed when a sample was taken.
 *
 * <p>The M5Tab5 carries an ESP32-C6-MINI-1U whose RF path can be steered by GPIO
 * between the module's own chip antenna and an external MMCX connector. Every
 * measurement in Antenna Lab is meaningless without knowing which of the two was
 * live, so this travels with each sample rather than sitting in session metadata.
 */
public enum AntennaPath {

    /** The ESP32-C6-MINI-1U's on-module chip antenna: the reference/control path. */
    CHIP("Chip"),

    /** Whatever is bolted to the MMCX port -- for this project, the Platypus patch. */
    EXTERNAL("External");

    private final String displayName;

    AntennaPath(String displayName) {
        this.displayName = displayName;
    }

    /** Short human-readable label, used on chart legends and in report tables. */
    public String displayName() {
        return displayName;
    }
}
