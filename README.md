# CoverDesign

The local **1.3.1** Android build is based on `stable/v1` and includes live gait
analysis with estimated pose landmarks or coloured markers, marker-video analysis,
session summaries and separate daily statistics for each measurement method.
Marker colours are selected only from body images. Real Home charts, compact
Firebase result sync, explicit patient-to-doctor sharing and full doctor session
reports with the same per-session graphs as the patient are included;
Full-report Firebase rules are published in the configured `coverdesign-dev`
project, including protected detail chunks for session graphs. A
different Firebase project needs its own reviewed rules (see `firebase-gait/`).
This branch integrates the module onto the author's updated stable/v1 at6f574ef.
See [Ukrainian handoff](HANDOFF_UK.md), [author Firebase setup](firebase-gait/AUTHOR_SETUP_UK.md)
and [integration verification](INTEGRATION_VERIFICATION.md).
See [Gait analysis setup and behaviour](GAIT_ANALYSIS.md).

A Kotlin Multiplatform (KMM) application for designing, configuring, and ordering customized prosthetic covers, insoles, and accessories. The app supports AI-driven 3D model generation from user specifications and AR-based virtual try-on powered by ARCore.

---

## Tech Stack

| Category | Libraries / Tools |
| --- | --- |
| Languages & Platform | Kotlin 2.4.0, Kotlin Multiplatform (KMM), JVM 17 |
| UI | Compose Multiplatform 1.11.0, Material3 1.11.0-alpha07, Navigation Compose 2.9.8 |
| 3D / AR | SceneView 4.18.0, ARSceneView 4.18.0, ARCore 1.54.0 |
| Networking | Ktor Client 3.5.0 (OkHttp engine), Kotlinx Serialization |
| Image Loading | Coil3 3.5.0 (Ktor backend) |
| Backend / Firebase | Firebase BOM 34.15.0, GitLive Firebase Multiplatform (Auth, Firestore, Storage) |
| Authentication | Google Sign-In (googleid 1.2.0), Androidx Credentials 1.6.0, Biometric 1.2.0-alpha05 |
| Local Storage | SQLDelight 2.3.2 (multiplatform), DataStore Preferences 1.2.1, Security Crypto 1.1.0 |
| DI | Koin 4.2.1 (core, Android, Compose) |
| Async | Kotlinx Coroutines 1.11.0 |
| Accompanist | Flow Layout, System UI Controller, Permissions — all 0.34.0 |
| Build | AGP 9.0.1, Gradle Kotlin DSL, Version Catalog (`libs.versions.toml`) |
| Testing | JUnit 4.13.2, Kotlin Test, Espresso 3.7.0, Compose UI Test |

---

## Features (stable/v1)

- **Authentication** — Google Sign-In, role selection (user / admin), biometric unlock, encrypted token storage
- **Product Configuration** — per-category sizing (EU foot size, clothing sizes, ring sizes), material selection (Silicone, Carbon Fiber, etc.), color/texture picker with hex input, photo upload for reference
- **3D Model Generation** — AI-driven model generation from configuration; background status polling; generated model URL download
- **3D Model Preview** — SceneView-based interactive 3D viewer with generation task state tracking
- **AR Virtual Try-On** — ARCore + ARSceneView camera overlay for prosthetic cover try-on; camera permission handling
- **Order Management** — create orders for any product type; dynamic pricing engine (base + material + texture + quantity); order review before submission; order history and detail view; local persistence via SQLDelight
- **Rehabilitation Tracking** — rehab history records, gait session data, prosthetic type and suspension type enumerations, limb measurement capture

---

## Project Structure

```
CoverDesign/
├── androidApp/          # Android Compose entry point, navigation, screens
├── shared/              # KMM shared module
│   └── src/
│       ├── commonMain/  # data/, domain/, ui/, di/
│       ├── androidMain/ # Android-specific implementations
│       └── iosMain/     # iOS-specific implementations
├── desktopApp/          # JVM desktop entry point
├── iosApp/              # Swift/Xcode entry point
└── gradle/
    └── libs.versions.toml  # centralized version catalog
```

---

## Build Requirements

- **minSdk:** 30 (Android 11)
- **targetSdk / compileSdk:** 36 (Android 15)
- **JDK:** 17
- **IDE:** Android Studio Ladybug or newer (with KMM plugin)

---

## Running the App

```bash
# Android
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :desktopApp:run
# or with hot reload:
./gradlew :desktopApp:hotRun --auto
```

iOS: open the `/iosApp` directory in Xcode and run from there.

---

## Running Tests

```bash
# Android (host)
./gradlew :shared:testAndroidHostTest

# Desktop (JVM)
./gradlew :shared:jvmTest

# iOS Simulator
./gradlew :shared:iosSimulatorArm64Test
```
