// `app` is the JavaFX front end: scope view, session manager, A/B view, settings.
//
// Two deliberate choices worth knowing about before you edit this file:
//
// 1. We do NOT use the org.openjfx.javafxplugin Gradle plugin. Its newest
//    release is 0.1.0, which predates Gradle 9, so rather than gamble the
//    contest deadline on plugin compatibility we declare the JavaFX artifacts
//    directly with their platform classifier. It is about six extra lines.
//
// 2. The app runs from the CLASSPATH, not the module path. `Launcher` is a
//    plain class that does not extend javafx.application.Application, which is
//    the supported way to start a classpath JavaFX app without hitting
//    "JavaFX runtime components are missing". This keeps jpackage simple.

plugins {
    application
}

// JavaFX ships one jar per OS/arch. Resolve the classifier for whoever is
// building; primary target is Windows x64, but this keeps the build portable.
val javafxClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch64 = arch.contains("aarch64") || arch.contains("arm64")
    when {
        os.contains("win") -> if (aarch64) "win-aarch64" else "win"
        os.contains("mac") || os.contains("darwin") -> if (aarch64) "mac-aarch64" else "mac"
        else -> if (aarch64) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation(project(":core"))

    // The classifier jar carries the platform classes and native libraries; the
    // plain module supplies the POM metadata. Declaring both is the belt-and-
    // braces form that resolves cleanly without the JavaFX plugin.
    for (fx in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls)) {
        implementation(fx)
        implementation(variantOf(fx) { classifier(javafxClassifier) })
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "dev.antennalab.app.Launcher"
    // --enable-preview is added to every JavaExec by the root build, but the
    // `run` task also needs it recorded here so the jpackage launcher and the
    // start scripts inherit the same flag.
    applicationDefaultJvmArgs = listOf("--enable-preview")
}
