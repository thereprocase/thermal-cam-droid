# Thermal Field

An independent, open-source Android thermal viewer for the USB device `0bda:5830` used by the InfiRay P2 Pro. Built for building-enclosure thermography, with a Gridline interface, native capture and lossless radiometric export. This project is not affiliated with the camera manufacturer.

**Development preview.** Live USB, experimental network capture and host radiometric correction have run on a Pixel 9 Pro. Measurement tools are still in progress. Comparison against the official app on ice-water and approximately 55 °C water targets has **not** been completed; camera-apparent and model-corrected values are not independently validated surface temperatures.

## Current features

- Native USB capture through Android UsbManager, a borrowed file descriptor, libusb and libuvc. No proprietary camera SDK.
- GPU rendering of the bottom 256 × 192 radiometric plane; the camera's AGC preview is ignored for display.
- Ironbow, white-hot and rainbow; center/min/max markers; °C/°F; automatic or manually locked span with deliberate clipping.
- Rotate in 90° steps, mirror, 180° flip and a separate screen-rotation lock.
- Capture an annotated PNG, original 16-bit grayscale radiometric PNG and JSON sidecar through MediaStore, under `Downloads/ThermalField`.
- Volume Down captures the annotated view; Volume Up or X selects raw as the preferred share item. Every capture still saves all three files. Share one image, the raw plane or the complete set through Android's chooser.
- Manual NUC and gain controls; frame-age and performance diagnostics; debug composite-frame dumps.
- Global emissivity/reflected apparent temperature inputs and raw/corrected display using an integrated 8–14 µm Planck model. Extrema, center, GPU coloring and capture metadata share the same per-frame lookup table. Invalid solutions are magenta and excluded from extrema.
- Original synthetic demo for UI development, explicitly labeled as synthetic.
- Experimental lossless network source from the included desktop bridge. It requires this documented protocol; ordinary JPEG/MJPEG streams do not provide its radiometric plane.

Planned: editable spots, boxes, line profiles, ΔT, isotherms, saved-capture reanalysis and further lifecycle/accuracy qualification. See [feature scope](docs/FEATURES.md) and [validation](docs/VALIDATION.md).

<img src="docs/synthetic-export.png" width="320" alt="A rotated synthetic enclosure export with temperature markers, palette legend and an explicit synthetic-data caption">

Original synthetic enclosure example, corrected at ε = 0.96 and reflected input 20 °C. The illustrated temperatures are test data, not a physical camera measurement. Source, correction inputs, legend and timestamp are measurement context rather than a branding watermark.

## Build and install

Requirements: JDK 21, Android SDK platform 37, NDK `28.2.13676358`, CMake `3.22.1`. The APK supports **arm64-v8a only**, Android 16/API 36 or newer, and targets API 37. Install the SDK components with Android Studio or `sdkmanager`, then set `sdk.dir` in an ignored `local.properties` file.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The bundled Gradle wrapper downloads its pinned distribution. Grant Camera and USB permissions for direct USB capture. Android 17 also requests local-network access for the network source. Keep capture in the foreground; backgrounding pauses the source. The app responds to USB attach for the supported vendor/product ID.

Host regression tests need a C++17 compiler and CMake:

```sh
cmake -S . -B build/native
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

Native decoding is tested against a captured composite fixture. Radiometric PNG tests independently decode all 49,152 words and verify exact preservation. Synthetic radiometry tests verify numerical behavior, not camera accuracy.

## Radiometric meaning

The received stream is 256 × 384 YUYV. The lower half contains unsigned little-endian words with the documented conversion `T_C = word / 64 - 273.15`. Raw exports preserve those words in sensor orientation, independently of display rotation, mirroring, palette and span. PNG stores 16-bit samples in PNG's big-endian order; pixel values remain unchanged.

The camera has readable/writable correction properties. Startup uses the bench-tested settings: distance 32, reflected/atmospheric temperature 300 K, emissivity/transmission 128, high gain. Register readback was checked, but the physical interpretation of this baseline has **not** been independently qualified. A maximum register value alone does not prove emissivity or transmission is physically one. JSON states this qualification explicitly. Network gain is currently exported as unknown until per-frame bridge state is integrated.

The correction core integrates Planck radiance over 8–14 µm, assumes a flat spectral response and atmospheric transmission of one at short range, solves the single-band graybody equation and inverts a lookup table. It does not use a temperature-to-the-fourth approximation. Raw/corrected mode selects the display and numeric-readout model; the raw file always preserves the original camera words. The JSON records the actual selected mode and inputs. The default reflected temperature of 20 °C is an input default, not a measured environment value. Nonpositive solved radiance and temperatures outside the table domain are invalid, displayed as magenta, excluded from extrema and exported as JSON null readings where applicable.

For numerical/export verification of a synthetic demo capture, install Pillow on the development host and run:

```sh
python3 tools/verify_capture.py fixtures/synthetic-enclosure.yuyv CAPTURE_raw.png CAPTURE.json
```

This compares every original word and evaluates min/max/center using finer direct Planck quadrature and bisection, independently of the app's interpolation table. Passing it does not establish physical camera accuracy.

## Experimental desktop bridge

With the supported camera connected to a Linux host, install FFmpeg and Python 3 plus the host libusb runtime. The included bridge validates the device identity and forwards complete original composites. It can issue bounded NUC/gain commands when USB device permissions allow.

```sh
python3 tools/desktop_bridge.py --device /dev/video0 --bind 127.0.0.1 --port 8010
```

For a phone, bind explicitly to a trusted LAN or Tailscale interface and enter `http://HOST:8010/radiometric` in the app's Network source dialog. The prototype has **no application authentication**; access control must come from the trusted interface/firewall/Tailnet policy. Do not expose it to the public internet. The stream is approximately 4.9 MB/s before network overhead. Frame sequence gaps and receiver-to-display latency are reported; the latter excludes network transit and sensor exposure.

## Privacy and licensing

No account, analytics or cloud service is required. There is no location permission. Exports omit persistent camera identifiers and the network URL. Network addresses remain local app preferences. A user's scene and timestamp can still be identifying: review your own images before sharing or publishing them. Public examples use original synthetic data.

Project code is MIT; dependencies retain their own licenses. libusb is a separately built LGPL-2.1-or-later shared library, with corresponding source and Android build rules included under `third_party/libusb`. See [NOTICE](NOTICE.md) before reusing or redistributing. No vendor product images, logos, proprietary camera libraries or GPL application code are included. Camera names identify compatibility only.
