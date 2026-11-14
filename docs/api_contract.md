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
    *   `ROLE_SUPERVISOR`: Read-only monitoring & directory views (`GET /api/v1/admin/analytics/**`, `GET /api/v1/admin/questions`, `GET /api/v1/admin/subjects`, `GET /api/v1/admin/students`, `GET /api/v1/admin/students/{id}`, `GET /api/v1/admin/students/{id}/results`). Mutating admin routes, `/export`, `/api/v1/admin/students/credentials/pdf` (`GET` & `POST`), and `/*/slip` are forbidden (`403`).
    *   `ROLE_ADMIN`: Full administrative CRUD, PIN reset, workstation unlock, CSV & credential PDF exports, ingestion, and HITL question approval.

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

## 4. Exam Engine (Student Facing)

### `GET /api/v1/exams/active/payload`
Fetches the massive JSON payload for the active exam. The backend serves this directly from the Redis Cache.

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
         // SECURITY WARNING: correctOption MUST BE STRIPPED from this payload
      }
    ]
  }
}
```

### `GET /api/v1/exams/active/session`
Fetches the last known session state from PostgreSQL. Used heavily by the frontend `examSlice` on initialization to handle multi-device failover and rehydration.

**Response (200 OK):**
```json
{
  "data": {
    "sessionId": "uuid-session-123",
    "status": "IN_PROGRESS",
    "timeLeft": 5400,
    "lastSyncedAt": "2026-08-23T10:05:00Z",
    "answers": {
      "q-101": "B",
      "q-102": "A"
    }
  }
}
```

### `POST /api/v1/exams/active/sync`
Background Web Worker endpoint to persist state changes incrementally. Highly concurrent, must be extremely fast.

**Request Body:**
```json
{
  "answers": {
    "q-101": "B",
    "q-103": "D"
  },
  "timeLeft": 5370,
  "tabSwitchCount": 1
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

### `POST /api/v1/exams/active/submit`
Final exam submission. Triggers the `ExamSubmittedEvent` via the Transactional Outbox for grading.

**Request Body:**
```json
{
  "answers": { "q-101": "B", "q-102": "A", "q-103": "D" },
  "timeLeft": 0,
  "tabSwitchCount": 1
}
```
**Response (200 OK):**
```json
{
  "data": {
    "status": "SUBMITTED",
    "message": "Exam successfully submitted."
  }
}
```
