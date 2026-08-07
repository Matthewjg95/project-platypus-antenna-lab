# Antenna Lab — taking Java to the RF bench

> **Contest:** Hackster.io "Modern Java in the Wild" · **Category:** BYOD Integration
> **Deadline:** 16 August 2026, 11:59 p.m. PT
>
> **Draft status.** This document is written as features land, not at the end.
> Sections marked <!-- TODO --> need facts only the bench can supply.
> Last updated: 6 August 2026 (day 1).

---

## The one-sentence version

Antenna Lab is a Java 26 desktop instrument that drives an ESP32-C6 dev board,
streams RSSI from two switchable antenna paths, and turns the comparison into a
number you can defend: **+12.5 dB** for a hand-made microstrip patch over the
module's chip antenna.

---

## Why this project

Antenna work has a credibility problem for hobbyists. You build a patch antenna,
you hold it up, the signal bars look better, and you write "it works great" in
your build log. Nobody — including you — knows whether that was the antenna or
the fact that you moved two feet to the left.

Real answers need a bench: repeated measurements on both antennas under matched
conditions, statistics over the samples rather than eyeballing a peak, and a
record of the conditions the numbers were taken under. That is lab equipment
territory, and lab equipment is expensive.

So: build the instrument in software, and point it at hardware that costs less
than lunch.

The Java angle is not incidental. "Take Java where Java hasn't gone before" is the
category, and RF test and measurement is squarely a place Java isn't. This is a
domain owned by LabVIEW, MATLAB, and a long tail of Python scripts. The argument
this project makes is that modern Java — virtual threads, structured concurrency,
records, sealed types, pattern matching — is a genuinely good fit for real-time
instrument software, and that the code comes out *cleaner* than the alternatives,
not merely possible.

---

## The hardware

| Part | Detail |
|---|---|
| Board | M5Tab5, ESP32-C6-MINI-1U |
| RF switch | GPIO-controlled, selects chip antenna or external MMCX port |
| Antenna under test | Project Platypus patch, Rev 7.13 |
| Antenna type | 2.4 GHz microstrip patch, quarter-wave transformer variant |
| Reference | ESP32-C6-MINI-1U on-module chip antenna |
| Measured result | **+12.5 dB** over the chip antenna |
| Link | USB serial, streaming RSSI; firmware also logs CSV |

The board runs oscilloscope-style antenna test firmware written for this project.
The GPIO-controlled RF switch is what makes the whole thing a *comparison* rather
than two separate experiments: both antennas see the same RF environment within
milliseconds of each other, so the common-mode variation that ruins naive
antenna measurements — someone walking past, a door opening, the router's own
rate adaptation — largely cancels.

<!-- TODO: photo of the board + patch antenna on the bench -->
<!-- TODO: measurement conditions for the headline figure — distance, orientation,
     channel, number of samples, and what the far end of the link was. -->

### About the +12.5 dB

<!-- TODO: describe how this was measured, and over how many samples. The app now
     computes a 95% confidence interval for exactly this figure; once a real
     capture is imported, quote the interval here rather than the bare number. -->

---

## What the software does

1. **Live capture.** Opens the serial port, parses the RSSI stream, and plots
   both antenna paths as live scope traces with a rolling window, pause/resume,
   and operator markers.
2. **Session recording.** Records runs to a session file, and imports the
   firmware's existing CSV logs into the same model — so field captures and
   desk captures are comparable on equal terms.
3. **Analysis.** Per-trace mean, median, min/max, p95 and standard deviation,
   and the headline delta-dB between antennas with a confidence grade.
4. **A/B comparison.** Overlays two sessions — chip vs patch, or patch Rev 7.13
   vs a future Rev 8 — aligned by time or by sample index.
5. **Report export.** One-click HTML report with embedded charts, the statistics
   table, and the test-conditions metadata.
6. **Replay and simulation.** A synthetic RSSI source and CSV replay, so the whole
   application is demonstrable with nothing plugged in.

---

## Where modern Java did real work

This section is the honest one. Every feature below is load-bearing; anywhere a
Java 26 feature would have been decoration, it was left out.

### Structured concurrency (JEP 525 — preview)

The capture pipeline is three stages:

```
producer ──▶ bounded queue ──▶ rolling buffer ──▶ frame publisher ──▶ UI
```

These are not three independent jobs. They are one unit of work with one
lifetime: if the serial port drops, the other two stages are pointless; if the
operator hits Stop, all three must stop *and the port must be released*.

Written with an `ExecutorService`, that is a pile of manual bookkeeping — hold
three futures, cancel the siblings on every failure path, and hope you didn't
miss one. The failure mode when you do miss one is specific and maddening: the
reader thread outlives the capture, keeps the COM port open, and the next Start
fails with "access denied" until you restart the app. Anyone who has written
serial software in Java has met this bug.

With structured concurrency the fix is structural rather than diligent:

```java
try (prod; var scope = StructuredTaskScope.open()) {
    scope.fork(() -> { prod.produce(queue::put); return null; });
    scope.fork(() -> { runBufferStage();          return null; });
    scope.fork(() -> { runPublishStage();         return null; });
    scope.join();
}
```

Leaving that block **cannot** complete while any forked stage is still running.
There is no cancellation path to forget, because the block's scope *is* the
cancellation path. Stopping the capture is then just interrupting the owner
thread, and the port being released is a consequence of the language construct
rather than of remembering to do it.

The two resources in one `try` also give the right teardown *order* for free: the
scope closes before the producer, so no stage is still touching the port when the
port is closed.

**Cost of honesty:** this is a preview API, so the build needs `--enable-preview`
everywhere and the binaries only run on JDK 26. See
[the README](../README.md#a-note-on---enable-preview).

### Virtual threads (JEP 444)

All three pipeline stages block essentially all the time — on the port, on the
queue, on the frame clock. That is the exact workload virtual threads exist for,
and the payoff is that the code is written as straight-line blocking code because
it *is* straight-line blocking code.

There is no thread pool sized anywhere in this project, and no reactive
framework. A read loop that reads, and a publish loop that publishes, and neither
inverted into a callback chain to avoid holding an OS thread.

### Records

`RssiSample` is the atom of the application: produced by the parser, held in the
ring buffer, drawn by the scope, consumed by the statistics, tabulated in the
report — and passed across threads at every one of those boundaries.

As a record it is shallowly immutable for free, which means the only thing needing
synchronisation is the buffer, never its contents. No defensive copying on the hot
path. The generated `equals` also makes the parser tests read as plain data
comparisons against captured fixtures, which matters when the fixtures are real
captures rather than invented ones.

### Sealed interfaces + pattern matching

```java
public sealed interface Source permits SerialSource, ReplaySource, SyntheticSource
```

A `Source` is a *description* — "COM7 at 115200 baud", "replay this CSV at 4×" —
not a live connection. That separation is what lets a source be recorded into a
session file, shown in the UI, and embedded in a report's provenance block, none
of which you can do with an open port handle.

Turning a description into a live producer is a `switch` with no `default`:

```java
return switch (source) {
    case SyntheticSource spec -> new SyntheticProducer(spec);
    case SerialSource(String port, int baud) -> /* ... */;
    case ReplaySource(var file, var speed)   -> /* ... */;
};
```

Because the interface is sealed, javac proves this is exhaustive. The day a
fourth source type is added — a TCP bridge, a second board — every such switch
becomes a compile error naming exactly what still needs handling. That is a
materially better failure than a `default:` that throws at runtime on the one
machine where the new source got selected.

The alternative design — an abstract `openProducer()` method on the interface —
would have dragged jSerialComm and file I/O into the domain model, which is
precisely what keeps `core` headless and testable.

---

## Engineering decisions worth defending

### Averaging in the dB domain

dBm is logarithmic, so the arithmetic mean of dBm readings is not the linear-power
mean. Antenna Lab reports the dB-domain mean, because that is what "12.5 dB
better" conventionally means and what antenna datasheets quote — the figure stays
directly comparable to how the original bench result was expressed. The choice is
documented in the code rather than left implicit.

### The delta never travels without its error bars

The whole point of the project is a defensible number, so the one thing this UI
must not do is print a large confident figure that is actually noise. Every delta
carries a 95% confidence interval and a grade — `STRONG`, `MODERATE`, `WEAK`,
`INSUFFICIENT` — and the headline is coloured by that grade. Under 30 samples on
a path, or worse than a 3:1 count imbalance between paths, and the app says so
instead of quoting a figure.

### Simulated data is watermarked

The synthetic source exists so the app demos without hardware. It also creates an
obvious hazard: a modelled "+12.5 dB" looks identical to a measured one. So
`Source.isLiveHardware()` travels with every session, the status bar reads
**SIMULATED — not measured data**, and reports state provenance. The demo can
never be mistaken for evidence.

### No parser was written from a guess

At the time of writing, the firmware's serial format and CSV layout have not been
captured. `Producers.forSource` therefore throws — with an explanation — for
serial and replay sources.

Writing a plausible parser from an assumed format would have been the fastest way
to look finished on day 1 and the fastest way to silently mis-read real data on
day 4. Everything downstream is complete and exercised by the synthetic source,
so dropping the real parser in is an isolated change.

---

## Building it yourself

See [the README](../README.md) for the full instructions. Short version:

```bash
winget install --id EclipseAdoptium.Temurin.26.JDK --version 26.0.2.10 --exact
```

```bash
./gradlew :app:run
```

The app starts in synthetic mode, so it runs with no board attached.

---

## What I'd do next

<!-- TODO: fill in after the build is complete. -->

---

## Credits and links

<!-- TODO: link the Project Platypus antenna build log, the firmware repo, and
     the demo video. -->
