// Root build for Antenna Lab.
//
// Shared configuration lives here so `core` and `app` cannot drift apart on
// language level or preview flags -- which matters more than usual on this
// project, because we depend on a *preview* API (JEP 525, Structured
// Concurrency, sixth preview in JDK 26). Preview class files carry the exact
// JDK version that produced them and are refused by any other JDK, so the
// toolchain, `--release`, and `--enable-preview` have to agree everywhere:
// compile, test, run, and the jpackage launcher.

plugins {
    java
}

val javaVersion = libs.versions.java.get().toInt()

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaVersion)
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = javaVersion
        options.encoding = "UTF-8"
        // --enable-preview must be paired with --release <current>; javac rejects
        // preview features targeted at any earlier release.
        options.compilerArgs.addAll(
            listOf(
                "--enable-preview",
                // Preview APIs warn on every single use site. We have opted in
                // deliberately and pinned the JDK, so silence just that category
                // and keep every other lint on.
                "-Xlint:all,-preview",
            )
        )
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-preview")
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("-enable-preview", true)
        (options as StandardJavadocDocletOptions).addStringOption("-release", javaVersion.toString())
    }
}

// Convenience: `./gradlew toolchainInfo` prints what the build actually resolved,
// which is the first thing to check when a preview-feature error looks bizarre.
tasks.register("toolchainInfo") {
    group = "help"
    description = "Print the JVM this build resolved for compilation."
    val launcher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    doLast {
        val meta = launcher.get().metadata
        println("Toolchain vendor   : ${meta.vendor}")
        println("Toolchain version  : ${meta.javaRuntimeVersion}")
        println("Toolchain home     : ${meta.installationPath}")
        println("Gradle             : ${gradle.gradleVersion}")
    }
}
