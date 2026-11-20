# Rehabilitation Backend — Pipeline & API Schema

Specification the server must implement so the CoverDesign client can fetch per-user
rehabilitation data and process newly recorded scans. The client already contains the
matching DTOs/mappers; **field names and types below are the contract — match them exactly**.

- Base URL (dev): `http://10.0.2.2:8000/` (Android emulator → host `localhost:8000`)
- Transport: JSON over HTTP, Ktor client. All keys are **snake_case**.
- Auth: every endpoint is authenticated as the current user (same scheme as `/orders`,
  `/model`, `/auth`). Resolve the caller's `uid` from the auth token; never trust a
  client-supplied uid.

---

## 1. Pipeline overview

```
            ┌─────────────┐      records video       ┌──────────────────────┐
  Patient → │ SCAN screen │ ───────────────────────▶ │ Firebase Storage      │
            └─────────────┘   sessions/<uid>/x.mp4    │ sessions/<uid>/*.mp4  │
                   │                                   └──────────┬───────────┘
                   │ POST /rehab/sessions {video_path}            │ server reads video
                   ▼                                              ▼
            ┌────────────────────────────────────────────────────────────────┐
            │ BACKEND                                                          │
            │  1. create task (status=processing), return task_id              │
            │  2. fetch video from Firebase path                               │
            │  3. pose/gait analysis  → gait_report (per-line inclinations)    │
            │  4. (optional) attach smart-insole pressure_data if available    │
            │  5. build GaitSession, persist under the user                    │
            │  6. task.status = ready, task.session = <GaitSession>            │
            └────────────────────────────────────────────────────────────────┘
                   ▲                                   │
                   │ GET /rehab/sessions/{task_id}/status (client polls)
                   │                                   ▼
            client shows processing dialog → on "ready" reloads GET /rehab
```

Client panels consume the result as:
- **Left panel ("COP Trajectory")** ← `pressure_data.cop_trajectory` (smart-insole data).
  If `pressure_data` is `null`, the client shows a "connect insole" placeholder.
- **Right panel ("Body-Line Tilt" / "Body-Line Inclination")** ← `gait_report.typical_inclinations`
  (scanner/pose data; mean body-line tilt per metric, in degrees).
  If the user has no session yet, the client shows a "make a scan" placeholder.

The uploaded **video drives the scanner/pose output** (`gait_report`, the per-metric inclination
angles). `pressure_data` is separate smart-insole telemetry — include it only when available for
that user/session, otherwise send `null`.

---

## 2. Data model (JSON schema)

### GaitSession
```json
{
  "id": "s1",
  "patient_uid": "abc123",
  "timestamp_ms": 1731801600000,
  "duration_seconds": 420,
  "gait_report": { /* GaitReport */ },
  "pressure_data": { /* InsoleData */ } | null,
  "notes": ""
}
```
| field | type | notes |
|-------|------|-------|
| `id` | string | unique per session (= the uploaded video's id) |
| `patient_uid` | string | owner uid |
| `timestamp_ms` | int64 | epoch millis of the recording |
| `duration_seconds` | int | recording length |
| `gait_report` | GaitReport | required |
| `pressure_data` | InsoleData \| null | null when no insole data |
| `notes` | string | may be `""` |

### GaitReport
Mirrors the on-device gait engine's numeric output. Values are **mean-absolute inclination
angles in degrees** per body line and camera view — *not* 0–100 health scores. Metric keys are
a fixed set: `FRONT_SHOULDERS`, `FRONT_PELVIS`, `FRONT_KNEES`, `BACK_SHOULDERS`, `BACK_PELVIS`,
`BACK_SPINE` (a `POSE` recording omits `BACK_SPINE`).
```json
{
  "typical_inclinations": { "FRONT_SHOULDERS": 2.5, "FRONT_PELVIS": 2.0, "FRONT_KNEES": 2.8 },
  "sample_counts":        { "FRONT_SHOULDERS": 80,  "FRONT_PELVIS": 80,  "FRONT_KNEES": 80 },
  "qualified_metrics":    ["FRONT_SHOULDERS", "FRONT_PELVIS", "FRONT_KNEES"],
  "measurement_method": "MARKERS",
  "acquisition_source": "VIDEO",
  "protocol_version": 7,
  "accepted_frames": 500,
  "rejected_frames": 40,
  "front_valid_duration_ms": 210000,
  "back_valid_duration_ms": 210000,
  "comparison_group_id": "0000…(64 hex)"
}
```
| field | type | range / units |
|-------|------|---------------|
| `typical_inclinations` | map<string,float> | metric key → mean-absolute tilt, degrees 0–90 |
| `sample_counts` | map<string,int> | per-metric usable sample count (same keys) |
| `qualified_metrics` | string[] | metrics with enough data to trend (subset of the keys) |
| `measurement_method` | string | `"MARKERS"` \| `"POSE"` |
| `acquisition_source` | string | `"LIVE"` \| `"VIDEO"` |
| `protocol_version` | int | capture protocol (e.g. 7) |
| `accepted_frames` / `rejected_frames` | int | tracking frame counts |
| `front_valid_duration_ms` / `back_valid_duration_ms` | int64 | usable per-view duration |
| `comparison_group_id` | string | 64-hex fingerprint of capture settings; only sessions sharing it are comparable |

### InsoleData (nullable on GaitSession)
```json
{
  "left_foot":  { "heel": 0.35, "midfoot": 0.15, "forefoot": 0.35, "toes": 0.15 },
  "right_foot": { "heel": 0.33, "midfoot": 0.17, "forefoot": 0.34, "toes": 0.16 },
  "cop_trajectory": [ { "time_ms": 0, "x": 0.0, "y": 20.0 } ],
  "step_count": 700,
  "cadence": 100.0
}
```
| field | type | notes |
|-------|------|-------|
| `left_foot` / `right_foot` | FootPressureMap | pressure fractions |
| `cop_trajectory` | CopPoint[] | center-of-pressure path |
| `step_count` | int | |
| `cadence` | float | steps/min |

### FootPressureMap
`heel`, `midfoot`, `forefoot`, `toes` — each float, fraction **0–1** (ideally sum ≈ 1).

### CopPoint
| field | type | units |
|-------|------|-------|
| `time_ms` | int64 | |
| `x` | float | medial-lateral mm (+ = lateral) |
| `y` | float | anterior-posterior mm (+ = forward) |

### DoctorRecommendation
```json
{
  "id": "r1",
  "doctor_name": "Dr. Petrenko",
  "timestamp_ms": 1731628800000,
  "tag": "insole_based",
  "text": "Adjust left insole angle by 2 degrees..."
}
```
`tag` ∈ `"insole_based"` | `"photogrammetric"` (lowercase; unknown values map to
`insole_based` on the client).

### GaitProfile (user-scoped)
Biometrics + capture settings for the current user. Stored at a **user location** (e.g.
`rehab_profiles/<uid>`), independent of any single session. The server reads it when analysing a
video so it can calibrate centimetre scale and age/sex-sensitive scoring.
```json
{
  "height_cm": 181,
  "weight_kg": 77,
  "sex": "Male",
  "age": 35,
  "pass_steps": 4,
  "target_steps_per_view": 12,
  "pass_duration_seconds": 2,
  "target_duration_seconds_per_view": 6,
  "updated_at": 1731801600000
}
```
| field | type | range / notes |
|-------|------|---------------|
| `height_cm` | int | 40–250 |
| `weight_kg` | int | 10–350 |
| `sex` | string | `"Female"` \| `"Male"` \| `"Other"` \| `"Prefer not to say"` |
| `age` | int | 1–120 |
| `pass_steps` | int | 2–10 (legacy step-based protocol) |
| `target_steps_per_view` | int | 4–100 (legacy step-based protocol) |
| `pass_duration_seconds` | int | 2–4 |
| `target_duration_seconds_per_view` | int | 6–60 |
| `updated_at` | int64 | epoch millis the client last wrote the profile |

The profile is **not** embedded on returned `GaitSession`s — sessions persist separately and link to
the owner by `uid`; the server joins the stored profile at analysis time.

---

## 3. Endpoints

### 3.1 `GET /rehab`
Returns the current user's full rehab payload.
```json
{
  "sessions": [ /* GaitSession, newest first preferred */ ],
  "recommendations": [ /* DoctorRecommendation */ ]
}
```
- New / unknown users: return `{ "sessions": [], "recommendations": [] }` (HTTP 200).
  **Do not fabricate data for arbitrary users** — empty arrays drive the client's
  "connect insole" / "make a scan" placeholders.

### 3.2 `GET` / `PUT /rehab/profile`
The current user's `GaitProfile` (biometrics + capture settings), resolved from the auth token.
- `GET` returns the stored `GaitProfile`, or `404` if the user has never saved one.
- `PUT` upserts it idempotently (body = `GaitProfile`); returns the stored profile. Validate the
  ranges in the `GaitProfile` table; reject out-of-range values with `400`.

### 3.3 `POST /rehab/sessions`
Kick off processing of a recorded video already uploaded to Firebase Storage.

Request:
```json
{
  "video_path": "sessions/<uid>/pose_1731801600000.mp4",
  "duration_seconds": 420,
  "measurement_method": "POSE",
  "acquisition_source": "VIDEO",
  "views": ["FRONT", "BACK"]
}
```
- `measurement_method` ∈ `"MARKERS"` \| `"POSE"` (marker vs. markerless analysis); optional.
- `acquisition_source` ∈ `"LIVE"` \| `"VIDEO"`; optional.
- `views` ⊆ `["FRONT","BACK"]`; may be empty when the capture flow does not distinguish views.
- Older clients may omit these fields — treat them as absent, not an error.
- The server joins the caller's stored `GaitProfile` (§3.2) for biometrics; it need not be resent here.
Response (`202`/`200`):
```json
{ "task_id": "task_789", "status": "processing", "session": null }
```
- Validate that `video_path` lives under the caller's own `sessions/<uid>/` prefix.
- Server needs read access to the Firebase Storage bucket to fetch the video.

### 3.4 `GET /rehab/sessions/{task_id}/status`
Poll processing status. The client polls with back-off (~3s → 15s, up to ~60 attempts).
```json
{ "task_id": "task_789", "status": "processing", "session": null }
```
When finished:
```json
{ "task_id": "task_789", "status": "ready", "session": { /* GaitSession */ } }
```
On failure:
```json
{ "task_id": "task_789", "status": "failed", "session": null }
```
- `status` ∈ `"processing"` | `"ready"` | `"failed"` (lowercase).
- On `ready`, the produced `GaitSession` must also be persisted so the subsequent
  `GET /rehab` includes it.

### 3.5 `POST /rehab/sessions_data`
Client-computed **fallback upload**. The Android gait module computes the report on-device; the
client posts it so the server can persist the result immediately even when its own
video-analysis module (§3.3/§3.4) is unavailable or faulty.

Request body = a full `GaitSession` (§2). The `id` carries the uploaded video's session id, so a
later server-side analysis of the same video can reconcile against it.
```json
{
  "id": "pose_1731801600000",
  "patient_uid": "ignored — resolved from auth",
  "timestamp_ms": 1731801600000,
  "duration_seconds": 8,
  "gait_report": { /* GaitReport */ },
  "pressure_data": null,
  "notes": ""
}
```
- Resolve the owner from the auth token; **ignore any client-supplied `patient_uid`**.
- Validate `gait_report` the same way as a produced report (metric keys, angle ranges 0–90).
- Upsert by `id` under the caller and persist so `GET /rehab` reflects it. Echo the stored
  `GaitSession` (200).
- Idempotent: re-posting the same `id` replaces the stored session.

---

## 4. Processing stages (step 2–6)

1. **Fetch** the video from `video_path` (Firebase Storage).
2. **Pose/gait analysis** (e.g. pose estimation over frames) → a `gait_report`:
   - `typical_inclinations` (mean-absolute tilt per body line, degrees 0–90),
   - `sample_counts` + `qualified_metrics` (which lines had enough data to trend),
   - `measurement_method`, `acquisition_source`, `protocol_version`, frame/duration counts,
   - `comparison_group_id` (fingerprint of the capture settings used).
3. **Insole data** — if the patient has smart-insole telemetry for this session, attach
   `pressure_data` (foot pressure maps + `cop_trajectory`); otherwise set `pressure_data: null`.
4. **Assemble** a `GaitSession` (new `id`, caller `patient_uid`, `timestamp_ms`,
   `duration_seconds` from the request, `notes` optional).
5. **Persist** under the user and set the task to `ready` with `session` populated.
6. On any unrecoverable error set the task to `failed`.

> If the analysis module is unavailable, the client-computed report from §3.5 is the fallback
> source of truth for that session.

---

## 5. Seed data (demo/test user only)

Attach the following fixture to a **demo/test account only** (mirrors the client's former
`RehabMockData`, now owned by the server):

- **5 GaitSessions**, ~7 days apart, inclination angles **decreasing** over time (improving):
  per-line `typical_inclinations` ≈ 8° → 2.5°; `duration_seconds` 300 → 420;
  `measurement_method = "MARKERS"`, `acquisition_source = "VIDEO"`, `protocol_version = 7`,
  all six metrics `qualified`.
- Each session `pressure_data.cop_trajectory`: a lissajous path
  (`x = 30·sin(t)`, `y = 20·sin(2t + π/4)`, ~120 points); foot maps as in the InsoleData
  example; `cadence ≈ 100`.
- **2 DoctorRecommendations**: one `insole_based`, one `photogrammetric`.

All other / newly-registered users start with **empty** rehab data until they submit a scan.

---

## 6. Errors

- Use standard HTTP status codes; auth failures → `401`.
- A missing/forbidden `video_path` → `400`/`403`.
- Unknown `task_id` → `404`.
- Keep the JSON body shapes above stable; the client deserializes strictly by these keys.