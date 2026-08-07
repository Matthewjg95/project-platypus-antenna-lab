package dev.antennalab.app;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the JavaFX toolkit once per JVM, for every test that needs it.
 *
 * <p><b>Why this exists.</b> The obvious per-class setup -- {@code Platform.startup}
 * in {@code @BeforeAll} and {@code Platform.exit} in {@code @AfterAll} -- works
 * perfectly for exactly one test class and then breaks the moment a second one
 * appears. {@code Platform.exit()} shuts the toolkit down for the whole JVM and
 * JavaFX cannot be restarted in the same process, so the second class finds a
 * dead toolkit, its {@code Platform.startup} throws {@code IllegalStateException},
 * and every {@code runLater} afterwards is silently never executed. The symptom is
 * a timeout, which points nowhere near the cause.
 *
 * <p>So: start once, never exit. Gradle's test worker terminates the JVM when the
 * run finishes, which is the only shutdown actually needed.
 */
final class FxTestToolkit {

    private static volatile Boolean available;

    private FxTestToolkit() {
    }

    /**
     * Ensure the toolkit is running.
     *
     * @return false when this environment has no graphics stack, so tests can be
     *         skipped rather than failed.
     */
    static synchronized boolean ensureStarted() {
        if (available != null) {
            return available;
        }
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            available = started.await(30, TimeUnit.SECONDS);
            // Nothing is ever shown, so without this the toolkit would consider
            // itself finished after the first snapshot.
            Platform.setImplicitExit(false);
        } catch (IllegalStateException alreadyRunning) {
            // Started by an earlier test class in this JVM. That is the expected
            // path for every class after the first.
            available = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            available = false;
        } catch (UnsupportedOperationException | Error headless) {
            available = false;
        }
        return available;
    }

    /**
     * Run work on the FX application thread and return its result.
     *
     * <p>Failures are rethrown on the calling thread; an exception swallowed on
     * the FX thread would otherwise surface only as a missing result.
     */
    static <T> T onFxThread(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                result.set(work.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS),
                "FX work did not complete within 30s -- is the toolkit still alive?");
        if (failure.get() != null) {
            throw new AssertionError("work on the FX thread threw", failure.get());
        }
        return result.get();
    }
}
