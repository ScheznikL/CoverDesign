# Gait analysis in stable/v1

The gait feature was migrated from the local research prototype onto the public
`stable/v1` branch at commit `f516602096c61a455d1befc8b7d4a555ec96de9e`.
The Android application version is **1.3.1**, version code **14**.

## Open and build

Open this project directory in Android Studio. Use Android SDK Platform 36,
Build-Tools 36.0.0 and a JDK compatible with Gradle 9.1.0. The local build uses
Android Studio's bundled JBR. The Gradle wrapper is executable.

`androidApp/google-services.json` and `local.properties` are local, ignored
configuration files. The Firebase Android application must use package name
`com.example.coverdesign`. Replacing the Firebase configuration does not require
changing the gait algorithms. Email/password authentication must be enabled for
email sign-in. Google sign-in reads its client ID from the active configuration.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebugAndroidTest
```

For a compilation-only check before configuring Firebase:

```sh
./gradlew :androidApp:assembleDebug -PcompileWithoutFirebase=true
```

That option does not configure authentication or produce a substitute Firebase
account. Existing device installations also require the same signing key when
installing an update without uninstalling the application.

## Entry points

Patients can open **Gait analysis** from Home or the **Gait** navigation tab.
Patient Profile links to the same gait characteristics editor. The first visit
shows instructions and requires height, weight, age and sex settings.

The ordering and 3D-model workflows belong to the upstream application. Its
original **Record video** tool remains separate from gait analysis. Patient Home
now shows real local angle summaries and daily charts. The clinician dashboard
shows only patients who explicitly shared their Firebase results; the old fake
patient list and nonfunctional recommendation form are no longer used there.
Unrelated legacy rehabilitation history/recommendations remain labelled as demo.

## Colour selection: body photos only

This section applies to the coloured-marker modes. **Live without markers**
skips marker attachment, colour selection and calibration photographs entirely.

For live analysis, attach every marker before securing the phone. Select
**Calibrate on your body**, choose the front or back view, and stand at the
furthest intended walking distance during the countdown. Capture requires a
fresh full-body view. Return to the phone and select the actual marker centres
on the captured image. Zoom and drag the image to place the selections precisely.
Pairs require both visible markers. Apply the completed palette for that view,
then repeat for the other view. Retake, undo, replace selections and reset remain
available. Temporary calibration photos stay in memory and are not saved.

For video analysis, select a video, pause or seek to a clear front-facing frame,
and select the marker centres on that body image. Repeat on a clear back-facing
frame if analysing both views. Correct the mirror setting when necessary.
Colours are sampled separately for the two views. Every required group must be
selected before that view can be analysed.

There is no calibration mode that asks the user to hold individual pieces of
paper up to the camera. Marker colours are learned from the actual body images.
The lighting-tolerance control and colour-conflict checks remain available.

## Measurement and walking

### Live without markers

Select **Gait → Live without markers**. The camera uses the bundled MediaPipe
Pose Landmarker directly. After securing and leveling the phone, complete the
front and back body-point checks. Follow the same countdown, short-pass, stop
and turn cues as in marker mode. Pause/resume, saving partial results, profile
settings, history, summaries and daily statistics are available.

The method records five lines: shoulders, hip landmarks and knees from the front,
and shoulders and hip landmarks from the back. It produces **no C7–coccyx angle**.
Coordinates are camera-estimated joints, not the anatomical surface points of
the marker protocol. The two methods have separate comparison groups even if
their profile and camera settings match.

Front/back recognition uses consistent anatomical left/right shoulder and hip
ordering. Narrow side views, inconsistent labels and low-confidence required
points are rejected. This is a heuristic and cannot independently verify facing
direction when the model itself swaps anatomical labels. Stop and face squarely
towards or away from the camera when the requested view is not recognised.
Initial setup requires a full-body view; after setup, cropped head or feet do
not alone block angles while the required joints remain visible. Setup framing uses a separate lower-body visibility requirement, so occluded
rear knees and individual shoe points do not block good shoulder/hip tracking.
Known cropped feet still block setup. On a requested view change, or prolonged
missing/wrong-view tracking, POSE reacquires a fresh native tracker with bounded
reset frequency. It never labels the observed view from the requested view. The optional
step estimate becomes unavailable if feet cannot be tracked reliably.
Pose-mode walking evidence requires a sustained, visible change in shoulder,
hip and torso scale along the guided path; an estimated foot step alone cannot
fill the usable walking target. Small landmark jitter must not count as walking.

Colour segmentation and calibration snapshots are bypassed in pose mode. Live
video is processed locally and is not recorded. The mode is a research feature;
its angular accuracy is not established by successful implementation tests.

### Coloured-marker modes

The live feature retains sensor-assisted phone leveling and its manual grid
fallback, the body silhouette, marker checks, voice/bell feedback, compact
camera layout and pause actions: continue, save and exit, or discard and exit.
No video is recorded by live gait analysis.

The default short pass lasts **2 seconds**, with a target of **6 usable seconds
per view**, accumulated over repeated passes. These are engineering presets for
short home paths. Keep the phone upright and stable; its suggested lens height
is approximately half the person's height. Full-body visibility is required
for initial setup and body-photo capture. During walking the required markers
must remain visible; cropped head or feet do not alone stop angle measurement.

Front measurements: shoulders, pelvis and the line between knee markers relative
to the horizontal. Back measurements: shoulders and pelvis relative to the
horizontal, and the C7–coccyx marker line relative to the vertical. Pose estimates
help framing and marker matching; only observed coloured marker coordinates
produce the measured angles in marker mode. Rear attachment tolerance and bounded lighting
compensation are retained.

## Results and storage

Both live and imported-video analysis execute on the phone. Saved results include
the profile snapshot, protocol, `measurementMethod` (`MARKERS` or `POSE`), acquisition metadata, usable durations,
passes, tracking counts and time-series angles. The same account-scoped local
history format is retained. Original video, marker coordinates and calibration
images are not added to the saved session. Imported-video step counts are
unavailable; live step estimates can be partial when the feet are cropped.

Results show the typical inclination (`meanAbsolute`), signed mean, range and
graphs separately for each marker line and view. Session summaries, compatible
session comparisons, personal recorded ranges and daily statistics are retained.
Different protocols, sources or capture settings are not silently combined.
Daily video statistics use the date the analysis result was created, not an
inferred original recording date. Partial sessions remain identifiable.

These are projected surface-marker or estimated joint-line measurements. The knee
metric is the inclination of the line joining the knees, not knee flexion or
valgus; it changes through the step cycle. No validated population normal
range, diagnosis or overall health score is inferred. Numerical tests and
emulator checks do not establish measurement accuracy in clinical use.

### Firebase results and doctor access

Analysis remains on the phone. Saved sessions automatically upload a
compact numerical index and a full numerical report to the Firebase project selected by google-services.json:
`gaitReports/{patientUid}/sessions/{sessionId}`. The payload contains
`typicalInclinations` (mean absolute angles by FRONT/BACK line), per-line sample
counts, qualified metric keys, completion and tracking/duration metadata, source,
method and protocol. A deterministic compatibility fingerprint keeps different
settings apart without including the raw demographic profile. Missing values stay
absent, including BACK_SPINE in POSE. The protected `details/{index}` documents
now contain the complete ordered angle samples, the original saved profile and
all capture metadata. They preserve every numeric value, so the doctor uses the
same report renderer, signed statistics, per-session charts and comparisons as
the patient. No joint coordinates, colour calibration, photographs or video enter
the full report. The patient sharing screen explains the expanded numerical scope.

Full reports use a versioned binary format with lossless GZIP compression,
bounded chunks, SHA-256 and strict decoding. Parent index and all parts are
published atomically. Checksums and the regenerated compact summary must match
before the doctor can view graphs. Reports are fetched only under the existing
server-confirmed patient grant. Revocation or deletion clears an open report.

The doctor selects a patient, then **Open session report** on a saved session.
Only read access is provided. Previously uploaded summary-only records show an
explicit request to sync the updated patient app; missing curves are never
invented. On the next patient launch, locally available older sessions upload
full detail automatically. Comparisons identify older summary-only records
until their details arrive.

Open **Gait → Results & doctor access** for status and retry. A server-acknowledged
write is required before an upload is marked synced. Local files remain available
when Firebase is unreachable or denies access. Retries run while the process is
alive and on the next launch; this is not a scheduled background worker. Deleting
a session queues a durable Firebase deletion for reconnection. Signing in on another phone does not automatically restore the patient's local
history; the doctor's authorized detail view fetches full reports on demand.

A doctor account obtains a random invitation code from the clinician dashboard.
The patient enters that code and explicitly shares their reports. Only that
linked doctor can read them; selecting DOCTOR at registration alone grants no
patient access. The patient can stop sharing at any time. Clinician data requires
a server-confirmed grant and is cleared on revocation, account changes or lost
server authorization. Roles are self-selected in the upstream app, not verified
medical credentials. The dashboard displays real numerical reports and comparable
daily charts, without clinical normality scores.

The Firebase access contract and tested rules are in **firebase-gait/**. Its
`firestore.dev.rules` is ONLY for a fresh development database; for an author's
existing project, merge the gait rules with the existing reviewed app rules and
check overlapping allows. Replacing google-services.json must be accompanied by
the corresponding Auth providers, Firestore rules and app signing setup.
The current development database is coverdesign-dev, Frankfurt (europe-west3).
The reviewed 1.3.1 development rules, including protected detail chunks, have been
published there with explicit owner approval and confirmed in Firebase Console.
The full transport passed three native Firebase-emulator scenarios, including
exact report equality, revocation, offline retry and a 10,000-sample migration.
224 JVM checks, 31 report UI checks and 91 DEV-rule checks passed. These checks
used local Firebase emulators; no synthetic
numerical reports were uploaded to the live project for testing. A different
project still requires separate rules deployment. Screenshots remain allowed.

## Preserved measurement protocols

Live marker capture remains protocol **7** and imported marker video remains
protocol **8**. Markerless live capture uses protocol **9** and saves an explicit
measurement method. Older sessions without this field load as `MARKERS` without
rewriting their original records. The history schema remains version **1**.
The bundled
Pose Landmarker Lite model is the same model as the preceding prototype.

Retest on Galaxy S22 under the intended lighting: body-photo colour selection,
front/back marker checks, natural short passes, turning guidance, cropped feet,
pause/resume, imported video and repeated independently recorded measurements.
