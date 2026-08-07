// `core` is the headless half of Antenna Lab: domain model, serial parsing,
// statistics, session storage and HTML/SVG report generation.
//
// It deliberately has NO JavaFX dependency. That is what lets the parser and the
// stats maths be tested on a CI box with no display, and it is also why the
// report generator emits SVG by hand rather than snapshotting a JavaFX chart --
// chart rendering stays testable and headless.

plugins {
    `java-library`
}

dependencies {
    // Serial I/O lives in core because the *parsing* contract belongs with the
    // domain; the UI should never speak to a COM port directly.
    api(libs.jserialcomm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
