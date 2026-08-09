package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.parse.Tab5LogParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests running against the REAL capture taken from the M5Tab5 on
 * 2026-08-08 — not synthetic test strings. The fixture is the authority: if
 * firmware output changes, a new capture gets committed and these numbers
 * change with it.
 *
 * <p>Ground truth for the fixture, established by manual inspection:
 * 38 samples — 19 INT (−32..−38 dBm) and 19 EXT (−37..−50 dBm) — amid 573
 * lines that include a boot ROM banner (the port-open DTR pulse resets the
 * board), heartbeats, touch events and scan chatter.
 */
class Tab5LogParserTest {

    private static final String FIXTURE = "/captures/tab5-raw-2026-08-08-213501.txt";

    private static String fixture() throws IOException {
        try (InputStream in = Tab5LogParserTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("fixture missing: " + FIXTURE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the real capture yields exactly 19 chip and 19 external readings")
    void realCaptureParsesCompletely() throws IOException {
        Tab5LogParser parser = new Tab5LogParser();
        List<Tab5LogParser.Reading> readings = parser.feed(fixture());
        // Flush: the file may end without a newline; a trailing terminator makes
        // the final line complete either way.
        readings.addAll(parser.feed("\n"));

        assertEquals(38, readings.size(), "sample count must match manual inspection");
        assertEquals(19, readings.stream().filter(r -> r.path() == AntennaPath.CHIP).count());
        assertEquals(19, readings.stream().filter(r -> r.path() == AntennaPath.EXTERNAL).count());
    }

    @Test
    @DisplayName("values and order survive: first is INT -35, the EXT -50 outlier is kept")
    void valuesMatchTheCapture() throws IOException {
        Tab5LogParser parser = new Tab5LogParser();
        List<Tab5LogParser.Reading> readings = new ArrayList<>(parser.feed(fixture()));
        readings.addAll(parser.feed("\n"));

        assertEquals(AntennaPath.CHIP, readings.get(0).path());
        assertEquals(-35.0, readings.get(0).rssiDbm(), 1e-9);

        // The -50 reading is a real multipath dip in the capture. A parser (or a
        // later "cleanup") that drops outliers would silently bias the stats.
        assertTrue(readings.stream().anyMatch(
                        r -> r.path() == AntennaPath.EXTERNAL && r.rssiDbm() == -50.0),
                "the EXT -50 dBm reading must survive parsing");

        // Every value in this firmware is a whole dBm.
        assertTrue(readings.stream().allMatch(r -> r.rssiDbm() == Math.rint(r.rssiDbm())));
    }

    @Test
    @DisplayName("boot banner, heartbeats and touch chatter produce zero readings")
    void noiseLinesAreIgnoredNotFatal() {
        Tab5LogParser parser = new Tab5LogParser();
        // Verbatim lines from the capture, including the reset banner that
        // appears whenever the port is opened.
        String noise = """
                ESP-ROM:esp32p4-eco2-20240710
                rst:0x17 (CHIP_USB_UART_RESET),boot:0x20c (SPI_FAST_FLASH_BOOT)
                [hb] up=5s home=1 ota=1 heap=418676
                [LOOP 250] enter screen=1
                [SCOPE-TOUCH] tap x=163 y=719 BY=674
                [SCAN] found target AP at index 0: -35 dBm
                [SAMPLE] stats updated: n=1 avg=-35.0
                [ANT-SW] doAntSwitch entered, mode=INT
                """;

        List<Tab5LogParser.Reading> readings = parser.feed(noise);

        assertEquals(0, readings.size(),
                "scan results and stats lines mention dBm but are NOT samples");
        assertEquals(8, parser.linesSeen());
        assertEquals(8, parser.linesIgnored());
    }

    @Test
    @DisplayName("chunked delivery: readings are identical regardless of how reads split lines")
    void chunkBoundariesDoNotMatter() throws IOException {
        String text = fixture() + "\n";

        // Whole-file baseline.
        Tab5LogParser whole = new Tab5LogParser();
        List<Tab5LogParser.Reading> expected = whole.feed(text);

        // Feed the same bytes in pseudo-random tiny chunks, as a serial port
        // actually delivers them — including splits mid-token like "[SAMP|LE]".
        Random rng = new Random(42);
        Tab5LogParser chunked = new Tab5LogParser();
        List<Tab5LogParser.Reading> actual = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int n = Math.min(1 + rng.nextInt(17), text.length() - i);
            actual.addAll(chunked.feed(text.substring(i, i + n)));
            i += n;
        }

        assertEquals(expected, actual, "chunking must never create or drop readings");
    }

    @Test
    @DisplayName("switch transitions parse, and both line variants from the capture are covered")
    void switchLinesParse() {
        assertEquals(AntennaPath.EXTERNAL,
                Tab5LogParser.parseSwitch("[ANT] -> EXT P0=HIGH ok").orElseThrow().to());
        assertEquals(AntennaPath.CHIP,
                Tab5LogParser.parseSwitch("[ANT] -> INT P0=LOW ok").orElseThrow().to());
        assertTrue(Tab5LogParser.parseSwitch("[ANT-SW] doAntSwitch entered, mode=EXT").isEmpty(),
                "the enter/exit bracket lines are not transitions");
    }

    @Test
    @DisplayName("CRLF, LF and lone-CR line endings all terminate lines")
    void lineEndingVariantsWork() {
        for (String ending : new String[] {"\r\n", "\n", "\r"}) {
            Tab5LogParser parser = new Tab5LogParser();
            List<Tab5LogParser.Reading> readings =
                    parser.feed("[SAMPLE] enter: INT = -40 dBm" + ending);
            assertEquals(1, readings.size(), "ending " + ending.replace("\r", "CR").replace("\n", "LF"));
            assertEquals(-40.0, readings.get(0).rssiDbm(), 1e-9);
        }
    }
}
