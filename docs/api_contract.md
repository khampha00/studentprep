# StudentPrep API Contract (v1)

This document serves as the strict contract between the frontend and backend teams. All endpoints must adhere to these standards to prevent integration drift.

## 1. Global API Standards

### 1.1 Success Response Wrapper
All successful responses (2xx) MUST be wrapped in a standard JSON object containing a `data` and optional `meta` property (used strictly for pagination).

```json
{
  "data": {
    // Array or Object
  },
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

### 1.2 Error Responses (RFC 7807)
All errors (4xx, 5xx) MUST conform to RFC 7807 Problem Details for HTTP APIs. Spring Boot 3 handles this natively when `spring.mvc.problem-details.enabled=true` is set.

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Exam payload validation failed.",
  "instance": "/api/v1/exams/active/sync",
  "errors": [
    {
      "field": "timeLeft",
      "message": "Must be greater than or equal to 0"
    }
  ]
}
```

### 1.3 Authentication & Authorization
All endpoints (except `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`) require a valid **access** JWT (`token_type: "access"`) passed in the Authorization header:
`Authorization: Bearer <access_token>`

*   **Expired or Missing Credentials:** Protected endpoints MUST return `401 Unauthorized` (RFC 7807 `ProblemDetail`) — never `403 Forbidden` — so clients automatically invoke `/api/v1/auth/refresh`.
*   **Evicted Session:** If the presented `jti` no longer matches Redis `session:<CANONICAL_ID>`, protected endpoints return `401 Unauthorized` with `detail: "Session active on another device"` and `code: "SESSION_EVICTED"`.
*   **Role Separation:**
    *   `ROLE_STUDENT`: Exam execution (`/api/v1/exams/**`) and own results (`/api/v1/students/me/**`, `/api/v1/student/results`).
    *   `ROLE_SUPERVISOR`: Read-only monitoring & directory views (`GET /api/v1/admin/analytics/**`, including `GET /api/v1/admin/analytics/flagged-sessions`, `GET /api/v1/admin/questions`, `GET /api/v1/admin/subjects`, `GET /api/v1/admin/students`, `GET /api/v1/admin/students/{id}`, `GET /api/v1/admin/students/{id}/results`). Mutating admin routes, `/export`, `/api/v1/admin/students/credentials/pdf` (`GET` & `POST`), `/*/slip`, and moderation mutations (`clear-late`, `void`, `grant-retake`) are forbidden (`403`).
    *   `ROLE_ADMIN`: Full administrative CRUD, PIN reset, workstation unlock, CSV & credential PDF exports, ingestion, HITL question approval, and exam moderation (`POST /api/v1/admin/exams/sessions/{sessionId}/clear-late`, `POST /api/v1/admin/exams/sessions/{sessionId}/void`, `POST /api/v1/admin/students/{id}/grant-retake`).

---

## 2. Authentication Module (`security` boundary)

### `POST /api/v1/auth/login`
Authenticates a user (Student or Admin) and issues an Access Token and `refreshToken` cookie. Subject to brute-force rate limiting (`429 Too Many Requests` with `Retry-After` after repeated failed attempts per identifier or IP burst limits).

**Request Body:**
```json
{
  "identifier": "123456JF",
  "pin": "49281"
}
```

**Response (200 OK):**
```json
{
  "data": {
    "accessToken": "eyJhbG...",
    "expiresIn": 900,
    "role": "ROLE_STUDENT"
  }
}
```
*(Note: The `refreshToken` JWT (`token_type: "refresh"`) is omitted from the JSON body and set as an `HttpOnly`, environment-configured `Secure`, `SameSite=Strict` (configurable) cookie scoped to `Path=/api/v1/auth`.)*

**Failure & Policy Responses:**
*   `401 Unauthorized`: Invalid identifier/PIN or deleted account.
*   `403 Forbidden`: Candidate flagged for malpractice OR legacy PIN requires admin reset (`code: "PIN_RESET_REQUIRED"`).
*   `409 Conflict`: Active exam (`IN_PROGRESS`) or active workstation session bound elsewhere (`isSameDevice == false` and no valid admin unlock clearance). Same-device reconnections presenting a matching `refreshToken` cookie resume seamlessly and rotate `jti`.

### `POST /api/v1/auth/refresh`
Exchanges a valid `refreshToken` cookie (`token_type: "refresh"`) matching Redis `session:<CANONICAL_ID>` `jti` for a fresh 15-minute access token (`token_type: "access"`). Subject to IP rate limiting (`429`).

**Response (200 OK):** `{ "data": { "accessToken": "eyJhbG...", "expiresIn": 900, "role": "ROLE_STUDENT" } }`

**Failure Responses:** `401 Unauthorized` (missing/invalid/wrong `token_type`/evicted `jti`), `403 Forbidden` (malpractice flagged).

### `POST /api/v1/auth/logout`
Verifies caller `jti` ownership (via `Authorization` header allowing expired verified JWT, or `refreshToken` cookie) before deleting Redis `session:<CANONICAL_ID>`. Clears the client `refreshToken` cookie unconditionally (`Max-Age=0`). An evicted or unauthenticated caller never terminates another active workstation's session.

**Response (200 OK):** Empty body + expired `refreshToken` cookie header.

---

## 2.5 Student Identity & Onboarding (`student` boundary — Admin Facing)

*   `POST /api/v1/admin/students` (`ROLE_ADMIN`): Creates candidate, generates one-time random 5-digit PIN, stores both irreversible authentication hash (`pin_hash`) and AES-256-GCM encrypted copy (`pin_ciphertext`), and returns `oneTimePin` **once** in response JSON.
*   `POST /api/v1/admin/students/bulk` (`ROLE_ADMIN`): Imports CSV cohort, persists `pin_hash` + `pin_ciphertext` atomically for each created student, and returns `{ "data": { "created", "errors", "credentials": [{ "name", "registrationNumber", "oneTimePin" }] } }` **once**.
*   `POST /api/v1/admin/students/{id}/reset-pin` (`ROLE_ADMIN`): Generates new one-time 5-digit PIN, updates both `pin_hash` and `pin_ciphertext`, clears `pinResetRequired`, revokes active Redis session/unlock keys, and returns `{ "data": { "newPin", "registrationNumber" } }` **once**.
*   `POST /api/v1/admin/students/{id}/unlock-session` (`ROLE_ADMIN`): Evicts current workstation `session:<CANONICAL_ID>` and writes a 15-minute single-use `exam_unlock:<CANONICAL_ID>` permit (audited in `audit_logs`) so an interrupted candidate may resume on a replacement workstation.
*   `GET /api/v1/admin/students` & `GET /api/v1/admin/students/{id}`: Returns student metadata (`id`, `name`, `registrationNumber`, `pinResetRequired`, `hasActiveSession`, `subjects`) with **no PIN, hash, or ciphertext fields**.
*   `GET /api/v1/admin/students/export` (`ROLE_ADMIN`): Downloads CSV roster (`Name,Registration Number,Registered Subjects`) with spreadsheet formula sanitization and **zero PIN columns**.
*   `GET /api/v1/admin/students/credentials/pdf` (`ROLE_ADMIN`, audited via `AuditLogAspect`): Downloads consolidated multi-page A4 OpenPDF credential roster (`Content-Type: application/pdf`, `Cache-Control: no-store`, attachment filename `student_credentials_roster.pdf`). Includes all registered students ordered deterministically by `name ASC, registrationNumber ASC`. Renders title banner, generation timestamp, candidate count, repeating table headers (`#`, `Candidate Name`, `Registration Number`, `Login PIN`), or `"No registered students found."` when empty. Preserves existing active PINs without disrupting active exam sessions, and auto-provisions a 5-digit PIN once for legacy/unissued candidates (`pinResetRequired = true` or `pin_ciphertext IS NULL`). Returns RFC 7807 `ProblemDetail` on `401 Unauthorized`, `403 Forbidden` (non-`ROLE_ADMIN` including `ROLE_SUPERVISOR`), or `500 Internal Server Error` (AEAD key mismatch / corrupt ciphertext or PDF I/O failure).
*   `POST /api/v1/admin/students/credentials/pdf` (`ROLE_ADMIN`, audited via `AuditLogAspect`): Accepts `{ "registrationNumbers": ["123456JF", ...] }` (`@NotEmpty`, `@Size(min = 1, max = 5000)`, non-blank entries; invalid body returns RFC 7807 `400 Bad Request`). Downloads batch A4 OpenPDF credential roster (`Content-Type: application/pdf`, `Cache-Control: no-store`, attachment filename `batch_credentials_roster.pdf`) built strictly from PostgreSQL student records matching `registrationNumbers` (client never supplies PINs), with the same active-PIN preservation, lazy legacy provisioning, and `401`/`403`/`500` error semantics.
*   `GET /api/v1/admin/students/{id}/slip` (`ROLE_ADMIN`, audited via `AuditLogAspect`): Downloads printable single-student credential slip PDF (`Content-Type: application/pdf`, `Cache-Control: no-store`, attachment filename `slip.pdf`, saved client-side as `${registrationNumber}-slip.pdf`) containing `Candidate Name`, `Registration Number`, `Login PIN: <5-digit PIN>`, and `Registered Subjects`. Preserves existing encrypted PIN across repeat downloads, provisions and persists a fresh 5-digit PIN once when `pinResetRequired = true` or `pin_ciphertext IS NULL`, and returns RFC 7807 `ProblemDetail` on `401`, `403`, `404 Not Found`, or `500`.

---

## 3. Ingestion & Question Bank (Admin Facing)

### `POST /api/v1/admin/ingestion/assets` (`ROLE_ADMIN`)
Uploads diagram/media assets for questions. Strictly restricted to validated raster images (`image/png`, `image/jpeg`, `image/gif`, `image/webp`) verified by magic bytes (`<= 5 MB`); active content such as SVG, HTML, or scripts is rejected with RFC 7807 `400 Bad Request` / `415 Unsupported Media Type`.

**Response (200 OK):** `{ "data": { "url": "<public-base-url>/<bucket>/diagrams/<uuid>.<ext>" } }`

### `POST /api/v1/admin/ingestion/pdf` (`ROLE_ADMIN`)
Uploads a past-questions PDF (`<= 10 MB`, `application/pdf`, `%PDF-` magic header) for layout analysis via authenticated `docling-parser` (`X-API-Key`), S3 uploading, and LLM structuring. Upstream errors stored in `ingestion_jobs` are stripped of API keys or query secrets.

**Content-Type:** `multipart/form-data`
*   `file`: The PDF file binary.
*   `subject`: (String) e.g., "MATH"
*   `year`: (String) e.g., "2020"

**Response (202 Accepted):**
```json
{
  "data": {
    "jobId": "uuid-1234",
    "status": "PROCESSING",
    "message": "PDF ingestion started in background."
  }
}
```

### `GET /api/v1/admin/questions`
Fetches questions for Human-in-the-Loop (HITL) review.

**Query Params:** `?status=DRAFT&page=0&size=20`

**Response (200 OK):**
```json
{
  "data": [
    {
      "id": "uuid-q-101",
      "subject": "MATH",
      "topic": "Calculus",
      "status": "DRAFT",
      "content": { 
        "type": "MCQ_LATEX",
        "text": "Evaluate: \\( x^2 \\)",
        "options": { "A": "...", "B": "..." },
        "correctOption": "A"
      }
    }
  ],
  "meta": { "page": 0, "size": 20, "totalElements": 40, "totalPages": 2 }
}
```

### `PUT /api/v1/admin/questions/{id}`
Updates a question (fixing LLM/OCR hallucinations) and approves it for live exams.

**Request Body:**
```json
{
  "status": "ACTIVE",
  "content": { 
     // Complete updated JSONB Payload
  }
}
```

---

## 4. Exam Engine (Student Facing — `/api/v1/exams/**`, `ROLE_STUDENT`)

### `POST /api/v1/exams/start`
Starts a new exam session or resumes an existing `IN_PROGRESS` session.
*   **Frozen Snapshot & Deterministic Permutation:** Generates or loads the immutable `snapshot_payload` stored in PostgreSQL, deterministically shuffled per session `shuffleSeed`. All `correctOption` fields are stripped recursively (including child questions).
*   **Deadline Calculation:** Computes `expectedEndTime = startTime + durationMinutes` (120 minutes).
*   **Single Sitting Policy:** A student may have at most one active attempt per exam.

**Response (200 OK):**
```json
{
  "data": {
    "sessionId": "uuid-session-123",
    "shuffleSeed": 4829103,
    "expectedEndTime": "2026-08-23T12:05:00Z",
    "serverTime": "2026-08-23T10:05:00Z",
    "payload": {
      "examId": "uuid-exam-2026",
      "durationMinutes": 120,
      "questions": [
        {
          "id": "q-101",
          "type": "MCQ_LATEX",
          "text": "Evaluate...",
          "options": { "A": "...", "B": "...", "C": "...", "D": "..." }
        }
      ],
      "contexts": {},
      "student": {
        "name": "Jane Doe",
        "registrationNumber": "123456JF"
      },
      "answers": {},
      "timeLeft": 7200,
      "tabSwitchCount": 0,
      "resumed": false
    },
    "statePayload": {},
    "resumed": false
  }
}
```

**Failure Responses:**
*   `401 Unauthorized`: Missing or invalid access JWT.
*   `403 Forbidden` (`code: "MALPRACTICE_BLOCKED"`): Candidate holds an active (`superseded = false`) `FLAGGED_TAB_SWITCH` session.
*   `409 Conflict` (`code: "EXAM_ALREADY_COMPLETED"`): Candidate already holds a non-superseded terminal session (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`), including when an expired `IN_PROGRESS` session is finalized on resume.

---

### `GET /api/v1/exams/active/payload`
Fetches the stripped question and context payload for the active exam.
*   **200 OK:** Serves stripped exam payload from the active `IN_PROGRESS` session's frozen `snapshot_payload` if one exists (falling back to fail-open catalog lookup only when no active session exists yet, never bypassing malpractice check).
*   **403 Forbidden** (`code: "MALPRACTICE_BLOCKED"`): Candidate holds an active `FLAGGED_TAB_SWITCH` session.

**Response (200 OK):**
```json
{
  "data": {
    "examId": "uuid-exam-2026",
    "shuffleSeed": 4829103,
    "durationMinutes": 120,
    "questions": [
      {
        "id": "q-101",
        "type": "MCQ_LATEX",
        "text": "Evaluate...",
        "options": { "A": "...", "B": "...", "C": "...", "D": "..." }
      }
    ],
    "contexts": {}
  }
}
```

---

### `GET /api/v1/exams/active/session` *(Strictly Read-Only)*
Fetches the last known session state from PostgreSQL. Used by client `examSlice` on initialization to handle multi-device failover and rehydration.
*   **Strictly Read-Only:** Executes in a `@Transactional(readOnly = true)` context without performing any database writes or finalization sweeps.
*   **200 OK (Active):** When `status == IN_PROGRESS` and `now <= expectedEndTime + syncGraceSeconds`:
```json
{
  "data": {
    "sessionId": "uuid-session-123",
    "status": "IN_PROGRESS",
    "startTime": "2026-08-23T10:05:00Z",
    "expectedEndTime": "2026-08-23T12:05:00Z",
    "serverTime": "2026-08-23T10:15:00Z",
    "shuffleSeed": 4829103,
    "timeLeft": 6600,
    "lastSyncedAt": "2026-08-23T10:14:30Z",
    "tabSwitchCount": 0,
    "answers": {
      "q-101": "B",
      "q-102": "A"
    }
  }
}
```
*   **200 OK (Null / Expired):** When the session has exceeded `expectedEndTime + syncGraceSeconds` (before the background sweeper runs) or when no active `IN_PROGRESS` session exists, returns `{ "data": null }` cleanly without throwing `500` or writing to the database.

---

### `POST /api/v1/exams/active/sync?sessionId={uuid}`
Periodic background sync endpoint to persist answers and proctoring strikes incrementally.
*   **Validation:** `ExamSyncRequest` MUST include `@NotNull Map<String, Object> statePayload`.
*   **Optimistic Locking:** Enforces ownership and `@Version` check, retrying or handling conflict safely.
*   **Monotonic Proctoring Strikes:** Computes `tabSwitchCount = max(existingCount, reportedCount)`. If `tabSwitchCount >= 3`, transitions session to `FLAGGED_TAB_SWITCH`, publishes `ExamSessionFlaggedEvent` (evicting Redis session token `AFTER_COMMIT`), and returns `403 Forbidden` (`code: "MALPRACTICE_BLOCKED"`).
*   **Terminal Session Guard:** If a non-final sync attempts to mutate an already terminal session (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`, `FLAGGED_TAB_SWITCH`), returns `409 Conflict` (`code: "SESSION_ALREADY_FINALIZED"`). Replays with `isFinal=true` remain idempotent.
*   **Deadline Guard:** If non-final and `now > expectedEndTime + syncGraceSeconds` (30s grace), closes the expired session cleanly and returns **`410 Gone`** (`code: "EXAM_WINDOW_EXPIRED"`).
*   **Delegated Finalization:** If `isFinal == true`, delegates to shared submission logic enforcing the 900s offline cutoff.

**Request Body:**
```json
{
  "statePayload": {
    "answers": { "q-101": "B", "q-103": "D" },
    "timeLeft": 5370,
    "tabSwitchCount": 1
  },
  "isFinal": false
}
```

**Response (200 OK):**
```json
{
  "data": {
    "status": "SYNCED",
    "serverTime": "2026-08-23T10:05:30Z"
  }
}
```

---

### `POST /api/v1/exams/active/submit?sessionId={uuid}&reason={reason}`
Final exam submission endpoint. Evaluates deadline windows and triggers idempotent grading via transactional outbox (`ExamSubmittedEvent`).
*   **Deadline Enforcement & Windows:**
    *   `now <= expectedEndTime + syncGraceSeconds` (30s): Finalizes with status `SUBMITTED` (`moderationStatus = ELIGIBLE`).
    *   `expectedEndTime + syncGraceSeconds < now <= expectedEndTime + offlineSubmitGraceSeconds` (900s / 15m): Finalizes with status `LATE_SUBMISSION_FLAGGED` (`moderationStatus = PENDING_REVIEW`), withholding result slip and leaderboard entry until admin review.
    *   `now > expectedEndTime + offlineSubmitGraceSeconds` (900s): Returns **`410 Gone`** (`code: "OFFLINE_CUTOFF_EXCEEDED"`). Session transitions to `TIME_EXPIRED`.
*   **Proctoring Termination:** If submitted with reason `TAB_SWITCH_EXCEEDED` or strikes `>= 3`, finalizes with status `FLAGGED_TAB_SWITCH` (`moderationStatus = FLAGGED`), publishes `ExamSessionFlaggedEvent`, evicts Redis session key, and suppresses grading and slips.
*   **Idempotency:** Repeated final submission calls on an already-finalized session are strictly idempotent (`200 OK` returning current terminal status, firing zero duplicate `ExamSubmittedEvent`).

**Request Body:**
```json
{
  "statePayload": {
    "answers": { "q-101": "B", "q-102": "A", "q-103": "D" },
    "timeLeft": 0,
    "tabSwitchCount": 1
  },
  "isFinal": true
}
```

**Response (200 OK):**
```json
{
  "data": {
    "status": "SUBMITTED",
    "serverTime": "2026-08-23T12:04:55Z",
    "moderationStatus": "ELIGIBLE"
  }
}
```

---

## 5. Student Results & Moderation Gating (`/api/v1/students/me/**`, `/api/v1/student/results`, `ROLE_STUDENT`)

### `GET /api/v1/students/me/results` & `GET /api/v1/student/results`
Returns candidate's graded exam results with moderation status metadata (`ELIGIBLE`, `PENDING_REVIEW`, `CLEARED`, `VOIDED`).
*   **Clean Status Display:** When a candidate holds an active (`superseded = false`) `FLAGGED_TAB_SWITCH` session and no eligible result, returns:
    ```json
    {
      "data": {
        "reviewStatus": "UNDER_REVIEW",
        "message": "Your examination attempt is under administrative review."
      }
    }
    ```
*   **Pending Review Banner:** For `PENDING_REVIEW` (`LATE_SUBMISSION_FLAGGED`), UI renders `"Submission Pending Review — Official Slip Withheld"`.

### `GET /api/v1/students/me/results/{resultId}/slip` & `/latest/slip`
Downloads the official printable student result slip PDF.
*   **403 Forbidden** (`code: "MALPRACTICE_REVIEW_REQUIRED"` or `"RESULT_PENDING_REVIEW"`): Access is strictly blocked whenever the candidate has an active `FLAGGED_TAB_SWITCH` session OR the target `ExamResult.moderationStatus` is `PENDING_REVIEW` or `VOIDED`.
*   **200 OK:** Only `ELIGIBLE` or `CLEARED` results generate a student PDF slip.

---

## 6. Admin Moderation & Exam Analytics (`/api/v1/admin/**`)

### `GET /api/v1/admin/analytics/results` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
Returns paginated list of all candidate examination results.
*   **Response (200 OK):** Wrapped in `PagedResponse` `{ "data": [...], "meta": { "page", "size", "totalElements", "totalPages" } }`.
*   **Null Safety:** Guaranteed null-safe serialization even when `topicBreakdown` is null.
*   **Fields:** `id`, `examSessionId`, `studentName`, `registrationNumber`, `totalScore`, `maxScore`, `topicBreakdown`, `gradedAt`, `moderationStatus`, `sessionStatus`.

### `GET /api/v1/admin/analytics/flagged-sessions` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
Returns active non-superseded `FLAGGED_TAB_SWITCH`, `LATE_SUBMISSION_FLAGGED`, and `TIME_EXPIRED` sessions with full student metadata (`studentId`, `studentName`, `registrationNumber`, `sessionId`, `status`, `tabSwitchCount`, `startTime`, `endTime`, linked `resultId`, and `moderationStatus` if graded) for administrative review.

### `POST /api/v1/admin/exams/sessions/{sessionId}/clear-late` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
Clears a late submission flag.
*   **Validation:** Verifies session exists and is in `LATE_SUBMISSION_FLAGGED` status. Returns `409 Conflict` if session is not late flagged.
*   **Action:** Promotes `ExamSession.status` to `SUBMITTED`, promotes `ExamResult.moderationStatus` to `CLEARED`, publishes `ExamModerationUpdatedEvent`, and updates candidate entry on Redis leaderboard (`LeaderboardService`).
*   **Authorization:** `ROLE_ADMIN` only. `ROLE_SUPERVISOR` receives `403 Forbidden`.

### `POST /api/v1/admin/exams/sessions/{sessionId}/void` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
Voids an examination attempt and result.
*   **Action:** Marks `ExamResult.moderationStatus = VOIDED`, removes candidate entry from Redis leaderboard, and preserves session audit trail.
*   **Authorization:** `ROLE_ADMIN` only. `ROLE_SUPERVISOR` receives `403 Forbidden`.

### `POST /api/v1/admin/students/{id}/grant-retake` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
Grants candidate permission for a fresh examination sitting.
*   **Action:** Marks current non-superseded `ExamSession` rows for candidate `{id}` as `superseded = true`, evicts active Redis session key, and enables the candidate to start one fresh exam sitting via `POST /api/v1/exams/start`.
*   **Authorization:** `ROLE_ADMIN` only. `ROLE_SUPERVISOR` receives `403 Forbidden`.

### `GET /api/v1/admin/exams/sessions/{sessionId}/script` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
Reconstructs the candidate's frozen question paper (`snapshot_payload`), submitted answers (`state_payload`), cryptographic SHA-256 Answer Receipt (`answerReceiptCode` + `answerSha256`), and timeline provenance (`lastClientSyncAt`, `submissionTrigger`) for dispute resolution.
*   **Authorization:** `ROLE_ADMIN` and `ROLE_SUPERVISOR` (`200 OK`). `ROLE_STUDENT` receives `403 Forbidden`.

### `GET /api/v1/admin/exams/sessions/{sessionId}/script/pdf` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
Generates a printable A4 PDF Examination Script Audit & Verification Report (`<CandidateName>_<RegNo>-Exam-Script-Audit.pdf`).
*   **Authorization:** `ROLE_ADMIN` and `ROLE_SUPERVISOR` (`200 OK`, `application/pdf`). `ROLE_STUDENT` receives `403 Forbidden`.
