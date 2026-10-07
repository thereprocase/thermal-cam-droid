# Physical acceptance procedure

These checks remain open. This procedure is a repeatable plan, not a result or a claim of camera calibration. Keep identifying screenshots, device identifiers and real scene images in private working storage. Publish numerical results and their stated conditions after review.

## Powered-device parameter comparison

Connect the camera directly to Pixel and keep the camera powered through the app transition. Record app versions, gain/range selection and whether the camera was disconnected or power-cycled. Thermal Field records six original device properties before establishing its configured startup values; inspect `identity.original_properties` and `identity.configured_properties` in a direct-USB sidecar. The configured values are known register readbacks, not proof of a physically neutral correction baseline.

1. Capture a Thermal Field baseline and retain its original/configured arrays.
2. Close Thermal Field's source, open `com.infisense.p2plus` and set emissivity to 0.96. Record reflected/environment inputs if the official UI exposes them; otherwise record them as unknown. Do not substitute a guessed value.
3. Close the official source, then reopen Thermal Field without removing power from the camera. Capture the original readbacks before Thermal Field's reset, and its configured readbacks afterward.
4. Repeat with a second deliberately different official emissivity input, then restore 0.96. Repeat a separate cable/power-cycle condition if practical and label it separately.

A changed property array across an observed transition is evidence of device state in that transition. Identical readbacks do not establish that the official app never writes or persists a parameter: it might reset on close, use another field or apply a host correction. Report those alternatives as unresolved unless further measurements distinguish them. Firmware/version and repeatability matter.

## Ice-water and warm-water point comparisons

Use an ice-water mixture and approximately 55 °C water. Record available contact-thermometer readings and their uncertainty; leave that field unknown if no independent probe is available. Nominal target temperatures alone are not independent reference measurements.

Use a uniform visible surface patch, with the camera center well inside the water area. Keep distance, angle and optical accessories consistent. Keep the camera powered between viewers and record the time between readings, stability and gain/range readbacks. Avoid interpreting a mixed edge/background pixel as the water temperature. A camera reading is a surface radiance measurement under the chosen assumptions.

For each target:

1. Set official emissivity to **0.96**; record any exposed reflected/environment settings. Obtain center-point readings over a stated interval after the scene appears stable. Retain the numerical series privately.
2. Switch to Thermal Field on the same target. Set ε = **0.96**, enter the recorded reflected input when known and use **Corrected** mode. If the official reflected input is unknown, record the chosen Thermal Field input and that unmatched condition explicitly.
3. Save an annotated image, original radiometric plane and JSON sidecar. Dump an original composite through the debug action when collecting a decode fixture. Record center readings and any deliberately placed comparison points using their original sensor coordinates.
4. Switch back to the official viewer for another sample. This helps expose target drift across the comparison rather than assigning every change to the software.
5. Report each point's signed delta as **Thermal Field minus official**, along with sample spread, timing and the two bracketing official samples. The requested comparison target is within **±1 °C**; mark a failure or an unresolved comparison rather than discarding an unfavorable point.

Suggested result columns:

```csv
target,point,sensor_x,sensor_y,thermal_field_c,official_before_c,official_after_c,delta_before_c,delta_after_c,emissivity,reflected_input_c,official_reflected_input_known,gain_readback,target_probe_c,sample_seconds,app_version,firmware,notes
```

The integrated 8–14 µm flat-response graybody model and short-range transmission of 1 remain assumptions. Agreement with the official app does not independently establish absolute temperature accuracy or validate every material, angle and environment.

## Direct-USB lifecycle and performance

Test the latest build in direct USB mode. Record the actual APK/version and readable firmware. Use both immediate cable reseats and longer disconnects, including the extension cable use case. Exercise detach during live view, a capture and a pending control operation; ensure each operation belongs to its intended session.

After each reattach, require fresh frame counters from the new session, inspect permission/status behavior and confirm that an older completion did not restore stale Live state. Sample app descriptor counts before and after the run; counts include graphics/IPC resources as well as USB. Record crashes, permission failures, malformed/overflow/source-gap counts and refusals. A bounded count in one run is not a general leak guarantee.

For the steady performance interval, retain received/rendered/presentation counts, fps, callback-to-swap and available callback-to-presentation timestamps. Apply representative measurement load and include orientation/full-screen transitions as a separately labeled condition. These timings start at receiver callback and exclude sensor acquisition latency. Capture requests and NUC/gain transitions should be identified in the log rather than averaged away.

Use the manual **Run NUC** action and observe command state, frame age, identical-content age and recovery. Unchanged content by itself does not prove a shutter event; a static scene can produce it. Treat a known command and observed freeze interval as correlated evidence, and distinguish stopped delivery from repeated radiometric content.

Record scoped outcomes in [VALIDATION.md](VALIDATION.md). Do not mark the physical gates complete from synthetic, desktop-only or network-only checks.

## Opt-in direct-USB automation

The framework test runner can exercise an already permitted physical camera without requiring its GUI to be visible:

```sh
adb -s "$PIXEL_ADB" shell am instrument -w -e usb_hardware true \
  com.thereprocase.thermalfield.test/com.thereprocase.thermalfield.ValidationInstrumentation
```

Install the matching arm64 debug/test APKs first. The test refuses emulator builds, missing cameras and absent USB permission. It opens using the saved USB gain, the alternate gain, and the saved gain again; verifies gain transitions and capture metadata; sends a NUC command; and counts USB descriptors after logical closure. The first phase includes one minute with eight full-plane boxes, eight full-width lines, isotherm and host correction. Later phases use twelve-second steady intervals. It preserves the saved gain preference and publishes numeric results only; captured pixel packets stay in process memory. Run it separately from other app/device tests.

This exercises logical fd/session ownership, not physical cable detach. ImageReader timings do not establish visible compositor presentation. Command completion plus advancing delivery does not establish a visible shutter freeze or radiometric accuracy. Inspect the result rather than relying only on the shell exit code: the framework runner reports failed assertions in its output.

## Mounting and selfie preview qualification

Use an asymmetric warm/cool target whose top and left can be identified thermally. Record manual Rotate/Flip/Mirror output settings before testing; retain the established mounting offset rather than silently clearing it.

1. With the camera facing away from the screen, select Facing away in Controls → View. At each of the four phone poses, compare normal/full-screen views and enter/exit full screen. Record any scene turn introduced by the transition and whether GUI text is readable.
2. Mount the camera toward the screen and select Selfie. Both portraits must retain the established upright orientation; both landscapes must avoid the reported inversion. The live preview must reverse left/right.
3. With Mirror output off, place a spot on the target's asymmetric warm feature and capture in each pose. Inspect the annotated PNG outside the app: the automatic selfie mirror must be absent, and the annotation must identify the same physical feature. Check JSON mounting, preview/output mirror flags and output rotation. Verify original raw words remain in sensor coordinates.
4. Repeat lock/unlock and manual Rotate/Flip/Mirror output checks. Manual Mirror output is an intentional export transform, separate from the automatic selfie preview mirror.

These physical checks are pending; emulator transform/export checks do not qualify mounting direction or actual on-phone display behavior.
