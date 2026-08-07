# Antenna Lab

An RF experiment manager for 2.4 GHz antenna work, written in Java 26.

Antenna Lab drives an M5Tab5 (ESP32-C6-MINI-1U) whose RF path can be switched
between the module's on-board chip antenna and an external MMCX connector. It
streams RSSI over USB serial and plots both paths as live scope traces — but the
scope is the instrument, not the product.

**The product is the record.** Antenna Lab keeps a durable library of what was
built, how it was measured, and what was concluded: antenna designs with their
actual geometry, versioned test procedures, and experiments that carry a stated
question through to a recorded answer. That library is deliberately not tied to
this project — the same procedures and the same DUT registry are meant to serve
the 915 MHz Meshtastic work and whatever comes after it.

The antennas under test are the [Project Platypus](docs/HACKSTER.md) Rev 7.13.1
patches: three designs on one panel, differing only in how the patch is matched
to the feed. Design C (quarter-wave transformer) measured **+12.5 dB** over the
chip antenna.

> **Status: day 2 of 10.** Live scope works end to end on the synthetic source;
> the lab library is complete and tested. Serial capture and CSV import are
> deliberately unimplemented — see
> [Deliberately not implemented yet](#deliberately-not-implemented-yet).

---

## Requirements

| Component | Version | Why this one |
|---|---|---|
| JDK | **Temurin 26.0.2+10** | Java 26 went GA 2026-03-17. Non-LTS. |
| Gradle | **9.6.1** (via wrapper) | Gradle ≥ 9.4 is the first to run on JDK 26. |
| JavaFX | **26.0.2** | Patch line matching the JDK. |
| jSerialComm | **2.11.4** | Serial I/O — the only runtime dependency. |
| JUnit | **6.1.2** | Jupiter programming model. |

The dependency list is deliberately one library. The lab library needed JSON, and
Jackson would have been the obvious answer; a hand-written codec in
[`core/json`](core/src/main/java/dev/antennalab/core/json/Json.java) kept the
budget intact, since the schema is small and entirely ours. Its writer is an
exhaustive `switch` over a sealed value model, so a new JSON value kind is a
compile error rather than a silent gap.

Everything is pinned exactly in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) —
no dynamic versions, so a clean checkout builds the same artefact.

### Installing the JDK on Windows

```bash
winget install --id EclipseAdoptium.Temurin.26.JDK --version 26.0.2.10 --exact
```

Gradle's toolchain support will locate it automatically once installed. Verify
what the build actually resolved:

```bash
./gradlew toolchainInfo
```

### A note on `--enable-preview`

Structured concurrency is still a **preview API** in Java 26
([JEP 525](https://openjdk.org/jeps/525), sixth preview). This project uses it for
real, so `--enable-preview` is wired through compilation, tests, `run`, and the
jpackage launcher.

The practical consequence: **class files built here run only on JDK 26.** Preview
class files record the exact JDK that produced them and every other JDK refuses
to load them. That is the deal preview features come with, and for a project
whose whole point is exercising modern Java it is the right side of the trade.

---

## Build and run

```bash
./gradlew :app:run
```

```bash
./gradlew build
```

```bash
./gradlew :core:test
```

`core` has no JavaFX dependency and its tests run headless.

---

## Project layout

```
antenna-lab/
├── core/                     # domain, parsing, stats, sessions, reports — no UI deps
│   └── src/main/java/dev/antennalab/core/
│       ├── domain/           # records + the sealed Source hierarchy
│       ├── lab/              # DUTs, feed designs, procedures, experiments, library
│       ├── json/             # dependency-free JSON codec for the library
│       ├── source/           # sample producers (synthetic today, serial/replay to come)
│       ├── pipeline/         # structured-concurrency capture pipeline + ring buffer
│       ├── stats/            # trace statistics and the delta-dB result
│       ├── parse/            # serial + CSV parsing (awaiting real captures)
│       ├── session/          # session persistence
│       └── report/           # HTML/SVG report generation
├── app/                      # JavaFX UI
│   └── src/main/java/dev/antennalab/app/
│       ├── Launcher.java     # plain main class, so JavaFX runs from the classpath
│       ├── AntennaLabApp.java
│       └── view/             # ScopeView (Canvas), DeltaCard
└── docs/
    ├── HACKSTER.md           # contest writeup
    ├── demo-script.md
    └── screenshots/
```

The `core` / `app` split is enforced by the build, not by convention: `core`
simply does not have JavaFX on its compile classpath. That is what keeps the
parser and the statistics testable without a display.

---

## How it works

### The capture pipeline

```
producer ──▶ bounded queue ──▶ rolling buffer ──▶ frame publisher ──▶ UI
 (stage 1)                        (stage 2)          (stage 3)
```

All three stages run as virtual threads inside a single
`StructuredTaskScope`. They are not independent jobs — they are one unit of work,
and the scope enforces that: leaving the `try`-with-resources block cannot
complete until every stage has finished, by any route. Stopping a capture is
therefore guaranteed to release the source, with no per-failure-path cancellation
bookkeeping to get wrong.

Shutdown is a cascade rather than an abrupt halt, so when a replay file runs out
the queue is drained and a final frame is published before the pipeline closes —
nothing captured is lost to teardown.

### The scope view

Canvas-based rather than `LineChart`. At a few hundred samples per second a
`LineChart` allocates a node per point and relayouts the scene graph on every
update; a 2 000-point window visibly stutters. The Canvas path is one
`strokePolyline` per trace per frame — flat cost, and it looks like bench
equipment.

### The lab library

A directory of three pretty-printed, id-sorted JSON files — `duts.json`,
`procedures.json`, `experiments.json` — meant to live in git next to the KiCad
project it describes.

* **DUTs** carry real geometry. A patch antenna's `FeedDesign` is a sealed type,
  so "inset feed at y₀ = 9.81 mm, 6.3 mm slot" and "λ/4 transformer, Z₁ = 100 Ω"
  are structured data, not a notes field. That is what makes "does the
  transformer beat the inset feed?" a question software can help answer.
* **Procedures** are versioned and cited by the runs that followed them, so
  "these two results are comparable" becomes checkable rather than hoped.
* **Experiments** require a stated question. An experiment without one is just
  data collection, and the constructor rejects it.

Everything is referenced by id, so correcting a mistyped dimension fixes every
experiment that used it rather than one. Writes go through a temp file and a
move, because losing the experiment record is worse than losing a session.

### The delta, and its error bars

The headline figure is `mean(external) − mean(chip)` in the dB domain, which is
what "12.5 dB better" conventionally means and what antenna datasheets quote.

It never travels without its qualification. Every delta carries a 95% confidence
interval and a grade:

| Grade | Meaning |
|---|---|
| `STRONG` | Difference is at least twice its margin of error. |
| `MODERATE` | Difference exceeds its margin, but not by much. |
| `WEAK` | Interval overlaps zero — the traces are not distinguishable. |
| `BELOW_RESOLUTION` | Statistically separable, but under 2 dB. |
| `INSUFFICIENT` | Under 30 samples on a path, or worse than 3:1 imbalance. |

`BELOW_RESOLUTION` is the one worth explaining. The ESP32 reports RSSI quantised
to 1 dBm, and Project Platypus's own `TEST_PROCEDURE.md` states that differences
under 2 dBm are within measurement noise. Averaging drives error bars down
without limit, so a large enough sample makes *any* small bias "significant" — a
1.2 dB delta over 3000 samples has a confidence interval nowhere near zero and
still tells you nothing about the antenna. The check fires only *after* the
statistics claim significance, because that is exactly where statistical
significance and physical meaning come apart.

The UI colours the headline by grade, so a weak result cannot be displayed in the
same confident green as a strong one.

### What this rig cannot settle

Per the Platypus README: return loss is not signal loss. Even Design B's
deliberate 97 Ω mismatch costs well under 2 dB of delivered power — inside the
noise floor above. So an over-the-air RSSI comparison is **not** expected to
separate designs A, B and C; the +12.5 dB comes from directivity and from
escaping the host enclosure, not from matching finesse. Ranking the three
matching approaches needs an S11 sweep on a VNA.

That limitation is recorded in the code rather than left for a reader to
discover, because a bench that quietly invites an unsupported conclusion is worse
than no bench.

---

## Deliberately not implemented yet

`Producers.forSource` throws `UnsupportedOperationException` with an explanation
for `SerialSource` and `ReplaySource`.

This is on purpose. The firmware's serial output format and CSV column layout
have not been captured yet, and a parser written against a guess is worse than no
parser — it looks finished while silently mis-reading real data. Both are built
against real captures once those are in hand.

Everything downstream of parsing (pipeline, buffer, scope, statistics, delta) is
finished and exercised by the synthetic source, so wiring in the real parser is
an isolated change.

---

## Licence

TBD before submission.
