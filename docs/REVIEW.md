# Workflow review follow-through

An independent Claude/Frodo source review on 2026-10-06 examined the development preview and in-progress correction integration. It did not operate the phone. Findings about device behavior are hypotheses to test, not measured results. The earlier review was redirected from the research checkout to this authoritative repository before conclusions were used.

| Finding | Current disposition |
| --- | --- |
| A shared demo image can look like a real measurement | Annotated exports now carry a source caption; synthetic demos have an amber synthetic/not-a-measurement caption. Live exports state that baseline/accuracy validation is pending. Original camera words remain unchanged. |
| Export lacks a palette legend | Added a palette gradient and numeric limits; an invalid-radiance swatch is included when needed. |
| Capture results appear below the fold | Moved save/error feedback into the persistent capture bar. Volume-key refusals explain why capture was unavailable. |
| Two meanings of Raw | Correction selector is now Apparent/Corrected. Save raw still retains all three files, but explicitly selects the raw plane as the preferred share option and presents it first in the chooser controls. |
| Correction can queue behind slow USB operations | Added a separate correction executor, generation checks, an applying indicator and errors in the correction pane. Captures wait for matching correction settings in the displayed-frame telemetry. |
| Numeric inputs use a text keyboard | Decimal keyboard hints and explicit sign controls added. Reflected-temperature sign entry passed on the Android 16 AVD; span/isotherm sign entry and Pixel keyboard behavior still need gesture checks. Named user presets remain unimplemented. |
| Correction inputs are hidden while viewing readings | The corrected live-image caption includes emissivity/reflected temperature. |
| All actions announce a toggle state | Selection descriptions now apply only to actual toggles. |
| Attach can cancel another open for the same event | The activity and receiver share an attach entry point which retains an existing open/permission request. Hardware repetition still needs qualification. |
| Manual span editor starts from old defaults | When auto span has valid readings, the editor starts from the displayed range. |
| UTC-only time on images | Image footer now prints local time and UTC offset; JSON retains UTC nanoseconds. |
| Every network frame updates UI state | Network status updates only on a state transition; numeric telemetry retains its separate cadence. |
| Old image remains after signal loss | An opaque NO SIGNAL cover and a stalled-frame/command badge were added. The final-build cover was verified in a controlled network connection-failure screenshot. Repeated physical USB qualification remains open. |
| Settings scroll the image away | The viewport is now pinned beside/before a separate controls scroll area, with landscape columns and an immersive full-screen mode. Portrait and one revised landscape transition were inspected on Pixel. |
| Renderer failure has no recovery path | A landscape test reproduced a Surface creation failure. Surface callbacks now check their owner; native buffer format is selected from EGL, and renderer retries are bounded. The revised transition worked in one Pixel retest; broader lifecycle qualification remains open. |

Saved-capture browsing/reanalysis is implemented and has synthetic Pixel export/reopen checks. Firmware/register context preservation and source-generation recovery checks are recorded in VALIDATION.md. Ten network Home/return cycles completed, with bounded descriptor samples and seven recorded bridge sequence gaps; this does not qualify physical cable recovery or loss-free delivery.

Still open: broader layout/lifecycle qualification, measurement naming/persistence, source setup and permanent-permission errors, dense label collision cases, visible NUC history, final-build attach/detach evidence and gain behavior during share handoff. These are tracked by the repository acceptance checklist. Source changes require device verification; this table alone is not acceptance evidence.

## Third Frodo review

Claude/Frodo reviewed clean commit `cd60a01` on 2026-10-07: **0 critical findings, 10 warnings, 12 notes**. This was a source review plus reading existing validation records; the reviewer did not rerun tests, operate hardware or examine the published APK. The previous synthetic-export critical finding and most capture/correction workflow warnings are addressed in the reviewed source. That verdict does not qualify physical accuracy or USB behavior.

Prioritized follow-up:

1. **Separate saved-frame and live profiles.** `CameraViewModel.openSaved` restores archive correction/display settings and geometry into the shared engine. Returning to another source currently retains those values; preferences can subsequently restore a different combination after process restart. Preserve a live profile and make any intentional transfer explicit.
2. **Separate gallery work from capture snapshot work.** Addressed in source after the review: listing has a dedicated executor, and capture requests check their source session before and after native snapshot rendering. A controlled AVD regression completed capture while the gallery worker was held; a queued request was explicitly cancelled after the source session changed. Temporarily restoring the shared queue made the gallery-isolation assertion fail. Actual gallery listing time and physical shutter-to-snapshot timing remain unmeasured; the review's seconds-scale estimate was not measured.
3. **Persist unfinished measurement layouts.** Geometry, delta selections and isotherm settings remain in memory across reconnect, but process restart loses them. Keep live persistence separate from saved-frame reanalysis.
4. **Clarify gain after foreground/configuration recovery.** Activity stop tears down USB, including during share handoff. Validate requested gain versus actual gain after reopen, keyboard/configuration changes and returning from a share target; surface any reset clearly.
5. **Make live annotations readable.** Overlay labels use fixed pixel sizing and lack the export's collision avoidance. Check dense layouts, display density and font scaling; distinguish box/line averages from point readings.
6. **Distinguish isotherm highlights and extrema.** The highlight, minimum marker and Rainbow colors overlap in the cyan family. Add shape/contrast cues and verify visibility on rendered examples.
7. **Use mode-specific isotherm inputs.** Below/Above use one threshold, but the editor currently shows and validates both; an unused value can reject an otherwise valid active threshold. Match editor, measurement summary and exported caption to the selected mode.
8. **Provide permission/startup recovery.** Handle camera denial, permanent denial and USB errors with actionable next steps; retain technical detail in diagnostics.
9. **Provide an action for prolonged stalls.** A stalled-frame badge alone does not tell the user how to restart USB capture. Any automatic retry should be bounded and qualified with hardware checks.
10. **Improve gallery navigation.** Consider lazy rows, thumbnails, measurement/temperature summaries and clear original-acquisition versus reanalysis-export times. Capture-set deletion needs explicit failure handling for partial removal.

Other notes include active measurement tools being hidden in full screen, displaced export labels lacking leader lines, archive repainting an unchanged plane at 25 Hz, ambiguous share-target capture identity, NUC history, large-text/TalkBack coverage and splitting the large screen composable into smaller pieces. Delta between region means is a product extension. The separate Save raw action is now accurately described; whether it deserves a capture-bar slot is a product choice.

Source findings require appropriate regression checks before being marked fixed. Physical USB recovery, baseline persistence, sensor NUC/gain behavior and official-app bath comparisons remain open. The full reviewer report is retained privately; this summary contains no device identifiers or private network details.
