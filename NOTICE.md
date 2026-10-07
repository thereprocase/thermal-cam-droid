# Notices and provenance

Thermal Field project implementations are licensed under the root MIT license. Camera names identify compatibility; vendor logos and product images are not incorporated. Reference inspection and protocol facts are distinct from copied implementation.

## Reference repositories

Research snapshot: 2026-10-06. The following licenses were recorded before incorporation decisions.

| Repository | Revision inspected | License | Reuse policy |
| --- | --- | --- | --- |
| [LeoDJ/P2Pro-Viewer](https://github.com/LeoDJ/P2Pro-Viewer) | `23887289d3841fdae25c3a11b8d3eed8cd778800` | MIT | Primary protocol facts; reuse allowed with MIT notice. |
| [mbuesch/p2pro-rs](https://github.com/mbuesch/p2pro-rs) | `4c3330b6e8cb82e49605a3593348851028c9f538` | MIT | Android USB and decode reference; reuse allowed with MIT notice and existing attribution. |
| [fbreitwieser/thermal-camera-android](https://github.com/fbreitwieser/thermal-camera-android) | `3284ca085a38c585e6a9e105113e46ba4ab23aa2` | No app-source license found | Read-only architecture reference. No code/assets to be reused without a license grant. Gradle wrapper licensing does not license the app. |
| [alufers/thermal-cat](https://github.com/alufers/thermal-cat) | `870c380df4ce56249f3e23a7f2b75573cb0f01eb` | GPL-3.0 (README declaration) | Feature reference only. No source/assets copied. |
| [leswright1977/PyThermalCamera](https://github.com/leswright1977/PyThermalCamera) | `b19821a1a25e081594c666022a3d64e2b9cf41cc` | Apache-2.0 | Decode cross-check; any future reuse needs Apache license/notice handling. |
| [JimKnopfIoT/harbour-ircam](https://github.com/JimKnopfIoT/harbour-ircam) | `5d05f3682b44d0135ea68e2250bcf0275ebcd7b1` | GPL-3.0 license text | USB identity and documented decode facts only. No source/assets copied. |

thermal-camera-android: root LICENSE/COPYING and application-source license headers were absent; GitHub license API returned 404. Treat as unlicensed, not permissively licensed. thermal-cat declares GPL-3.0 in README.md:39; harbour-ircam ships GPL version 3 text. Neither may supply copied implementation.

## Additional references

- [Pinoerkel/P2Pro-Viewer](https://github.com/Pinoerkel/P2Pro-Viewer), commands-and-fixes revision `163860a8e5e0a3ad7fdee761bd65e0f1a60fc50d`: MIT. Shutter/control protocol facts from the unmerged command research; independently implemented.
- [crexodon/P2Pro-Viewer-Gui](https://github.com/crexodon/P2Pro-Viewer-Gui), revision `4b4ae7a2f13e6502359b9365cd76fa5e2c02a571`: MIT; control-reference inspection.
- [thereprocase/thereprocase.github.io](https://github.com/thereprocase/thereprocase.github.io), revision `53da9a0f62164df2a8898872e586e2dc168a282e`: no general source/asset redistribution license established during inspection. The operator requested Gridline. Its color, typography, rule and layout patterns are independently ported to Compose; original web CSS, logos and application code are not included.

## Incorporated dependencies

| Dependency | Version / revision | License and source |
| --- | --- | --- |
| libusb | 1.0.30, `87a55632db62c9bdc58cd31d3ccfa673f1bb017f` | LGPL-2.1-or-later; `third_party/libusb/COPYING`, preserved source headers and Android shared-library build. |
| libuvc | `047920bcdfb1dac42424c90de5cc77dfc9fba04d` | BSD-3-Clause; `third_party/libuvc/LICENSE.txt` and source headers. |
| IBM Plex Sans / Mono | Bundled regular and semibold font files | SIL OFL 1.1; full notice in `app/src/main/assets/licenses/IBM-Plex-OFL.txt`. |
| AndroidX / Compose | Versions pinned in `app/build.gradle.kts` | Apache-2.0; upstream AndroidX distribution notices. |
| Kotlin | Plugin version pinned in root Gradle KTS | Apache-2.0; upstream distribution notices. |
| Gradle wrapper | 9.6.1 | Apache-2.0; build tooling. |
| Android NDK C++ runtime | NDK r28c | LLVM Apache-2.0 with LLVM exception and legacy LLVM text, packaged in `assets/licenses/LLVM-runtime.txt`. |
| utlist | Header 1.9.1, included by libuvc | BSD notice preserved in the header and `assets/licenses/utlist-BSD.txt`. |
| Runtime Maven artifacts | Resolved release inventory | Primary license declarations and embedded notices recorded in `docs/RUNTIME_DEPENDENCIES.txt` and `assets/licenses/runtime-dependencies.txt`; Apache-2.0 text is packaged separately. |

The separate `libusb.so` is linked dynamically. Corresponding library source and build rules are supplied here. Recipients retain the LGPL rights to modify/replace/relink the library; the MIT project license does not restrict those rights. Preserved native source-header notices, dependency declarations/full embedded notices, license texts and font notices are included in APK assets. See docs/RELEASE.md for source/rebuild/signing details. This summary does not substitute for the full dependency texts.

FFmpeg is a separately installed desktop diagnostic/bridge executable; it is not bundled in the APK or this source tree. Its installed build determines its own license. The bridge uses Python's standard library and the system libusb runtime through ctypes.

An original vector icon and synthetic scene were created for this project. No GPL application source, unlicensed Android reference implementation, vendor image or proprietary camera SDK was copied into the app.

## Build automation

GitHub Actions checkout v5, setup-java v4 and upload-artifact v4 are MIT-licensed upstream actions, referenced at immutable commit hashes in docs/ci-template.yml. They are build automation, not APK libraries. Hosted CI activation is pending a credential with workflow scope. The AndroidX graphics-path runtime includes a third arm64 native library; its Maven declaration is Apache-2.0 and inspected native source-header attributions are packaged in assets/licenses/androidx-native-path.txt.
