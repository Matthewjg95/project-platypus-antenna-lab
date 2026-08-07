# Antenna Lab

A desktop RF test bench for characterising 2.4 GHz antennas, written in Java 26.

Antenna Lab drives an M5Tab5 (ESP32-C6-MINI-1U) whose RF path can be switched
between the module's on-board chip antenna and an external MMCX connector. It
streams RSSI from the board over USB serial, plots both antenna paths as live
scope traces, and turns a run into a defensible number: **how many dB better is
the antenna under test?**

The antenna under test is a [Project Platypus](docs/HACKSTER.md) 2.4 GHz
microstrip patch (Rev 7.13, quarter-wave transformer variant), measured at
**+12.5 dB** over the chip antenna on the bench.

> **Status: day 1 of 10.** Live scope on the synthetic source works end to end.
> Serial capture and CSV import are deliberately unimplemented — see
> [Deliberately not implemented yet](#deliberately-not-implemented-yet).

---

## Requirements

| Component | Version | Why this one |
|---|---|---|
| JDK | **Temurin 26.0.2+10** | Java 26 went GA 2026-03-17. Non-LTS. |
| Gradle | **9.6.1** (via wrapper) | Gradle ≥ 9.4 is the first to run on JDK 26. |
| JavaFX | **26.0.2** | Patch line matching the JDK. |
| jSerialComm | **2.11.4** | Serial I/O. |
| JUnit | **6.1.2** | Jupiter programming model. |

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
| `INSUFFICIENT` | Under 30 samples on a path, or worse than 3:1 imbalance. |

The UI colours the headline by grade, so a weak result cannot be displayed in the
same confident green as a strong one.

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
