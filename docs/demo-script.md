# Demo video script — Antenna Lab

Target: **2–3 minutes**. Contest judges watch a lot of these; the antenna result
should land inside the first 30 seconds, not at the end.

> **Draft — day 1.** Beats are sketched; timings and the hardware segment get
> firmed up once serial capture works. <!-- TODO -->

---

## Cold open (0:00–0:20)

Board on the bench, patch antenna beside it, app already running with live traces
moving.

> "This is a two-and-a-half dollar ESP32 dev board, a patch antenna I etched
> myself, and a piece of Java 26 that turns the two of them into an antenna test
> bench."

Cut to the delta card — the delta, its confidence interval, and its grade.

> "That number is the whole project."

---

## The problem (0:20–0:45)

> "If you build an antenna, the honest question is: is it actually better, or did
> I just move closer to the router? Answering that properly needs repeated
> measurements on both antennas under matched conditions — and that's bench
> equipment you probably don't own."

Show the RF switch on the board.

> "This board can flip between its own chip antenna and an external port in
> milliseconds. Same room, same second, same everything — so the comparison is
> real."

---

## The software (0:45–1:40)

Screen recording, dark instrument UI.

1. **Live scope** — both traces running, point out CH1 amber (chip) and CH2 cyan
   (external). Hit Pause, drop a marker, Resume.
2. **The delta card** — zoom the headline, then the line underneath it.
   > "It never shows you the number without the error bars. Under thirty samples,
   > or if the two traces are unevenly sampled, it refuses to quote a figure at
   > all and tells you to capture more."
3. **A/B view** — overlay two sessions. <!-- TODO: script once built -->
4. **Report export** — one click, HTML report opens.
   > "That's the artefact — charts, statistics, and the conditions the run was
   > taken under."

---

## The Java angle (1:40–2:20)

Cut to the editor. Keep this concrete — one screenful of code, not a feature list.

Show `CapturePipeline`:

```java
try (prod; var scope = StructuredTaskScope.open()) {
    scope.fork(() -> { prod.produce(queue::put); return null; });
    scope.fork(() -> { runBufferStage();          return null; });
    scope.fork(() -> { runPublishStage();         return null; });
    scope.join();
}
```

> "Three stages, three virtual threads, one scope. The classic bug in serial
> software is the reader thread outliving the capture and keeping the port open,
> so the next Start fails. With structured concurrency you can't leave this block
> while anything is still running — so releasing the port isn't something I
> remembered to do, it's something the language guarantees."

Then the sealed `Source` switch:

> "And because the source types are sealed, adding a new one turns every switch
> over them into a compile error that names exactly what I still have to handle."

---

## Close (2:20–2:40)

Back to the bench.

> "Java 26, JavaFX, one dev board and an antenna I made on a scrap of FR-4.
> Twelve and a half decibels — with a confidence interval."

---

## Shot list

- [ ] Bench wide shot: board, patch antenna, laptop
- [ ] Close-up: patch antenna, MMCX connector
- [ ] Close-up: RF switch region of the board
- [ ] Screen capture: live scope, ~40 s of clean traces
- [ ] Screen capture: pause + marker interaction
- [ ] Screen capture: A/B overlay <!-- TODO -->
- [ ] Screen capture: report export, scrolled
- [ ] Editor: `CapturePipeline` scope block
- [ ] Editor: `Producers.forSource` switch

## Recording notes

- Run the app maximised at 1920×1080 so the UI text is legible after compression.
- Use a fresh synthetic seed for B-roll, but **any measured figure must be shown
  from a real captured session**, not the simulator. The status bar reads
  `SIMULATED` in synthetic mode and that will be on camera.
