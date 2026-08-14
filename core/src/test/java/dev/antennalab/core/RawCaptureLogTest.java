package dev.antennalab.core;

import dev.antennalab.core.source.RawCaptureLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawCaptureLogTest {

    @Test
    @DisplayName("bytes are written verbatim, including what a parser would ignore")
    void bytesAreWrittenVerbatim(@TempDir Path tmp) throws IOException {
        byte[] chunk1 = "boot banner rst:0x17\r\n[SAMPLE] enter: INT = -35 dBm\r\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "E sdmmc_common: sdmmc_init_ocr timeout\r\n"
                .getBytes(StandardCharsets.UTF_8);

        Path file;
        try (RawCaptureLog log = new RawCaptureLog(tmp, "COM6")) {
            file = log.file();
            log.append(chunk1, chunk1.length);
            log.append(chunk2, chunk2.length);
            assertNull(log.failure());
        }

        byte[] expected = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, expected, 0, chunk1.length);
        System.arraycopy(chunk2, 0, expected, chunk1.length, chunk2.length);
        assertArrayEquals(expected, Files.readAllBytes(file),
                "the raw log must be byte-identical to what the device sent");
    }

    @Test
    @DisplayName("partial buffers write only the filled length")
    void partialBuffersRespectLength(@TempDir Path tmp) throws IOException {
        // The read loop reuses one 4096-byte buffer; writing all of it would
        // interleave stale bytes from earlier reads into the record.
        byte[] buffer = new byte[64];
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(data, 0, buffer, 0, data.length);

        Path file;
        try (RawCaptureLog log = new RawCaptureLog(tmp, "COM6")) {
            file = log.file();
            log.append(buffer, data.length);
        }

        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("appending after close is a no-op, not an exception")
    void appendAfterCloseIsSafe(@TempDir Path tmp) throws IOException {
        RawCaptureLog log = new RawCaptureLog(tmp, "COM6");
        log.close();

        // The producer's read loop can race close() by one iteration; that
        // must cost nothing.
        byte[] late = "late".getBytes(StandardCharsets.UTF_8);
        log.append(late, late.length);

        assertTrue(Files.readAllBytes(log.file()).length == 0);
    }

    @Test
    @DisplayName("two captures on the same port do not collide")
    void filenamesDoNotCollide(@TempDir Path tmp) throws IOException {
        // CREATE_NEW makes a same-second collision an open failure rather than
        // silent truncation of the earlier capture; the name carries a
        // once-per-second stamp, so back-to-back opens must still both work
        // or fail loudly -- never overwrite.
        try (RawCaptureLog first = new RawCaptureLog(tmp, "COM6")) {
            byte[] a = "first".getBytes(StandardCharsets.UTF_8);
            first.append(a, a.length);

            IOException collision = null;
            RawCaptureLog second = null;
            try {
                second = new RawCaptureLog(tmp, "COM6");
            } catch (IOException e) {
                collision = e;
            }

            if (second != null) {
                assertTrue(!second.file().equals(first.file()),
                        "two logs must never share a file");
                second.close();
            } else {
                // Same-second open refused: acceptable, because the first
                // capture's bytes survived -- which is the property under test.
                assertTrue(collision != null);
            }
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(first.file()));
        }
    }
}
