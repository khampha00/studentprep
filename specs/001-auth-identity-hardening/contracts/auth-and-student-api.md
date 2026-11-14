# API Contract: Authentication & Student Identity Lifecycle (Slice 001)

All responses use `{ "data": ... }` (or `{ "data": [...], "meta": {...} }` for paginated lists) on `2xx`, and RFC 7807 `ProblemDetails` (`type`, `title`, `status`, `detail`, `instance`, optional `code`/`errors`) on `4xx`/`5xx`.

---

## 1. Authentication Endpoints (`/api/v1/auth/**`)

### `POST /api/v1/auth/login`
Authenticates candidate or staff member and binds single workstation session.
- **Rate Limits**: Max 5 failed login attempts per identifier / 15 min + max 60 attempts / min per IP -> `429 Too Many Requests` (`Retry-After` header).
- **Request**: `{ "identifier": "JAMB-2026-X7Y8Z9", "pin": "49281" }`
- **Responses**:
  - `200 OK`: `{ "data": { "accessToken": "...", "expiresIn": 900, "role": "ROLE_STUDENT" } }` + `Set-Cookie: refreshToken=...; HttpOnly; Secure=<env>; SameSite=<env:Strict>; Path=/api/v1/auth; Max-Age=604800`
  - `401 Unauthorized`: Bad credentials or deleted student (`ProblemDetail`).
  - `403 Forbidden`: Malpractice block OR `pinResetRequired == true` (`code: "PIN_RESET_REQUIRED"`, `detail: "Your PIN has been invalidated and must be reset by an administrator."`).
  - `409 Conflict`: Active session or `IN_PROGRESS` exam bound to another workstation (`isSameDevice == false` and no valid `exam_unlock` grant).

### `POST /api/v1/auth/refresh`
Exchanges a valid `refreshToken` cookie (`token_type = "refresh"`) matching Redis `session:<CANONICAL_ID>` `jti` for a fresh short-lived access JWT.
- **Rate Limits**: Max 30 requests / min per IP (`429 Too Many Requests`).
- **Responses**:
  - `200 OK`: `{ "data": { "accessToken": "...", "expiresIn": 900, "role": "..." } }`
  - `401 Unauthorized`: Missing/expired/invalid cookie, wrong `token_type` (`access` token rejected), deleted/reset student, or `jti` evicted in Redis (`ProblemDetail`).
  - `403 Forbidden`: Candidate flagged for exam malpractice (`ProblemDetail`).

### `POST /api/v1/auth/logout`
Verifies session `jti` ownership before revoking server-side Redis session state; clears client cookie unconditionally.
- **Inputs**: Optional `Authorization: Bearer <accessToken>` header (allowed expired only after HMAC signature verification) AND/OR `refreshToken` cookie.
- **Behavior**: Deletes Redis `session:<CANONICAL_ID>` **only if** caller presents a signature-verified token whose `jti` equals stored Redis `jti`. Evicted or unauthenticated callers never log out another workstation.
- **Response**: `200 OK` (empty body + expired `Set-Cookie: refreshToken=; ...; Max-Age=0`).

### Error Signal on Expired/Evicted Access JWT (All Protected Routes)
- Missing or expired JWT -> HTTP `401 Unauthorized` `ProblemDetail` (`title: "Unauthorized"`, `detail: "Authentication required or token expired"`) — **never `403`**.
- Valid access JWT with mismatched Redis `jti` -> HTTP `401 Unauthorized` `ProblemDetail` (`detail: "Session active on another device"`, `code: "SESSION_EVICTED"`).

---

## 2. Admin Student Management Endpoints (`/api/v1/admin/students/**`)

**Authorization**: All mutating endpoints (`POST`, `PUT`, `DELETE`), `/export`, and `/*/slip` require `ROLE_ADMIN`. `ROLE_SUPERVISOR` is limited to read-only `GET` directory/detail/results views without any credentials.

### `POST /api/v1/admin/students` (`ROLE_ADMIN`)
Creates a student via validated `CreateStudentRequest` (`@NotBlank name`, optional `examCenter`, `Set<UUID> subjectIds` — never binding raw entity fields or client-supplied `id`/`pin`/`pinHash`), generates a one-time random 5-digit PIN, stores only its Argon2id hash, and returns the plaintext PIN **once**.
- **Request**: `{ "name": "Ada Lovelace", "examCenter": "Center 1", "subjectIds": ["uuid-1", "uuid-2"] }`
- **Response (`201 Created` or `200 OK`)**:
  ```json
  {
    "data": {
      "id": "uuid",
      "name": "Ada Lovelace",
      "registrationNumber": "JAMB-2026-A1B2C3",
      "examCenter": "Center 1",
      "subjects": [...],
      "oneTimePin": "73914"
    }
  }
  ```

### `PUT /api/v1/admin/students/{id}` (`ROLE_ADMIN`)
Updates student metadata via validated `UpdateStudentRequest` (`@NotBlank name`, optional `examCenter`, `Set<UUID> subjectIds`) without altering `pin_hash` or exposing credentials.

### `POST /api/v1/admin/students/bulk` (`ROLE_ADMIN`)
Imports CSV cohort (`Name,Exam Center,Subject 1..4`). Generates one-time random PINs per created candidate and returns `credentials` **once** inside that response only.
- **Response (`200 OK`)**:
  ```json
  {
    "data": {
      "created": 2,
      "errors": [],
      "credentials": [
        { "name": "Ada Lovelace", "registrationNumber": "JAMB-2026-A1B2C3", "oneTimePin": "73914" }
      ]
    }
  }
  ```

### `POST /api/v1/admin/students/{id}/reset-pin` (`ROLE_ADMIN`)
Generates a new random 5-digit PIN, updates both `students.pin_hash` and `users.pin_hash` (with `pinResetRequired = false`), revokes active Redis session/unlock keys for that registration number, and returns `{ "data": { "newPin": "58204", "registrationNumber": "..." } }` **once**.

### `POST /api/v1/admin/students/{id}/unlock-session` (`ROLE_ADMIN`)
Clears active workstation lock for Q2 recovery: deletes Redis `session:<CANONICAL_ID>` (immediately evicting the previous workstation) and sets a 15-minute single-use `exam_unlock:<CANONICAL_ID>` permit if an exam session is `IN_PROGRESS` (or pre-exam lock needs clearing), recorded in `audit_logs`.
- **Response (`200 OK`)**:
  ```json
  {
    "data": {
      "studentId": "uuid",
      "registrationNumber": "JAMB-2026-A1B2C3",
      "unlocked": true,
      "message": "Active workstation lock cleared. Candidate may re-authenticate once."
    }
  }
  ```

### `GET /api/v1/admin/students` & `GET /api/v1/admin/students/{id}`
Returns student directory/profile metadata **without** `pin` or `pinHash`:
`{ "id", "name", "registrationNumber", "examCenter", "pinResetRequired": false, "hasActiveSession": true, "subjects": [...] }`.

### `GET /api/v1/admin/students/export` (`ROLE_ADMIN`)
Downloads CSV roster (`Name,Registration Number,Exam Center,Registered Subjects`) with spreadsheet formula injection sanitization (`=`, `+`, `-`, `@`, `\t`, `\r` neutralized). Contains **zero** PINs.

### `GET /api/v1/admin/students/{id}/slip` (`ROLE_ADMIN`)
Downloads registration slip PDF containing candidate details and redacted credential status (`"Login PIN: Issued separately (hashed) — use Admin PIN Reset to issue a new one-time PIN"`).
