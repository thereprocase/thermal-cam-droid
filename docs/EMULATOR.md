# Emulator validation

Use an Android 16/API 36 or newer x86_64 AVD for visible UI and synthetic-source integration checks. The native capture/decoder/renderer implementation is shared with the arm64 build. An emulator run does not qualify physical USB capture, sensor calibration, shutter behavior or Pixel performance.

## Create and start

Set `THERMAL_SDK` to your Android SDK installation. This creates a dedicated AVD without modifying another project's emulator.

```sh
THERMAL_SDK=/path/to/android-sdk
"$THERMAL_SDK/cmdline-tools/latest/bin/sdkmanager" 'system-images;android-36;default;x86_64'
printf 'no\n' | "$THERMAL_SDK/cmdline-tools/latest/bin/avdmanager" create avd \
  --name thermal_field_api36 --package 'system-images;android-36;default;x86_64' --device pixel_9_pro
"$THERMAL_SDK/emulator/emulator" -avd thermal_field_api36 -port 5556 \
  -accel on -gpu swiftshader -no-audio -no-snapshot
```

Add `-no-window` for headless operation; ADB screenshots and accessibility controls remain available. Wait for `adb -s emulator-5556 shell getprop sys.boot_completed` to return `1`. The serial in these commands follows the selected emulator port.

Check `emulator -accel-check` in the environment that will run the emulator. A sandbox can hide `/dev/kvm` even when the host supports KVM. Our initial sandboxed software CPU run crashed during boot; the host KVM run booted successfully. That observation does not establish that software emulation fails on other hosts. See Android's [acceleration documentation](https://developer.android.com/studio/run/emulator-acceleration).

For development on a workstation with an accelerated GPU, prefer a verified `-gpu host` run over continuous SwiftShader rendering. The tested Intel host required its normal desktop display/Xauthority environment even for a headless AVD; use your own desktop environment rather than copying machine-specific paths. A run with `-cores 2` passed the integration checks with substantially lower observed CPU use. Results and sampling limits are in VALIDATION.md. Run Gradle with `--max-workers=2` when reducing host load, and close the test AVD with `adb -s emulator-5556 emu kill` when it is not needed. Keep SwiftShader as a compatibility option, not an unattended default live-preview loop.

## Build and check

Follow the emulator commands in the [README](../README.md#build-and-install). `-PemulatorValidation=true` selects only x86_64, uses an isolated application ID and disables release variants. Explicit release-task requests with that option fail before building. Default device/release builds remain arm64-only. Both configurations write the same Gradle output paths, so install or copy an APK before switching configurations.

The framework integration runner checks native saved-frame rendering, lossless export/reopening, publication failure cleanup, freshness during a synthetic transport pause and queued control cancellation. It removes its own temporary capture files. The UI checker uses only the emulator package and tests full-screen controls, rotation/mirroring, reflected-temperature sign entry and the source label after Home/return. It may dismiss Android's first-use immersive-mode explanation.

The UI checker reads accessibility bounds to scroll the controls pane beneath the pinned thermal viewport. It rejects physical-device serials. A source-label check after Home/return is narrower than a rendering/freshness check; native integration and the separate lifecycle checker cover their respective paths.

Do not use emulator frame rate or swap timing to claim the Pixel meets the 25 Hz target. Synthetic inputs and an emulator GPU do not reproduce the physical camera path.

## Process-restart layout check

After installing the emulator app and test APKs, run these phases in order:

```sh
adb -s emulator-5556 shell am instrument -w -e layout_restart seed com.thereprocase.thermalfield.emulator.test/com.thereprocase.thermalfield.ValidationInstrumentation
adb -s emulator-5556 shell am force-stop com.thereprocase.thermalfield.emulator
adb -s emulator-5556 shell am instrument -w -e layout_restart verify com.thereprocase.thermalfield.emulator.test/com.thereprocase.thermalfield.ValidationInstrumentation
```

Confirm the seed phase reports success before stopping the process. The verifier checks a different process ID and exact native layout restoration, then restores the original layout preference and removes its cache evidence. Complete the verify phase after a successful seed; the seed intentionally leaves the synthetic layout installed between phases. These optional phases reject a physical-device app package. The regular integration runner independently checks full-capacity layout restoration, clearing, invalid data and archive preference isolation.

## Four-way sensor orientation

After installing the isolated emulator build, run:

```sh
python3 tools/check_emulator_orientation.py --adb "$ANDROID_SDK_ROOT/platform-tools/adb" --serial emulator-5556
```

This injects four accelerometer directions through the emulator console, checks that full screen requests `fullSensor`, observes all four display rotations, and checks screen-lock/unlock behavior. It restores upright acceleration and exits full screen afterward. Run it separately from the UI checker and integration runner; these checks share the emulator activity. It does not establish the physical camera mounting offset on a phone.

## Dense annotation and text-scale inspection

```sh
python3 tools/check_emulator_annotation_labels.py --adb "$ANDROID_SDK_ROOT/platform-tools/adb" --serial emulator-5556
```

This emulator-only check seeds sixteen synthetic, densely anchored measurements, sets text scale to 1.5, waits for portrait/landscape activity layouts and saves screenshots under `/tmp/thermal-annotation-check`. Inspect the screenshots; artifact generation alone is not a visual pass. It restores preferences, text scale and rotation settings. A private recovery record is retained until restoration completes and used on the next run after interruption. Do not publish that preference record.

Host-GPU rotation testing produced a segmentation-fault exit in one run. For the recorded software-graphics fallback, the entire emulator process was pinned to two host CPU cores with `taskset -c 0,1`, alongside `-cores 2 -gpu swiftshader`. This limits workstation load; it is not a production performance configuration or a claim about other hosts.
