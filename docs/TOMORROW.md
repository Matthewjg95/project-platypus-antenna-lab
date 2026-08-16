# Tomorrow: Real Tab5 Capture + Community Alignment

## Goal

Do not expand the architecture tomorrow. Prove the existing system with one real, honest end-to-end antenna experiment.

The target evidence chain is:

**physical Patch C → Tab5 measurement → raw serial/CSV capture → Java parser → controlled experiment → statistics → HTML report**

One convincing experiment is enough for the Java in the Wild demo.

## 1. Capture real Patch C data

- Use Project Platypus Patch C.
- Record the actual Tab5 serial output and/or CSV produced by the firmware.
- Preserve the raw capture as an input artifact; do not throw away the source data after parsing.
- Record the test conditions and procedure used.
- Do not manufacture a positive result. An inconclusive result is valid and should be reported as such.

Suggested experimental question:

> Does Patch C produce a measurable RSSI improvement over the Tab5 internal antenna under this controlled test procedure?

## 2. Wire the real parser

The current implementation deliberately avoids pretending that the firmware's serial/CSV format is known. That decision was correct: do not build a parser against a guessed format.

Once the real capture exists:

- inspect the actual format;
- implement the parser against the observed format;
- add the real capture as a regression fixture where appropriate;
- run the complete pipeline against real data;
- remove/update the README language saying the real format has not yet been captured.

Do not redesign the capture pipeline just to accommodate the real format unless the evidence requires it.

## 3. Generate the first real report

The minimum successful demo is:

1. real Tab5 data enters Antenna Lab;
2. the experiment has a stated question;
3. the procedure and DUT are recorded;
4. Java processes the measurements;
5. the result includes its statistical qualification;
6. Antenna Lab generates a self-contained HTML report.

The report should make it possible to understand what was tested, how it was tested, what was measured, and what conclusion the data actually supports.

## 4. Community / Meshtastic positioning

Be cognizant of the existing antenna-testing community, including Meshtastic antenna reports. Do **not** position Antenna Lab as inventing the concept of community antenna testing.

The useful distinction is:

- community projects already document antenna measurements and results;
- Antenna Lab is building reusable machinery for producing reproducible, structured evidence from physical antenna experiments.

Keep the existing idea that the same DUT registry and procedures can support 915 MHz Meshtastic work and future antenna projects.

The intended positioning is interoperability and contribution to a broader antenna experimentation ecosystem, not competition with existing community reports.

Potential future direction (not required for the contest demo): make Antenna Lab outputs easy to consume or compare with community antenna-report datasets/workflows.

## 5. Contest scope

Do not add a pile of features for the sake of completeness.

Prioritize:

- one real experiment;
- one real raw capture;
- one real report;
- clear evidence of useful Java 26 features;
- clear separation between the Tab5 instrument and the Java verification/documentation layer.

The project's core thesis remains:

> **The Tab5 is the instrument. The product is the record.**

## Definition of done for tomorrow

- [ ] Patch C real-world data captured.
- [ ] Raw serial/CSV capture preserved.
- [ ] Real parser implemented against observed format.
- [ ] End-to-end real experiment runs.
- [ ] HTML report generated from real data.
- [ ] Result is honestly qualified, including an inconclusive result if appropriate.
- [ ] README updated to reflect the real capture now existing.
- [ ] Community/Meshtastic positioning reviewed without overclaiming novelty.
- [ ] Stop expanding scope once the demo path works.
