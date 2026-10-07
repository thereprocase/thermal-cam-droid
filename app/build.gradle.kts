import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigning = Properties().apply {
    val settings = rootProject.file("private/signing.properties")
    if (settings.isFile) settings.inputStream().use { load(it) }
}

android {
    namespace = "com.thereprocase.thermalfield"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = providers.gradleProperty("validationApplicationId").orElse("com.thereprocase.thermalfield").get()
        minSdk = 36
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "com.thereprocase.thermalfield.ValidationInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Native APIs used here are available through API 35; the APK
                // retains its API 36 runtime floor and API 37 target behavior.
                arguments += "-DANDROID_PLATFORM=android-35"
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes.getByName("debug").versionNameSuffix = "-dev"
    if (releaseSigning.isNotEmpty()) {
        signingConfigs.create("projectRelease") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("projectRelease")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

tasks.register("runtimeLicenseInventory") {
    doLast {
        val entries = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
            .joinToString("\n") { "${it.moduleVersion.id}\t${it.file.absolutePath}" }
        val destination = layout.buildDirectory.file("runtime-artifacts.tsv").get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(entries + "\n")
        println("Resolved runtime artifact inventory written under the build directory")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
