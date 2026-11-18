# Gait-result access rules

This directory is an isolated local emulator harness and a **rules fragment for merging**.
It is not a replacement for the application's production rules. Its `firebase.json`
points to a stand-alone file which intentionally denies access to every unrelated
collection. **Do not deploy this configuration to the production project.**

## Local verification

```sh
cd firebase-gait
npm ci
npm test
npm run test:dev
```

The launcher uses project `demo-coverdesign-gait`, binds Firestore to
`127.0.0.1:8185`, and runs a local test suite. No Firebase account is needed.
It uses `JAVA_HOME`, or the JBR bundled with Android Studio on macOS.
Emulator dependencies may need an initial network download; the test data never
leaves the emulator. Dependencies are pinned in `package-lock.json`.

`npm test` checks the isolated fragment. `npm run test:dev` runs the same gait
checks plus base account/configuration/order checks against `firestore.dev.rules`.
The latter is generated with `npm run build:dev` from the unchanged gait fragment
and `firestore.dev-base.rules.inc`; tests reject a stale generated file.

Verified on 2026-11-18 with Firebase CLI 15.36.0, Firestore emulator 1.22.0 and
Android Studio JBR 25: original compact-only rules **56/56 passed**; extended
numerical-detail fragment **83/83 passed** and development rules **91/91 passed**, including an atomic parent
plus 64 full-size detail-chunk batch. Firebase CLI has no signed-in accounts on this Mac;
no cloud deployment or data writes were performed by these checks.

For native Android integration tests, `npm run emulators:android` starts persistent
local Auth (`127.0.0.1:9098`) and Firestore (`127.0.0.1:8185`) emulators with the
same demo project and DEV rules. In the Android Emulator, connect a dedicated
Firebase app configured for project `demo-coverdesign-gait` to `10.0.2.2:9098`
and `10.0.2.2:8185`. The emulator UI is disabled. Stop the local server only after
the native tests finish; it holds no production data.

## Data contract

- `gaitDoctorCodes/{code}`: `doctorUid`, `doctorDisplayName`, `createdAt`.
  Codes are 32 uppercase hexadecimal characters (a random UUID without hyphens).
  Only a user whose existing `users/{uid}.role` is `DOCTOR` can create their own
  immutable invitation or delete it. Patients can retrieve a known code; no client
  can list the code collection.
- `gaitShares/{patientUid}`: `patientUid`, `doctorUid`, `doctorCode`,
  `doctorDisplayName`, `patientDisplayName`, `active`, `updatedAt`.
  A `PATIENT` account can create/update its own grant to a valid invited doctor.
  The doctor name must equal the invitation's name. Patients can revoke or delete
  the grant; an inactive grant can be saved even if the doctor deleted the code.
- `gaitReports/{patientUid}/sessions/{sessionId}`: the exact compact report fields
  checked by `gaitValidReport`. The owner can read/write/delete their own reports;
  a doctor can only read reports while their patient grant is active, their role
  remains `DOCTOR`, and the matching invitation still exists.
- `gaitReports/{patientUid}/sessions/{sessionId}/details/{chunkId}`: optional
  bounded numerical session details for reconstructing the same session report
  and time-series charts. Decimal chunk IDs are `0` through `63`, without leading
  zeroes. Each document has exactly integer `index` equal to its ID and string
  `payload` containing nonempty base64, at most 131,072 characters with length
  divisible by four. Create/update requires the owning `PATIENT`; the active linked
  doctor has read access while the parent report and its manifest still exist.
  Owners may delete chunks even after the parent is gone.

`createdAt` and `updatedAt` are positive integer epoch milliseconds, with at most
five minutes of device clock skew into the future. Report `createdAt` is immutable.
Names are at most 120 characters, and `comparisonGroupId` is an opaque 64-character
lowercase SHA-256 compatibility fingerprint, not a raw user profile.

`typicalInclinations` maps the optional keys `FRONT_SHOULDERS`, `FRONT_PELVIS`,
`FRONT_KNEES`, `BACK_SHOULDERS`, `BACK_PELVIS`, `BACK_SPINE` to numbers in `[0,90]`
degrees. A `POSE` report cannot contain `BACK_SPINE`. These are mean absolute
angles, not a diagnosis or a clinical normality score. Missing measurements stay
absent; zero is a measured zero. Empty maps are allowed for incomplete drafts.
`sampleCounts` has exactly the same keys and integer values from 1 to 30,000,
bounded by `acceptedFrames`. `qualifiedMetrics` contains unique measured keys.

`completed`, `manualLevel`, accepted/rejected frame counts, per-view usable
durations, protocol version, method and source preserve acquisition context.
The compact parent accepts no raw samples, coordinates, photographs, video URLs,
colour calibration or demographic profile. It validates shape and access; a
phone-created result is still self-reported data, not a trusted medical finding.

The parent may additionally contain `detailManifest`, without changing
`schemaVersion: 1` or invalidating old compact-only reports. The manifest has
exactly these fields:

```text
formatVersion: 1
chunkCount: integer 1..64
sha256: 64 lowercase hexadecimal characters
compressedBytes: integer 1..6291456
uncompressedBytes: integer 1..12582912
```

Write a new manifest and its compressed/base64 numerical details atomically.
Chunk validation uses `getAfter(parent)` and requires `index < chunkCount`, so the
manifest may be added or replaced in the same batch. Delete old excess chunks in
that batch. Firestore does not cascade deletes; deleting a report must also clean
up its detail documents. A doctor cannot read remaining orphan chunks once the
parent disappears, and the owner can still remove them.

Rules enforce ownership, grant, shape and size boundaries. They cannot establish
that every expected chunk was written, that compressed data matches its declared
byte lengths/hash, or that decoded content contains only valid numerical session
data. The Android decoder must enforce all of those checks, strict base64 and
bounded decompression, and refuse incomplete or corrupted details. Base64 syntax
alone does not enforce content type. Numerical details are not a video-upload
facility; photographs and video remain outside this integration.

## Required queries and UI behavior

A doctor obtains the list with **both** equality filters:

```text
gaitShares.whereEqualTo("doctorUid", signedInDoctorUid).whereEqualTo("active", true)
```

For a selected patient, query that patient's `gaitReports/{patientUid}/sessions`
collection. Do not use a collection-group query across all patients. The rules do
not grant that operation. No composite index is required for this two-equality
query with normal automatic single-field indexing; add no ordering to the grant
query unless its index has been reviewed.

Clear clinician screen state when authentication, patient selection or sharing
changes. Require a server-confirmed grant before showing medical results and do
not silently fall back to stale cached clinician data after revocation. Revocation
blocks future server reads; it cannot erase information a doctor has already seen.

Roles in the current app are chosen during registration. A `DOCTOR` role is not a
professional-license check. Explicit patient consent provides the access boundary.
The existing `/users` rules must prevent someone changing *another user's* role.

## Production integration

`firestore.dev.rules` and `firebase.dev.json` are a **separate option for a fresh
development database**. Initially `coverdesign-dev` had no Firestore database;
a new locked database was created in Frankfurt (europe-west3), then the owner
approved publication of the original compact-only rules. Firebase Console confirmed
that release. The optional `detailManifest` and nested `details` extension was then reviewed
and published in coverdesign-dev with separate explicit owner approval. The local
test runners themselves never deploy rules or write to the live project.
The DEV base paths add only own-profile get/create/update, own configurations and own
orders. Profile creation permits `PATIENT` or `DOCTOR`; UID, role and creation date
cannot be changed later. There is no profile listing, profile deletion, global
doctor access, technician workflow, Storage rule change or public allow rule.
Existing Firebase Auth users with no Firestore profile may create their own
`PATIENT` profile without touching an existing profile or changing its role.
Use this full file only for that reviewed new development database; retain the
merge procedure below for any existing database. Publishing either form requires
the project owner's normal release process and confirmation.

1. Obtain the **currently deployed** Firestore rules from an authorized owner.
   Preserve that exact source and all unrelated collection rules.
2. Merge the `gait*` functions and the three gait match blocks inside the existing
   `/databases/{database}/documents` block. Use rules version 2. Do not paste the
   outer wrappers twice.
3. Check overlapping matches. Firestore grants access if **any** matching allow is
   true; a broad `/{document=**}` allow must be narrowed so it cannot bypass the gait
   restrictions. Adding a restrictive block cannot cancel an existing broad allow.
4. Run these tests against the merged source too (the test suite loads
   `firestore.gait.rules`; point it to a local reviewed merged file for that check).
   Test normal account, order and configuration flows under the merged rules.
5. Only then deploy the reviewed merged rules from the owner's normal Firebase
   configuration. This folder intentionally has no deployment script, production
   project ID, service account or `.firebaserc`.

If a `PERMISSION_DENIED` appears, surface the failure and retain the report locally
for a retry. Never switch to open rules to make a demo work.

Official references: [rules unit tests](https://firebase.google.com/docs/rules/unit-tests),
[query authorization](https://firebase.google.com/docs/firestore/security/rules-query),
[overlapping match statements](https://firebase.google.com/docs/firestore/security/rules-structure#overlapping_match_statements).
