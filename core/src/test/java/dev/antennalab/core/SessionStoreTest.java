package dev.antennalab.core;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.SessionMetadata;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.json.Json;
import dev.antennalab.core.session.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session persistence — the join that turns an experiment's run ids into data
 * you can reopen. A statistics bug produces a wrong number you can recompute;
 * losing a capture loses an afternoon at the bench.
 */
class SessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-08-10T18:00:00Z");

    private static Session sampleSession(String id, int perPath) {
        List<RssiSample> samples = new ArrayList<>();
        long seq = 0;
        for (int i = 0; i < perPath; i++) {
            samples.add(new RssiSample(seq++, T0.plusMillis(i * 1500L), AntennaPath.CHIP, -35 - i % 3));
            samples.add(new RssiSample(seq++, T0.plusMillis(i * 1500L), AntennaPath.EXTERNAL, -41 - i % 4));
        }
        return new Session(id, SerialSource.onPort("COM6"),
                new SessionMetadata("Bench run", 3.0, "boresight", 6,
                        "Platypus Design C", "first live capture", T0),
                samples);
    }

    @Test
    @DisplayName("a captured run round-trips through disk with every sample intact")
    void roundTripsExactly(@TempDir Path dir) {
        SessionStore store = SessionStore.openOrCreate(dir);
        Session original = sampleSession("run-2026-08-10T18-00-00Z", 40);

        store.save(original);
        Session reloaded = store.load(original.id()).orElseThrow();

        assertEquals(original.id(), reloaded.id());
        assertEquals(original.samples(), reloaded.samples(), "every sample must survive verbatim");
        assertEquals(original.metadata(), reloaded.metadata());
        // Provenance must survive: a reloaded run has to still know it was measured.
        assertInstanceOf(SerialSource.class, reloaded.source());
        assertTrue(reloaded.isMeasured());
        assertEquals(40, reloaded.countFor(AntennaPath.CHIP));
        assertEquals(40, reloaded.countFor(AntennaPath.EXTERNAL));
    }

    @Test
    @DisplayName("a synthetic session reloads still flagged as not measured")
    void syntheticProvenanceSurvives(@TempDir Path dir) {
        SessionStore store = SessionStore.openOrCreate(dir);
        Session synthetic = new Session("s1", SyntheticSource.demo(),
                SessionMetadata.untitled(T0),
                List.of(new RssiSample(0, T0, AntennaPath.CHIP, -60)));

        store.save(synthetic);
        Session reloaded = store.load("s1").orElseThrow();

        // The report generator keys its simulation watermark off this. If a
        // reload lost it, a simulated run could be presented as evidence.
        assertFalse(reloaded.isMeasured());
        assertEquals(SyntheticSource.demo().seed(),
                ((SyntheticSource) reloaded.source()).seed());
    }

    @Test
    @DisplayName("a missing run id returns empty rather than throwing")
    void danglingRunIdIsSurvivable(@TempDir Path dir) {
        SessionStore store = SessionStore.openOrCreate(dir);

        // Experiments can reference a run whose file was deleted or never
        // written; the UI reports that, it does not crash on it.
        assertTrue(store.load("run-that-never-existed").isEmpty());
        assertFalse(store.exists("run-that-never-existed"));
    }

    @Test
    @DisplayName("run ids that would escape the store directory are refused")
    void unsafeIdsAreRejected(@TempDir Path dir) {
        SessionStore store = SessionStore.openOrCreate(dir);

        // Ids reach the filesystem, so traversal is checked even though today's
        // ids are generated. Cheap now; unpleasant to retrofit after a UI lets
        // someone name a run.
        for (String bad : new String[] {"../escape", "sub/dir", "a\\b", ""}) {
            assertThrows(IllegalArgumentException.class, () -> store.load(bad));
        }
    }

    @Test
    @DisplayName("listing returns saved ids, and delete removes one")
    void listAndDelete(@TempDir Path dir) {
        SessionStore store = SessionStore.openOrCreate(dir);
        store.save(sampleSession("run-a", 2));
        store.save(sampleSession("run-b", 2));

        assertEquals(2, store.listIds().size());
        assertTrue(store.listIds().containsAll(List.of("run-a", "run-b")));

        assertTrue(store.delete("run-a"));
        assertFalse(store.delete("run-a"), "deleting twice is not an error, just false");
        assertEquals(List.of("run-b"), store.listIds());
    }

    @Test
    @DisplayName("samples are stored compactly, but source and metadata stay readable")
    void storageShapeIsDeliberate(@TempDir Path dir) throws IOException {
        SessionStore store = SessionStore.openOrCreate(dir);
        store.save(sampleSession("shape", 3));
        String text = Files.readString(dir.resolve("shape.json"), StandardCharsets.UTF_8);

        // Compact positional rows for bulk data...
        assertTrue(text.contains("\"sampleFormat\""), "the compact row layout must be documented");
        assertFalse(text.contains("\"rssiDbm\": -35"), "samples should not be named objects");
        // ...named fields for anything a human might read or correct.
        assertTrue(text.contains("\"portName\": \"COM6\""));
        assertTrue(text.contains("\"orientation\": \"boresight\""));
    }

    @Test
    @DisplayName("a newer schema version is refused rather than misread")
    void futureSchemaIsRefused(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("future.json"),
                "{\"schemaVersion\": 99, \"id\": \"future\", \"samples\": []}",
                StandardCharsets.UTF_8);
        SessionStore store = SessionStore.openOrCreate(dir);

        var e = assertThrows(Json.JsonException.class, () -> store.load("future"));
        assertTrue(e.getMessage().contains("99"));
    }

    @Test
    @DisplayName("an unknown source kind names the problem instead of losing provenance")
    void unknownSourceKindIsRefused(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("odd.json"), """
                {"schemaVersion":1,"id":"odd",
                 "source":{"kind":"vna_s1p","file":"sweep.s1p"},
                 "metadata":{"title":"x","recordedAt":"2026-08-10T18:00:00Z"},
                 "samples":[]}
                """, StandardCharsets.UTF_8);
        SessionStore store = SessionStore.openOrCreate(dir);

        var e = assertThrows(Json.JsonException.class, () -> store.load("odd"));
        assertTrue(e.getMessage().contains("vna_s1p"));
    }
}
