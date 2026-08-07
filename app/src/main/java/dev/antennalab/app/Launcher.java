package dev.antennalab.app;

/**
 * Process entry point.
 *
 * <p>This class exists for one reason: it does <em>not</em> extend
 * {@link javafx.application.Application}. When the JVM's main class is an
 * {@code Application} subclass, the launcher insists the JavaFX runtime be on the
 * module path and aborts with "JavaFX runtime components are missing" otherwise.
 * Starting from a plain class sidesteps that entirely and lets the app run from
 * the classpath, which keeps both the Gradle build and the jpackage image simple.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        AntennaLabApp.main(args);
    }
}
