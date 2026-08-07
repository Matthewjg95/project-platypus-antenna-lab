package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The durable record of what has been built and measured.
 *
 * <p><b>Why this is not part of a session file.</b> Sessions are per-run and
 * per-project; the library is neither. "Platypus Rev 7.13 Design C" and
 * "boresight sweep at 3 m" are assets that should still be usable in two years on
 * a project that does not exist yet. Storing them once and referencing them by id
 * is what makes a measurement taken today comparable to one taken next year, and
 * what makes correcting a transcription error fix every experiment at once rather
 * than one.
 *
 * <p><b>Storage.</b> Three pretty-printed JSON files in one directory, sorted by
 * id so ordering is stable. That combination is chosen so the library can live in
 * git next to the KiCad project it describes and produce a readable diff when an
 * antenna's geometry is corrected or a procedure is revised. It is intended to be
 * hand-editable in an emergency; that is a feature, not an accident.
 *
 * <p><b>Durability.</b> Saves are written to a temporary file and then moved into
 * place, so a crash mid-write leaves the previous library intact rather than a
 * half-written one. Losing the experiment record to a truncated file would be
 * considerably worse than losing a session.
 *
 * <p>Instances are safe to share across threads.
 */
public final class LabLibrary {

    private static final String DUTS_FILE = "duts.json";
    private static final String PROCEDURES_FILE = "procedures.json";
    private static final String EXPERIMENTS_FILE = "experiments.json";

    /** Schema version, so a future format change can be detected rather than misread. */
    static final int SCHEMA_VERSION = 1;

    private final Path root;
    private final Map<String, Dut> duts = new ConcurrentHashMap<>();
    private final Map<String, Procedure> procedures = new ConcurrentHashMap<>();
    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();

    private LabLibrary(Path root) {
        this.root = root;
    }

    /** Default location, shared by every project on this machine. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), "AntennaLab", "library");
    }

    /**
     * Open the library at the given directory, creating an empty one if absent.
     *
     * @throws UncheckedIOException if the directory cannot be read or created.
     * @throws Json.JsonException   if a file exists but is malformed -- deliberately
     *                              fatal, because silently starting from empty
     *                              would look like data loss.
     */
    public static LabLibrary openOrCreate(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        LabLibrary library = new LabLibrary(root);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create library directory " + root, e);
        }
        library.load();
        return library;
    }

    private void load() {
        readAll(root.resolve(DUTS_FILE), "duts", Dut::fromJson, d -> duts.put(d.id(), d));
        readAll(root.resolve(PROCEDURES_FILE), "procedures", Procedure::fromJson,
                p -> procedures.put(p.id(), p));
        readAll(root.resolve(EXPERIMENTS_FILE), "experiments", Experiment::fromJson,
                e -> experiments.put(e.id(), e));
    }

    private <T> void readAll(Path file,
                             String arrayKey,
                             java.util.function.Function<Json.Obj, T> reader,
                             java.util.function.Consumer<T> sink) {
        if (!Files.exists(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        if (text.isBlank()) {
            return;
        }
        Json.Obj document = Json.parseObject(text);
        int version = (int) document.numOr("schemaVersion", SCHEMA_VERSION);
        if (version > SCHEMA_VERSION) {
            throw new Json.JsonException(
                    file.getFileName() + " uses schema version " + version + " but this build "
                            + "understands at most " + SCHEMA_VERSION
                            + ". Upgrade Antenna Lab rather than risk misreading it.");
        }
        for (Json element : document.arrOrEmpty(arrayKey)) {
            if (!(element instanceof Json.Obj o)) {
                throw new Json.JsonException(
                        "every entry in '" + arrayKey + "' must be an object, in " + file);
            }
            sink.accept(reader.apply(o));
        }
    }

    /** Where this library lives on disk. */
    public Path root() {
        return root;
    }

    // -------------------------------------------------------------------- duts

    public void put(Dut dut) {
        if (dut == null) {
            throw new IllegalArgumentException("dut is required");
        }
        duts.put(dut.id(), dut);
    }

    public Optional<Dut> dut(String id) {
        return Optional.ofNullable(duts.get(id));
    }

    /** All DUTs, ordered by id so the UI and the files agree. */
    public List<Dut> duts() {
        return duts.values().stream().sorted(Comparator.comparing(Dut::id)).toList();
    }

    // -------------------------------------------------------------- procedures

    public void put(Procedure procedure) {
        if (procedure == null) {
            throw new IllegalArgumentException("procedure is required");
        }
        procedures.put(procedure.id(), procedure);
    }

    public Optional<Procedure> procedure(String id) {
        return Optional.ofNullable(procedures.get(id));
    }

    public List<Procedure> procedures() {
        return procedures.values().stream().sorted(Comparator.comparing(Procedure::id)).toList();
    }

    // ------------------------------------------------------------- experiments

    public void put(Experiment experiment) {
        if (experiment == null) {
            throw new IllegalArgumentException("experiment is required");
        }
        experiments.put(experiment.id(), experiment);
    }

    public Optional<Experiment> experiment(String id) {
        return Optional.ofNullable(experiments.get(id));
    }

    public List<Experiment> experiments() {
        return experiments.values().stream().sorted(Comparator.comparing(Experiment::id)).toList();
    }

    /**
     * Check that an experiment's references actually resolve.
     *
     * <p>Returns the problems rather than throwing: a library with a dangling
     * reference is still worth opening and fixing, and refusing to load it would
     * be the worst possible response to a typo.
     */
    public List<String> validate(Experiment experiment) {
        List<String> problems = new ArrayList<>();
        if (!experiment.procedureId().isEmpty() && !procedures.containsKey(experiment.procedureId())) {
            problems.add("procedure '" + experiment.procedureId() + "' is not in this library");
        }
        for (String dutId : experiment.dutIds()) {
            if (!duts.containsKey(dutId)) {
                problems.add("DUT '" + dutId + "' is not in this library");
            }
        }
        return List.copyOf(problems);
    }

    // ------------------------------------------------------------------ saving

    /**
     * Write the library to disk.
     *
     * <p>Synchronised so two concurrent saves cannot interleave their temp-file
     * moves; the in-memory maps are concurrent, but the file set has to move as a
     * unit.
     */
    public synchronized void save() {
        writeAll(root.resolve(DUTS_FILE), "duts",
                duts().stream().map(Dut::toJson).toList());
        writeAll(root.resolve(PROCEDURES_FILE), "procedures",
                procedures().stream().map(Procedure::toJson).toList());
        writeAll(root.resolve(EXPERIMENTS_FILE), "experiments",
                experiments().stream().map(Experiment::toJson).toList());
    }

    private void writeAll(Path file, String arrayKey, List<Json> entries) {
        Map<String, Json> document = new LinkedHashMap<>();
        document.put("schemaVersion", Json.of(SCHEMA_VERSION));
        document.put(arrayKey, Json.array(entries));
        String text = Json.writePretty(new Json.Obj(document)) + "\n";

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            // ATOMIC_MOVE where the filesystem supports it; a plain replace is an
            // acceptable fallback, and either beats writing the real file in place.
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** How many of each kind the library holds -- used by the UI status line. */
    public String summary() {
        return "%d DUTs, %d procedures, %d experiments"
                .formatted(duts.size(), procedures.size(), experiments.size());
    }
}
