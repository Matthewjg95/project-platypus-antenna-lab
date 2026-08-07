package dev.antennalab.core.lab;

import dev.antennalab.core.json.Json;

import java.time.Instant;
import java.util.List;

/**
 * A question, the method used to answer it, the runs that were captured, and
 * what was concluded.
 *
 * <p>This is the unit the whole application is really about. A session is a pile
 * of samples; an experiment is a piece of engineering knowledge -- and it is the
 * thing worth keeping when the project is finished and you start the next one.
 *
 * <p>An experiment holds only <em>references</em> to its procedure, DUTs and
 * runs. That is deliberate: correcting an antenna's recorded geometry should fix
 * every experiment that used it, and a run should be reinterpretable under a
 * revised conclusion without being rewritten.
 *
 * @param id           stable identifier.
 * @param title        short name, e.g. "Design C vs Design A at 3 m".
 * @param question     the actual question in one sentence. Required -- an
 *                     experiment without a question is just data collection.
 * @param procedureId  the {@link Procedure} followed.
 * @param dutIds       the {@link Dut}s compared, in presentation order.
 * @param runIds       session ids captured under this experiment.
 * @param status       where it has got to.
 * @param conclusion   what was decided. Empty until the status says otherwise.
 * @param createdAt    when the experiment was opened.
 * @param updatedAt    when it last changed.
 */
public record Experiment(
        String id,
        String title,
        String question,
        String procedureId,
        List<String> dutIds,
        List<String> runIds,
        Status status,
        String conclusion,
        Instant createdAt,
        Instant updatedAt) {

    /** Lifecycle of an experiment. */
    public enum Status {
        /** Designed but not yet run. */
        PLANNED,
        /** Runs are being captured. */
        IN_PROGRESS,
        /** Enough data captured; being analysed. */
        ANALYSING,
        /** A conclusion has been recorded. */
        CONCLUDED,
        /** Stopped without a conclusion -- kept, because negative results matter. */
        ABANDONED
    }

    public Experiment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (question == null || question.isBlank()) {
            // Enforced rather than defaulted. The single most common way a bench
            // measurement becomes worthless later is that nobody wrote down what
            // it was supposed to settle.
            throw new IllegalArgumentException(
                    "question is required: an experiment without a stated question is just "
                            + "data collection");
        }
        id = id.strip();
        title = title.strip();
        question = question.strip();
        procedureId = procedureId == null ? "" : procedureId.strip();
        dutIds = List.copyOf(dutIds);
        runIds = List.copyOf(runIds);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        conclusion = conclusion == null ? "" : conclusion.strip();
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("createdAt and updatedAt are required");
        }
        if (status == Status.CONCLUDED && conclusion.isEmpty()) {
            throw new IllegalArgumentException(
                    "a CONCLUDED experiment must carry its conclusion");
        }
    }

    /** A fresh experiment in the PLANNED state. */
    public static Experiment plan(String id, String title, String question,
                                  String procedureId, List<String> dutIds, Instant now) {
        return new Experiment(id, title, question, procedureId, dutIds, List.of(),
                Status.PLANNED, "", now, now);
    }

    /** Attach a captured run, moving to IN_PROGRESS if it was still planned. */
    public Experiment withRun(String runId, Instant now) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (runIds.contains(runId)) {
            return this;
        }
        List<String> updated = new java.util.ArrayList<>(runIds);
        updated.add(runId);
        Status next = status == Status.PLANNED ? Status.IN_PROGRESS : status;
        return new Experiment(id, title, question, procedureId, dutIds, updated,
                next, conclusion, createdAt, now);
    }

    /** Record a conclusion and close the experiment. */
    public Experiment concludeWith(String text, Instant now) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a conclusion cannot be empty");
        }
        return new Experiment(id, title, question, procedureId, dutIds, runIds,
                Status.CONCLUDED, text, createdAt, now);
    }

    /** Abandon without a conclusion; the record is kept deliberately. */
    public Experiment abandon(String reason, Instant now) {
        return new Experiment(id, title, question, procedureId, dutIds, runIds,
                Status.ABANDONED, reason == null ? "" : reason, createdAt, now);
    }

    /** True once at least one run has been captured. */
    public boolean hasData() {
        return !runIds.isEmpty();
    }

    public Json toJson() {
        return Json.object()
                .put("id", id)
                .put("title", title)
                .put("question", question)
                .put("procedureId", procedureId)
                .put("dutIds", Json.array(dutIds.stream().map(Json::of).toList()))
                .put("runIds", Json.array(runIds.stream().map(Json::of).toList()))
                .put("status", status.name())
                .put("conclusion", conclusion)
                .put("createdAt", createdAt.toString())
                .put("updatedAt", updatedAt.toString())
                .build();
    }

    public static Experiment fromJson(Json.Obj o) {
        String rawStatus = o.strOr("status", Status.PLANNED.name());
        Status status;
        try {
            status = Status.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new Json.JsonException(
                    "unknown experiment status '" + rawStatus + "'. This library file may have "
                            + "been written by a newer version of Antenna Lab.");
        }
        return new Experiment(
                o.str("id"),
                o.str("title"),
                o.str("question"),
                o.strOr("procedureId", ""),
                o.arrOrEmpty("dutIds").stream().map(j -> ((Json.Str) j).value()).toList(),
                o.arrOrEmpty("runIds").stream().map(j -> ((Json.Str) j).value()).toList(),
                status,
                o.strOr("conclusion", ""),
                Instant.parse(o.str("createdAt")),
                Instant.parse(o.str("updatedAt")));
    }
}
