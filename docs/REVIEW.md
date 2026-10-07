# Workflow review follow-through

An independent Claude/Frodo source review on 2026-10-06 examined the development preview and in-progress correction integration. It did not operate the phone. Findings about device behavior are hypotheses to test, not measured results. The earlier review was redirected from the research checkout to this authoritative repository before conclusions were used.

| Finding | Current disposition |
| --- | --- |
| A shared demo image can look like a real measurement | Annotated exports now carry a source caption; synthetic demos have an amber synthetic/not-a-measurement caption. Live exports state that baseline/accuracy validation is pending. Original camera words remain unchanged. |
| Export lacks a palette legend | Added a palette gradient and numeric limits; an invalid-radiance swatch is included when needed. |
| Capture results appear below the fold | Moved save/error feedback into the persistent capture bar. Volume-key refusals explain why capture was unavailable. |
| Two meanings of Raw | Correction selector is now Apparent/Corrected. Save raw still retains all three files, but explicitly selects the raw plane as the preferred share option and presents it first in the chooser controls. |
| Correction can queue behind slow USB operations | Added a separate correction executor, generation checks, an applying indicator and errors in the correction pane. Captures wait for matching correction settings in the displayed-frame telemetry. |
| Numeric inputs use a text keyboard | Decimal keyboard hints added. Latest source adds explicit sign controls for reflected temperature, span and isotherm limits; keyboard/device gesture qualification is pending. Named user presets remain unimplemented. |
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
