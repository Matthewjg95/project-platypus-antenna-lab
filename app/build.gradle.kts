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
    applicationDefaultJvmArgs = listOf(
        // --enable-preview is added to every JavaExec by the root build, but the
        // `run` task also needs it recorded here so the jpackage launcher and the
        // start scripts inherit the same flag.
        "--enable-preview",
        // JavaFX loads its native libraries through System::load, which JDK 24+
        // treats as a restricted method. Today that is a console warning; the JDK
        // has stated it will become a hard failure in a future release. Granting
        // it explicitly silences the warning now and is the forward-compatible
        // form -- ALL-UNNAMED because we run JavaFX from the classpath.
        "--enable-native-access=ALL-UNNAMED",
    )
}

// The same flags have to reach the test JVM, which does not inherit
// applicationDefaultJvmArgs -- ScopeViewRenderTest starts a real JavaFX toolkit
// and would otherwise print the identical native-access warning.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ---------------------------------------------------------------- packaging
//
// Producing something a person can install without a JDK, a clone, or Gradle.
//
// Two outputs, because they have different prerequisites:
//
//   :app:packageImage      a self-contained folder with "Antenna Lab.exe".
//                          Needs nothing but the JDK. Zip it and it runs on a
//                          machine with no Java at all.
//   :app:packageInstaller  a real .msi. Needs the WiX Toolset 3.x installed,
//                          which jpackage shells out to.
//
// Both bundle their own Java runtime. That is not optional here: this app is
// compiled with --enable-preview, so its class files are locked to JDK 26 and
// refuse to load on any other JDK. Shipping the runtime is what makes that
// invisible to whoever installs it.

version = "0.1.0"

val appName = "Antenna Lab"
val vendor = "Project Platypus"

val isWindows = System.getProperty("os.name").lowercase().contains("win")

/** jpackage from the same toolchain that compiled the code, not whatever is on PATH. */
val jpackageTool: Provider<String> = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
}.map {
    it.metadata.installationPath.file("bin/jpackage" + if (isWindows) ".exe" else "").asFile.absolutePath
}

/**
 * Gather every runtime jar into one flat directory.
 *
 * jpackage's --input takes a directory and puts all of it on the app classpath,
 * so this has to contain the application jar and its dependencies and nothing
 * else. `installDist` already produces exactly that in build/install/app/lib.
 */
val jpackageInput = tasks.register<Sync>("jpackageInput") {
    dependsOn(tasks.named("installDist"))
    from(layout.buildDirectory.dir("install/app/lib"))
    into(layout.buildDirectory.dir("jpackage-input"))
}

/** Flags the packaged launcher must carry, matching how `run` starts the app. */
val packagedJvmArgs = listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED")

/**
 * The application jar's filename, taken from the jar task rather than written
 * out by hand -- it embeds the project version, so hardcoding "app.jar" breaks
 * the moment the version changes.
 */
val mainJarName: Provider<String> = tasks.jar.flatMap { it.archiveFileName }

/** Generated by tools/make-icon.ps1 and committed; see that script for the artwork. */
val appIcon: File = file("src/main/resources/dev/antennalab/app/antenna-lab.ico")

fun Exec.commonJpackageArgs(type: String) {
    dependsOn(jpackageInput)
    val dest = layout.buildDirectory.dir("dist").get().asFile
    val input = layout.buildDirectory.dir("jpackage-input").get().asFile

    doFirst {
        // jpackage refuses to write into an existing destination and exits 1.
        // Without this the task succeeds exactly once and then fails on every
        // rebuild -- the "works on a clean checkout, broken for everyone who
        // already built it" flavour of bug.
        dest.resolve(appName).deleteRecursively()
        dest.listFiles()?.filter { it.name.endsWith(".msi") || it.name.endsWith(".exe") }
            ?.forEach { it.delete() }
        dest.mkdirs()
        commandLine(
            buildList {
                add(jpackageTool.get())
                addAll(listOf("--type", type))
                addAll(listOf("--name", appName))
                addAll(listOf("--app-version", project.version.toString()))
                addAll(listOf("--vendor", vendor))
                addAll(listOf("--description", "RF experiment manager and antenna test bench"))
                addAll(listOf("--input", input.absolutePath))
                addAll(listOf("--dest", dest.absolutePath))
                addAll(listOf("--main-jar", mainJarName.get()))
                addAll(listOf("--main-class", "dev.antennalab.app.Launcher"))
                // The radar glyph from the Platypus firmware's Room Scan screen.
                // Multi-resolution, because Windows picks the nearest size and a
                // single 256 px image downscales to a smeared taskbar icon.
                if (appIcon.exists()) {
                    addAll(listOf("--icon", appIcon.absolutePath))
                }
                packagedJvmArgs.forEach { addAll(listOf("--java-options", it)) }
                if (type != "app-image" && isWindows) {
                    // A Start-menu entry and a desktop shortcut, and let the user
                    // choose the install directory rather than forcing Program Files.
                    addAll(listOf("--win-menu", "--win-shortcut", "--win-dir-chooser"))
                    addAll(listOf("--win-menu-group", vendor))
                }
            }
        )
    }
}

tasks.register<Exec>("packageImage") {
    group = "distribution"
    description = "Self-contained app folder with a native launcher. No WiX needed."
    commonJpackageArgs("app-image")
    doLast {
        val out = layout.buildDirectory.dir("dist/$appName").get().asFile
        logger.lifecycle("App image: ${out.absolutePath}")
    }
}

tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Native installer (.msi on Windows). Requires the WiX Toolset 3.x."
    commonJpackageArgs(if (isWindows) "msi" else "app-image")
}

/**
 * Zip the app image into a single shippable file.
 *
 * <p>This is the recommended way to hand Antenna Lab to someone. It needs no
 * WiX, no .NET Framework 3.5, and no administrator rights -- neither to build it
 * nor for the recipient to run it. An .msi additionally trips SmartScreen's
 * unknown-publisher warning unless the installer is code-signed, which a zip
 * does not.
 */
tasks.register<Zip>("packageZip") {
    group = "distribution"
    description = "Zip the self-contained app image for distribution. No WiX needed."
    dependsOn("packageImage")

    from(layout.buildDirectory.dir("dist/$appName"))
    // Nest under a named folder so unzipping does not scatter 135 MB across
    // whatever directory the recipient happened to be in.
    into("$appName $version")
    archiveFileName = "AntennaLab-$version-windows-x64.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")

    doLast {
        logger.lifecycle("Distributable: ${archiveFile.get().asFile.absolutePath}")
    }
}
