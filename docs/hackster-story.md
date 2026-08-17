# Antenna Lab — the paste-ready Hackster story

> This is the succinct version for the Hackster project page (~700 words,
> judge-skimmable). The full engineering writeup stays in
> [HACKSTER.md](HACKSTER.md) and is linked from here — nothing was deleted,
> it just stopped being the front door. `[IMG: ...]` marks where each visual
> goes in the Hackster editor.

---

`[VIDEO: 110-second demo]`

## Changing the paradigm on hobby antenna testing

I etched a 2.4 GHz patch antenna on a scrap of FR-4. Then came the question
every antenna builder faces: *is it actually any good?* The hobby answer is
guesswork — hold it up, squint at the signal bars, move two feet, convince
yourself. I wanted the answer a lab would give: repeated measurements under a
stated procedure, statistics with error bars, and a record I could stand
behind.

**Antenna Lab** is that bench: a Java 26 desktop app that turns an M5Stack
Tab5 into an automated antenna test instrument — and its defining feature is
that it refuses to overstate what the data shows.

`[IMG: bench photo — patch antenna, Tab5, laptop, both scopes live]`

## What it does

The Tab5 is the instrument: it measures WiFi RSSI and carries an RF switch
between its chip antenna and an external MMCX port. Antenna Lab is its
pipeline:

- **Finds the device itself** — identifies the firmware on serial by its log
  signature in under a second. No COM port menus.
- **Runs experiments hands-free** — drives the RF switch over two-byte serial
  commands, interleaves chip/external blocks so a changing room can't
  masquerade as antenna gain, discards samples until the device *confirms*
  each switch, and re-measures the baseline at the end. If the RF environment
  moved, the run declares itself **void** — the data is kept, the conclusion
  is refused.
- **Never quotes a naked number** — every delta ships with a 95% confidence
  interval and a grade. Under-sampled, unbalanced, or below the instrument's
  1 dBm resolution? It says so instead of showing green.
- **Keeps the evidence chain** — experiments must state a question, procedures
  are versioned, the raw serial bytes hit disk before any parsing, and one
  click exports a self-contained HTML report. Simulated data is watermarked
  `SIMULATED` so it can never pass for measurement.

`[IMG: automated run — progress panel, both traces]`
`[IMG: delta card with CI and grade]`
`[IMG: HTML report scroll]`

## The Java 26 part

This is RF instrument software — LabVIEW and Python territory — written in
modern Java on purpose, and the features are load-bearing:

```java
try (prod; var scope = StructuredTaskScope.open()) {
    scope.fork(() -> { prod.produce(queue::put); return null; });
    scope.fork(() -> { runBufferStage();          return null; });
    scope.fork(() -> { runPublishStage();         return null; });
    scope.join();
}
```

**Structured concurrency** (JEP 525): the classic serial-port bug is a reader
thread that outlives the capture and holds the port hostage. This block cannot
be left while any stage runs — releasing the port is guaranteed by the
language, not by discipline. **Virtual threads** carry every blocking I/O
stage. **Sealed interfaces + exhaustive switches** mean adding a new data
source or DUT kind is a compile error naming every place that must handle it —
twice during development, that compiler error was the code review. **Records**
make samples, sessions and statistics immutable values. The build is pinned to
JDK 26 outright: `--enable-preview` class files load nowhere else
([build.log](../build.log) in the repo shows the toolchain resolving
Temurin 26.0.2).

The deep dive — architecture, the statistics (and why a statistically
significant 1.2 dB is still not a result), test philosophy, CI catching a
platform bug and a race in its first three runs — is in
[HACKSTER.md](HACKSTER.md).

## Why it matters

Every home has the same unsolved RF problems: the room WiFi never reaches,
the sensor that drops offline, the LoRa node that needs a better antenna. The
moment you change an antenna you are running an experiment, whether you admit
it or not. This bench is the difference between "the bars look better" and
knowing, with error bars — built entirely from things you already own: a
laptop and a $60 tablet. No VNA, no spectrum analyser, no lab.

`[IMG: wiring schematic]`

## Build it yourself

Everything is public and free:

1. **App**: clone
   [project-platypus-antenna-lab](https://github.com/Matthewjg95/project-platypus-antenna-lab),
   install Temurin JDK 26, `./gradlew :app:run`. Starts in synthetic mode —
   no hardware needed to try it.
2. **Instrument**: flash
   [Tab5-Antenna-Scope](https://github.com/Matthewjg95/Tab5-Antenna-Scope)
   onto a Tab5 with PlatformIO. It boots straight into the bench.
3. **Antenna** (optional): etch the
   [open-hardware patch PCB](https://github.com/Matthewjg95/project-platypus-patch-antenna)
   for under a dollar, or measure any MMCX antenna you like.

GPLv3 software, CERN-OHL-S hardware.

## What's next

Session A/B overlay, an S11 sweep on a real VNA (the measurement this rig
honestly can't make), and SCPI support for bench instruments — every modern
oscilloscope speaks it, and the screenshots-onto-a-flash-drive ritual at real
test benches is two commands nobody wired up.

---

*Real measurements. Honest conclusions. Built from things you already own.*
