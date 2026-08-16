# Demo video script — Antenna Lab

Hard ceiling: **120 seconds** (contest limit is 90–120 s). This cut aims for
~110 s so one flubbed transition doesn't blow the limit. Every beat earns its
seconds or gets cut; the casualties are listed at the bottom so nothing
sneaks back in.

The point is NOT an antenna result — we do not have a validated one and the
video must not imply otherwise. The point is the system: a desktop app that
runs antenna experiments hands-free and refuses to overstate what the data
shows.

---

## Cold open (0:00–0:15)

Bench shot: Tab5 + patch antenna + app running, both traces live.

> "A patch antenna I etched myself, a $60 tablet with an RF switch, and a
> Java 26 app that turns them into an automated antenna test bench. My first
> measurement claimed +12.5 dB. It was wrong — and this tool is how I found
> out."

The withdrawn claim IS the hook. Twelve seconds in, judges know this entry
is about honesty, not bar charts.

## The machine works (0:15–0:50)

Screen capture, automated run already going. Speed-ramp the boring middle.

> "The app finds the instrument on serial by itself and drives the RF switch
> over two-byte commands. A run interleaves chip and external blocks so a
> changing room can't masquerade as antenna gain, throws away samples until
> the device itself confirms each switch, and ends by re-measuring the
> baseline — if the room moved, the run declares itself void. It still saves
> the data. It just refuses to attach a conclusion to it."

Visuals in order: progress panel segments filling → trace flipping
amber/cyan → status line with a confirmed switch → (cut) → delta card with
CI and grade.

## The record (0:50–1:15)

One slow scroll of the HTML report; two seconds on ~/AntennaLab in Explorer.

> "Every number ships with its error bars and a confidence grade — under-
> sampled or below the instrument's resolution, it refuses to quote at all.
> The raw serial stream is on disk byte-for-byte before any parsing touched
> it, and every run attaches to an experiment with a stated question, a
> versioned procedure, and a written conclusion. The product is the evidence
> chain."

## The Java (1:15–1:40)

Editor, ONE screenful: the CapturePipeline scope block. No second example.

> "Java 26, structured concurrency, virtual threads. The classic serial bug
> is a reader thread that outlives the capture and holds the port hostage —
> this block can't be left while any stage runs, so releasing the port is
> guaranteed by the language, not by my discipline."

## Close (1:40–1:50)

Back to the bench.

> "It measured my antenna, told me it was worse, and saved the receipts.
> That's the tool I wish I'd had before I believed my first measurement."

---

## Timing discipline

- Read the narration aloud with a timer BEFORE recording visuals: the text
  above is ~230 words ≈ 95–105 s at a natural pace. If any section runs
  long, cut words, not playback speed.
- Speed-ramp (4–8×) the middle of the automated run; never speed-ramp the
  switch confirmation moment — that beat is the product.
- Leave 2 s of clean bench footage at the end for the fade.

## Cut from the 3-minute version (do not sneak back)

- The quick-check segment (its story is told by the run itself)
- The problem-statement section ("is it the antenna or did I move")
- The sealed-types second code example
- Pause/marker interaction, library walkthrough, milestones

## Shot list (unchanged assets, fewer of them)

- [ ] Bench wide: Tab5 + patch + laptop, both screens alive (also the cover GIF session)
- [ ] Screen: automated run — panel filling, trace flip, confirmed switch
- [ ] Screen: delta card with CI + grade
- [ ] Screen: HTML report, one slow scroll; Explorer on ~/AntennaLab
- [ ] Editor: CapturePipeline scope block
- [ ] Status bar reads LIVE in every measured shot — SIMULATED will be on camera otherwise
