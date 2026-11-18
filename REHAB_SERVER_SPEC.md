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
            │  3. pose/gait analysis  → gait_analysis + position_series        │
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
- **Right panel ("Body Deviation")** ← `gait_analysis.position_series` (scanner/pose data).
  If the user has no session yet, the client shows a "make a scan" placeholder.

The uploaded **video drives the scanner/pose output** (`gait_analysis`, `position_series`).
`pressure_data` is separate smart-insole telemetry — include it only when available for
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
  "gait_analysis": { /* GaitAnalysisResult */ },
  "pressure_data": { /* InsoleData */ } | null,
  "notes": ""
}
```
| field | type | notes |
|-------|------|-------|
| `id` | string | unique per session |
| `patient_uid` | string | owner uid |
| `timestamp_ms` | int64 | epoch millis of the recording |
| `duration_seconds` | int | recording length |
| `gait_analysis` | GaitAnalysisResult | required |
| `pressure_data` | InsoleData \| null | null when no insole data |
| `notes` | string | may be `""` |

### GaitAnalysisResult
```json
{
  "posture_score": 85.0,
  "balance_score": 87.0,
  "symmetry_score": 83.0,
  "scoliosis_score": 88.0,
  "position_series": [ { "time_ms": 0, "deviation_x": 1.2, "deviation_y": -0.4 } ],
  "abnormalities": ["Slight anterior lean detected"]
}
```
| field | type | range / units |
|-------|------|---------------|
| `posture_score` | float | 0–100 |
| `balance_score` | float | 0–100 |
| `symmetry_score` | float | 0–100 (gait L/R symmetry) |
| `scoliosis_score` | float | 0–100 (100 = straight spine) |
| `position_series` | BodyPosition[] | time-ordered samples |
| `abnormalities` | string[] | may be empty |

### BodyPosition
| field | type | units |
|-------|------|-------|
| `time_ms` | int64 | sample time |
| `deviation_x` | float | lateral deviation, cm (0 = ideal) |
| `deviation_y` | float | anterior-posterior deviation, cm |

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

### 3.2 `POST /rehab/sessions`
Kick off processing of a recorded video already uploaded to Firebase Storage.

Request:
```json
{ "video_path": "sessions/<uid>/pose_1731801600000.mp4", "duration_seconds": 420 }
```
Response (`202`/`200`):
```json
{ "task_id": "task_789", "status": "processing", "session": null }
```
- Validate that `video_path` lives under the caller's own `sessions/<uid>/` prefix.
- Server needs read access to the Firebase Storage bucket to fetch the video.

### 3.3 `GET /rehab/sessions/{task_id}/status`
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

---

## 4. Processing stages (step 2–6)

1. **Fetch** the video from `video_path` (Firebase Storage).
2. **Pose/gait analysis** (e.g. pose estimation over frames) →
   - `posture_score`, `balance_score`, `symmetry_score`, `scoliosis_score` (0–100),
   - `position_series` (body deviation over time, cm),
   - `abnormalities` (human-readable findings; `[]` if none).
3. **Insole data** — if the patient has smart-insole telemetry for this session, attach
   `pressure_data` (foot pressure maps + `cop_trajectory`); otherwise set `pressure_data: null`.
4. **Assemble** a `GaitSession` (new `id`, caller `patient_uid`, `timestamp_ms`,
   `duration_seconds` from the request, `notes` optional).
5. **Persist** under the user and set the task to `ready` with `session` populated.
6. On any unrecoverable error set the task to `failed`.

---

## 5. Seed data (demo/test user only)

Attach the following fixture to a **demo/test account only** (mirrors the client's former
`RehabMockData`, now owned by the server):

- **5 GaitSessions**, ~7 days apart, scores rising over time
  (posture/balance/symmetry/scoliosis ≈ 55 → 85; `duration_seconds` 300 → 420).
- Each session `position_series`: a damped-sine body-deviation curve
  (`deviation_x ≈ 10·e^(-2t)·sin(6πt)`, `deviation_y ≈ 6·e^(-1.5t)·cos(5πt)`, ~60 samples).
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