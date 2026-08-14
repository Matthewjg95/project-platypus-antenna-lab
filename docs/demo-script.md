# Demo video script — Antenna Lab

Target: **2–3 minutes**. Contest judges watch a lot of these; the point has to
land inside the first 30 seconds.

The point is NOT an antenna result — we do not have a validated one, and the
script must not imply otherwise. The point is the *system*: a desktop app that
turns an RF measurement into a reproducible, inspectable experiment record, and
refuses to overstate what the data shows.

---

## Cold open (0:00–0:25)

Tab5 on the bench, patch antenna connected, app already running with live
traces moving. Status bar reads `LIVE - measured on hardware`.

> "This is an M5Stack Tab5 with an RF switch between its internal antenna and a
> patch antenna I etched myself. And this is Antenna Lab — a Java 26 desktop app
> that runs antenna experiments on it, hands-free, and records everything."

Point at the delta card: the number, the confidence interval, the grade line.

> "That number never appears without its error bars. If the data can't support
> it, the app says so instead of showing it. That honesty is the product."

---

## The instrument and its pipeline (0:25–0:55)

> "The Tab5 is the instrument — it measures RSSI and flips the RF switch in
> milliseconds. The app is its pipeline. It finds the device on serial by its
> log signature — no COM port menus — and it can command the switch itself over
> two-byte serial commands."

Live: click **Quick check**. Narrate while it runs (~30 s — trim in edit).

> "This is a thirty-second wiring check: command the chip antenna, confirm the
> device actually switched, collect, command the external, confirm, collect,
> return to baseline. And when it finishes —"

Show the status line: *wiring check passed ... This is not a measurement.*

> "— it tells you it proved the rig works and measured nothing. Four samples is
> not data, and the app knows that."

---

## A real experiment (0:55–1:45)

Switch to the Experiments tab. Show the seeded library: DUTs with real
geometry, versioned procedures, experiments with stated questions.

> "Experiments here have to state a question — the constructor literally
> rejects one without it. Procedures are versioned, so two runs are comparable
> only if they cite the same procedure."

Click **Run** on an experiment. Show the scope during the automated run:
alternating CHIP/EXT blocks, the block counter in the status bar.

> "The run interleaves the antennas — chip, external, chip, external — so if
> the room changes mid-run, the drift lands on both antennas instead of
> masquerading as gain. Samples during a switch are thrown away until the
> device itself confirms the new path. And it ends by re-measuring the baseline:
> if the room moved too much, the run declares itself void. It still saves the
> data — it just refuses to attach a conclusion to it."

<!-- Record this segment from a real run; if the full 10-minute procedure is
     too long for the shoot, film the first two blocks and the report of an
     earlier completed run. Never present a synthetic run as measured -- the
     status bar says SIMULATED on camera. -->

## The record (1:45–2:15)

Open the HTML report. Scroll once, slowly.

> "One click and the experiment is an artefact: the traces, the statistics, the
> confidence grade, and the conditions it ran under. The raw serial stream is
> also on disk, byte for byte, before any parsing touched it — so the processed
> result is never the only surviving copy of a measurement."

Show `~/AntennaLab` in Explorer briefly: `library/`, `sessions/`, `raw/`.

> "Designs, procedures, runs and raw captures — all JSON and plain text, meant
> to live in git next to the KiCad project they describe."

---

## The Java angle (2:15–2:45)

Cut to the editor. One screenful: the `CapturePipeline` scope block.

```java
try (prod; var scope = StructuredTaskScope.open()) {
    scope.fork(() -> { prod.produce(queue::put); return null; });
    scope.fork(() -> { runBufferStage();          return null; });
    scope.fork(() -> { runPublishStage();         return null; });
    scope.join();
}
```

> "Java 26, structured concurrency, virtual threads. The classic serial-port
> bug is a reader thread that outlives the capture and keeps the port hostage.
> This block cannot be left while any stage is running — releasing the port
> isn't something I remembered to do, it's something the language guarantees.
> And because the source types are sealed, adding a new one turns every switch
> over them into a compile error that names what I still have to handle."

---

## Close (2:45–3:00)

Back to the bench, app running.

> "I don't have a validated antenna result yet — an early measurement looked
> spectacular and didn't survive scrutiny, and the app is why I know that.
> That's the pitch: not a number, but a bench that won't let me fool myself."

---

## Shot list

- [ ] Bench wide: Tab5, patch antenna, laptop
- [ ] Close-up: patch antenna, MMCX connector, RF switch region
- [ ] Screen: auto-detect connecting on launch (status bar sequence)
- [ ] Screen: quick check end-to-end, including the "not a measurement" line
- [ ] Screen: automated run — switch confirmations, block counter
- [ ] Screen: a void run's status line (stage one by unplugging mid-run if needed)
- [ ] Screen: HTML report, one slow scroll
- [ ] Explorer: ~/AntennaLab tree
- [ ] Editor: CapturePipeline scope block

## Recording notes

- App maximised at 1920×1080; UI text must survive compression.
- The status bar reads `SIMULATED` in synthetic mode and that WILL be on
  camera. Any segment presented as measured must come from a live capture.
- No gain figures anywhere in the narration. If a delta is visible on screen,
  its grade line must be visible too.
