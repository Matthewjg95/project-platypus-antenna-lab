package dev.antennalab.app;

import dev.antennalab.app.view.ScopeView;
import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the scope off-screen and inspects the actual pixels.
 *
 * <p>Checking that the window opens proves almost nothing -- the app starts idle,
 * and every drawing bug lives in the code that only runs once samples arrive. So
 * this drives {@link ScopeView} through a real JavaFX layout and snapshot, then
 * counts trace-coloured pixels in the result.
 *
 * <p>It needs a JavaFX toolkit, so it lives in {@code app} rather than {@code core}
 * and is skipped where no graphics environment exists.
 */
class ScopeViewRenderTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 400;

    /** Trace colours from ScopeView; matched with tolerance because of antialiasing. */
    private static final Color CHIP_TRACE = Color.web("#FFB01F");
    private static final Color EXTERNAL_TRACE = Color.web("#22D3EE");
    private static final Color BACKGROUND = Color.web("#0B0F14");

    private static boolean toolkitAvailable;

    @BeforeAll
    static void startToolkit() {
        // Shared, start-once toolkit. Do NOT call Platform.exit() here -- see
        // FxTestToolkit for why that breaks every other FX test class in the JVM.
        toolkitAvailable = FxTestToolkit.ensureStarted();
    }

    /** A paired capture: both paths, external offset above chip, gently noisy. */
    private static List<RssiSample> pairedWindow(int perPath, double chipMean, double gainDb) {
        Instant t0 = Instant.parse("2026-08-07T12:00:00Z");
        List<RssiSample> out = new ArrayList<>();
        long seq = 0;
        for (int i = 0; i < perPath; i++) {
            double wobble = Math.sin(i / 9.0) * 1.5;
            out.add(new RssiSample(seq++, t0.plusMillis(i * 50L), AntennaPath.CHIP,
                    chipMean + wobble));
            out.add(new RssiSample(seq++, t0.plusMillis(i * 50L), AntennaPath.EXTERNAL,
                    chipMean + gainDb + wobble));
        }
        return out;
    }

    /** Lay the scope out at a fixed size, push a window through it, and snapshot. */
    private static WritableImage render(List<RssiSample> samples) throws Exception {
        return FxTestToolkit.onFxThread(() -> {
            ScopeView scope = new ScopeView();
            // A Scene is needed for layoutChildren to run, which is what sizes the
            // Canvas -- without it the canvas stays 0x0 and draws nothing.
            new Scene(scope, WIDTH, HEIGHT);
            scope.resize(WIDTH, HEIGHT);
            scope.applyCss();
            scope.layout();

            scope.setWindow(samples);

            return scope.snapshot(null, null);
        });
    }

    private static boolean near(Color actual, Color expected, double tolerance) {
        return Math.abs(actual.getRed() - expected.getRed()) < tolerance
                && Math.abs(actual.getGreen() - expected.getGreen()) < tolerance
                && Math.abs(actual.getBlue() - expected.getBlue()) < tolerance;
    }

    private static int countNear(WritableImage image, Color target, double tolerance) {
        PixelReader reader = image.getPixelReader();
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (near(reader.getColor(x, y), target, tolerance)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    @Timeout(90)
    @DisplayName("both traces are actually drawn, in their own channel colours")
    void drawsBothTraces() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(toolkitAvailable,
                "no JavaFX toolkit available in this environment");

        WritableImage image = render(pairedWindow(120, -62.0, 12.5));

        assertEquals(WIDTH, (int) image.getWidth());
        assertEquals(HEIGHT, (int) image.getHeight());

        int chipPixels = countNear(image, CHIP_TRACE, 0.12);
        int externalPixels = countNear(image, EXTERNAL_TRACE, 0.12);

        // A 120-point polyline across 800px cannot produce only a handful of
        // pixels; a low count here means the trace collapsed to a dot or ran off
        // the plot area rather than being scaled into it.
        assertTrue(chipPixels > 200,
                "expected a drawn chip trace, found only " + chipPixels + " amber pixels");
        assertTrue(externalPixels > 200,
                "expected a drawn external trace, found only " + externalPixels + " cyan pixels");
    }

    @Test
    @Timeout(90)
    @DisplayName("the external trace sits above the chip trace when it has gain")
    void higherRssiDrawsHigher() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(toolkitAvailable,
                "no JavaFX toolkit available in this environment");

        WritableImage image = render(pairedWindow(120, -62.0, 12.5));
        PixelReader reader = image.getPixelReader();

        // Screen Y grows downward, so a stronger signal must have a SMALLER mean y.
        // This is the assertion that catches an inverted axis -- a bug that looks
        // entirely plausible on a moving scope until you compare it to the numbers.
        double chipSumY = 0;
        int chipN = 0;
        double externalSumY = 0;
        int externalN = 0;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = reader.getColor(x, y);
                if (near(c, CHIP_TRACE, 0.12)) {
                    chipSumY += y;
                    chipN++;
                } else if (near(c, EXTERNAL_TRACE, 0.12)) {
                    externalSumY += y;
                    externalN++;
                }
            }
        }

        assertTrue(chipN > 0 && externalN > 0, "both traces must be present to compare");
        double chipMeanY = chipSumY / chipN;
        double externalMeanY = externalSumY / externalN;

        assertTrue(externalMeanY < chipMeanY,
                "external trace (+12.5 dB) should render above chip; "
                        + "external meanY=" + externalMeanY + " chip meanY=" + chipMeanY);
    }

    @Test
    @Timeout(90)
    @DisplayName("an empty window renders the idle state without throwing")
    void emptyWindowIsSafe() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(toolkitAvailable,
                "no JavaFX toolkit available in this environment");

        WritableImage image = render(List.of());

        // Should be essentially the instrument background plus grid and the
        // NO SIGNAL legend -- but crucially, no trace colours at all.
        assertTrue(countNear(image, CHIP_TRACE, 0.12) == 0, "no chip trace should be drawn");
        assertTrue(countNear(image, EXTERNAL_TRACE, 0.12) == 0, "no external trace should be drawn");
        assertTrue(countNear(image, BACKGROUND, 0.05) > 1000, "background should dominate");
    }
}
