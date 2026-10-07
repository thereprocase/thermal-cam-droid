# Release process

Published APKs use a project-specific release signer. Signing material stays in the ignored `private/` directory and is never committed or included in the APK. The project code is MIT; bundled dependencies retain their own terms and notices.

Build prerequisites and versions are in README.md. To reproduce an unsigned release:

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest
cmake -S . -B build/native
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

For your own signed build, create a private keystore and an ignored `private/signing.properties` file:

```properties
storeFile=private/release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=release
keyPassword=YOUR_PRIVATE_PASSWORD
```

Keep the signer stable for updates. A build signed with your own key cannot update a differently signed installation. `-PvalidationApplicationId=com.example.thermalfield.validation` builds a separate application ID for local release-code testing without replacing an existing app's private data; it is not a published package name.

Regenerate Java/Kotlin runtime declarations after dependency changes:

```sh
./gradlew :app:runtimeLicenseInventory
python3 tools/generate_notices.py
```

The generator reads resolved artifacts and primary Maven POM license declarations, retaining embedded distribution notices. It refuses a changed/non-Apache declaration until explicit handling is added. Local cache paths remain in ignored build output; public inventory contains coordinates and primary URLs only. Native license/copyright notices and font notices are packaged under `assets/licenses`.

libusb is a separate LGPL-2.1-or-later `libusb.so`; its pinned source and Android build are supplied in this repository. You may modify/replace that library and rebuild/relink/re-sign the app under your own signer. Distribution terms do not prohibit the modification or reverse engineering needed to debug those changes. The native project build is `app/src/main/cpp/CMakeLists.txt`; the dependency snapshot is `third_party/libusb/REVISION`. This source release includes those corresponding build materials.

Before upload, verify APK signatures, arm64-only contents, minimum/target SDK, 16 KiB native page and ZIP alignment, packaged notices and a privacy scan. Include the matching complete source archive and checksums with each APK release. Verification results are engineering evidence, not a legal opinion or a claim of physical temperature accuracy.

The initial version is a development prerelease. Physical camera-baseline qualification, official-app bath comparisons and broader lifecycle/measurement acceptance gates remain recorded in docs/VALIDATION.md and repository issues.

The reviewed GitHub Actions workflow is supplied as docs/ci-template.yml. GitHub rejected creating the workflow with the current OAuth credential because it lacks workflow scope. A maintainer with that scope may install it under .github/workflows/ci.yml. Until then, validation is run locally and hosted CI is not claimed active.
