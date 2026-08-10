package dev.antennalab.core.session;

import dev.antennalab.core.domain.AntennaPath;
import dev.antennalab.core.domain.ReplaySource;
import dev.antennalab.core.domain.RssiSample;
import dev.antennalab.core.domain.SerialSource;
import dev.antennalab.core.domain.Session;
import dev.antennalab.core.domain.SessionMetadata;
import dev.antennalab.core.domain.Source;
import dev.antennalab.core.domain.SyntheticSource;
import dev.antennalab.core.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for captured runs.
 *
 * <p><b>The join that makes an experiment's runs real.</b> {@code Experiment}
 * holds a list of run ids; before this class existed those ids pointed at
 * nothing, so an experiment could claim three runs whose data you could never
 * open. Here a run id <em>is</em> a session id, and {@link #load(String)}
 * turns it back into samples — which is what lets a report be regenerated from
 * a run recorded weeks ago rather than only from whatever is currently on
 * screen.
 *
 * <p><b>Storage shape.</b> One file per session under {@code sessions/}, next
 * to (not inside) the lab library: the library is curated, human-edited,
 * git-diffable configuration; sessions are bulk measurement data. Samples are
 * therefore written as compact positional arrays
 * {@code [sequence, epochMillis, "CHIP"|"EXTERNAL", dBm]} rather than named
 * objects — a 10,000-sample run is roughly a third the size, and nobody
 * hand-edits a sample list. Everything a human might read or correct (source,
 * metadata) stays a named object.
 *
 * <p>Writes go through a temp file and a move, for the same reason the library
 * does: a crash mid-write must not shred an existing run.
 */
public final class SessionStore {

    /** Bumped only on an incompatible layout change; refuses newer files. */
    static final int SCHEMA_VERSION = 1;

    private final Path root;

    private SessionStore(Path root) {
        this.root = root;
    }

    /** Default location: a sibling of the lab library, under the same home. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), "AntennaLab", "sessions");
    }

    /** Open (creating if needed) the session store rooted at the given directory. */
    public static SessionStore openOrCreate(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create session directory " + root, e);
        }
        return new SessionStore(root);
    }

    public Path root() {
        return root;
    }

    private Path fileFor(String sessionId) {
        // Session ids are generated (UUIDs or run-<timestamp>), but this is the
        // one place a caller-supplied string becomes a filesystem path, so treat
        // it as untrusted: no separators, no traversal.
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("/") || sessionId.contains("\\")
                || sessionId.contains("..")) {
            throw new IllegalArgumentException("unsafe session id: " + sessionId);
        }
        return root.resolve(sessionId + ".json");
    }

    /** True when a run id resolves to stored data. */
    public boolean exists(String sessionId) {
        return Files.exists(fileFor(sessionId));
    }

    /** Persist a session, overwriting any prior file with the same id. */
    public void save(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        String text = Json.writePretty(toJson(session)) + "\n";
        Path file = fileFor(session.id());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write session " + session.id(), e);
        }
    }

    /**
     * Load a session by id.
     *
     * <p>Empty when the id has no file — a dangling run reference, which the UI
     * reports rather than treating as a crash.
     *
     * @throws Json.JsonException if the file exists but is malformed or newer
     *         than this build understands.
     */
    public Optional<Session> load(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read session " + sessionId, e);
        }
        return Optional.of(fromJson(Json.parseObject(text)));
    }

    /** Every stored session id, newest file first. */
    public List<String> listIds() {
        try (var stream = Files.list(root)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .map(p -> {
                        String n = p.getFileName().toString();
                        return n.substring(0, n.length() - ".json".length());
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list sessions in " + root, e);
        }
    }

    /** Delete a stored session. Returns true when a file was removed. */
    public boolean delete(String sessionId) {
        try {
            return Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete session " + sessionId, e);
        }
    }

    // ------------------------------------------------------------------ codec

    static Json toJson(Session session) {
        List<Json> samples = new ArrayList<>(session.samples().size());
        for (RssiSample s : session.samples()) {
            samples.add(Json.array(List.of(
                    Json.of(s.sequence()),
                    Json.of(s.timestamp().toEpochMilli()),
                    Json.of(s.antenna().name()),
                    Json.of(s.rssiDbm()))));
        }
        return Json.object()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("id", session.id())
                .put("source", sourceToJson(session.source()))
                .put("metadata", metadataToJson(session.metadata()))
                // Documented inline because the compact form is deliberate and
                // otherwise looks like an accident to the next reader.
                .put("sampleFormat", "[sequence, epochMillis, antenna, rssiDbm]")
                .put("samples", Json.array(samples))
                .build();
    }

    static Session fromJson(Json.Obj o) {
        int version = (int) o.numOr("schemaVersion", SCHEMA_VERSION);
        if (version > SCHEMA_VERSION) {
            throw new Json.JsonException("session uses schema version " + version
                    + " but this build understands at most " + SCHEMA_VERSION
                    + ". Upgrade Antenna Lab rather than risk misreading it.");
        }
        List<RssiSample> samples = new ArrayList<>();
        for (Json element : o.arrOrEmpty("samples")) {
            if (!(element instanceof Json.Arr(List<Json> items)) || items.size() < 4) {
                throw new Json.JsonException(
                        "each sample must be [sequence, epochMillis, antenna, rssiDbm]");
            }
            samples.add(new RssiSample(
                    (long) asNumber(items.get(0)),
                    Instant.ofEpochMilli((long) asNumber(items.get(1))),
                    AntennaPath.valueOf(asString(items.get(2))),
                    asNumber(items.get(3))));
        }
        return new Session(
                o.str("id"),
                sourceFromJson(o.obj("source")),
                metadataFromJson(o.obj("metadata")),
                samples);
    }

    private static double asNumber(Json j) {
        if (j instanceof Json.Num(double value)) {
            return value;
        }
        throw new Json.JsonException("expected a number in a sample row");
    }

    private static String asString(Json j) {
        if (j instanceof Json.Str(String value)) {
            return value;
        }
        throw new Json.JsonException("expected a string in a sample row");
    }

    /**
     * Source serialisation, as an exhaustive switch over the sealed hierarchy —
     * a fourth source kind becomes a compile error here rather than a session
     * that silently loses its provenance.
     */
    private static Json sourceToJson(Source source) {
        return switch (source) {
            case SerialSource(String port, int baud) -> Json.object()
                    .put("kind", "serial").put("portName", port).put("baudRate", baud).build();
            case ReplaySource(var file, double speed) -> Json.object()
                    .put("kind", "replay").put("csvFile", file.toString())
                    .put("speedMultiplier", speed).build();
            case SyntheticSource s -> Json.object()
                    .put("kind", "synthetic")
                    .put("seed", s.seed())
                    .put("chipMeanDbm", s.chipMeanDbm())
                    .put("externalGainDb", s.externalGainDb())
                    .put("noiseStdDevDb", s.noiseStdDevDb())
                    .put("samplesPerSecond", s.samplesPerSecond())
                    .build();
        };
    }

    private static Source sourceFromJson(Json.Obj o) {
        String kind = o.str("kind");
        return switch (kind) {
            case "serial" -> new SerialSource(o.str("portName"), o.intValue("baudRate"));
            case "replay" -> new ReplaySource(
                    Path.of(o.str("csvFile")), o.num("speedMultiplier"));
            case "synthetic" -> new SyntheticSource(
                    (long) o.num("seed"), o.num("chipMeanDbm"), o.num("externalGainDb"),
                    o.num("noiseStdDevDb"), o.intValue("samplesPerSecond"));
            // A kind this build does not know means the file came from a newer
            // version; say so instead of dropping the provenance silently.
            default -> throw new Json.JsonException("unknown source kind '" + kind
                    + "' — this session was probably written by a newer version");
        };
    }

    private static Json metadataToJson(SessionMetadata m) {
        return Json.object()
                .put("title", m.title())
                .put("distanceMeters", m.distanceMeters())
                .put("orientation", m.orientation())
                .put("wifiChannel", m.wifiChannel())
                .put("deviceUnderTest", m.deviceUnderTest())
                .put("notes", m.notes())
                .put("recordedAt", m.recordedAt().toString())
                .build();
    }

    private static SessionMetadata metadataFromJson(Json.Obj o) {
        return new SessionMetadata(
                o.str("title"),
                o.numOr("distanceMeters", 0),
                o.strOr("orientation", ""),
                (int) o.numOr("wifiChannel", SessionMetadata.CHANNEL_UNKNOWN),
                o.strOr("deviceUnderTest", ""),
                o.strOr("notes", ""),
                Instant.parse(o.str("recordedAt")));
    }
}
