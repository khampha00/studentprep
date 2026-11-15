# API & Event Contracts: Slice 003 (`003-exam-kiosk-integrity`)

All responses MUST conform to `docs/api_contract.md` (`{ data, meta }` for 2xx, RFC 7807 `ProblemDetail` for 4xx/5xx). Implementation of Slice 003 MUST update `docs/api_contract.md` alongside code changes.

## 1. Student Exam Endpoints (`/api/v1/exams/**`, `ROLE_STUDENT`)

### `POST /api/v1/exams/start`
- **200 OK**: Starts new exam session (`snapshot_payload` frozen in DB, `expectedEndTime = startTime + 120m`) or resumes existing `IN_PROGRESS` session using its frozen `snapshot_payload` + `shuffleSeed`.
  - Response `data` includes: `sessionId`, `shuffleSeed`, `expectedEndTime` (ISO-8601), `serverTime` (ISO-8601), `payload` (`questions` with all `correctOption` recursively stripped, `contexts`, `durationMinutes`, `student`, `answers`, `timeLeft`, `tabSwitchCount`, `resumed`), `statePayload`, `resumed`.
- **403 Forbidden** (`code: "MALPRACTICE_BLOCKED"`): Candidate holds an active (`superseded = false`) `FLAGGED_TAB_SWITCH` session.
- **409 Conflict** (`code: "EXAM_ALREADY_COMPLETED"`): Candidate already holds a non-superseded terminal session (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`), including when an expired `IN_PROGRESS` session is finalized on resume.

### `GET /api/v1/exams/active/payload`
- **200 OK**: Serves stripped exam payload from active `IN_PROGRESS` session's frozen `snapshot_payload` if one exists (falling back to fail-open catalog lookup only when no active session exists yet, never bypassing malpractice check).
- **403 Forbidden** (`code: "MALPRACTICE_BLOCKED"`): Candidate holds an active `FLAGGED_TAB_SWITCH` session.

### `GET /api/v1/exams/active/session` *(Strictly Read-Only)*
- **200 OK**:
  - When `IN_PROGRESS` and `now <= expectedEndTime + syncGraceSeconds`: returns `{ sessionId, status: "IN_PROGRESS", startTime, expectedEndTime, serverTime, shuffleSeed, timeLeft, lastSyncedAt, tabSwitchCount, answers }`.
  - When `IN_PROGRESS` has already exceeded `expectedEndTime + syncGraceSeconds` (before sweeper runs) or no `IN_PROGRESS` session exists: returns `{ data: null }` (or non-active summary) WITHOUT performing any database writes inside the read-only transaction.

### `POST /api/v1/exams/active/sync?sessionId={uuid}`
- **Validation**: `ExamSyncRequest` MUST include `@NotNull Map<String, Object> statePayload`.
- **Behavior**:
  - Enforces ownership + optimistic locking (`@Version`), retries cleanly or maps conflict safely.
  - Preserves monotonic `tabSwitchCount = max(existingCount, reportedCount)`.
  - If existing session is terminal (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`, `FLAGGED_TAB_SWITCH`), returns idempotent `200 OK` ONLY if `!isFinal` replay? Better: returns `409 Conflict` (`code: "SESSION_ALREADY_FINALIZED"`) if non-final sync attempts to mutate an already terminal session (`isFinal` replays remain idempotent within terminal state rules except `FLAGGED_TAB_SWITCH` returns `403`).
  - **Deadline guard**: If non-final and `now > expectedEndTime + syncGraceSeconds`, closes expired `IN_PROGRESS` session safely (writable/swept) and returns **`410 Gone`** (`code: "EXAM_WINDOW_EXPIRED"`).
  - If `isFinal == true`: delegates to shared finalization logic enforcing the `offlineSubmitGraceSeconds` (900s) cutoff.

### `POST /api/v1/exams/active/submit?sessionId={uuid}&reason={reason}`
- **200 OK**: Finalizes within deadline (`SUBMITTED`), between `expectedEndTime + syncGrace` and `expectedEndTime + 900s` (`LATE_SUBMISSION_FLAGGED`), or on violation (`FLAGGED_TAB_SWITCH`). Repeats on already-finalized session are idempotent (`200 OK` with current status, firing zero duplicate `ExamSubmittedEvent`).
- **410 Gone** (`code: "OFFLINE_CUTOFF_EXCEEDED"`): Final submission arrives past `expectedEndTime + 900s`. Server ensures `IN_PROGRESS` session transitions to `TIME_EXPIRED` using last synced answers.
- **Response `data`**: `{ "status": "SUBMITTED" | "LATE_SUBMISSION_FLAGGED" | "FLAGGED_TAB_SWITCH", "serverTime": "...", "moderationStatus": "ELIGIBLE" | "PENDING_REVIEW" | "FLAGGED" }`.

## 2. Student Results & Moderation Gating (`/api/v1/students/me/**`, `/api/v1/student/results`, `ROLE_STUDENT`)

### `GET /api/v1/students/me/results` & `GET /api/v1/student/results`
- Excludes `VOIDED` results or labels non-eligible status cleanly (`moderationStatus`: `ELIGIBLE`, `PENDING_REVIEW`, `CLEARED`, `VOIDED`).
- When candidate has an active (`superseded = false`) `FLAGGED_TAB_SWITCH` session and no eligible result, `/api/v1/student/results` returns `{ "data": { "reviewStatus": "UNDER_REVIEW", "message": "Your examination attempt is under administrative review." } }` (clean non-500 status banner for `ResultPage`).
- For `PENDING_REVIEW` (`LATE_SUBMISSION_FLAGGED`), `ResultPage` / `Dashboard` display `"Submission Pending Review — Official Slip Withheld"`.

### `GET /api/v1/students/me/results/{resultId}/slip` & `/latest/slip`
- **403 Forbidden** (`code: "MALPRACTICE_REVIEW_REQUIRED"` or `"RESULT_PENDING_REVIEW"`): Blocked whenever candidate has an active `FLAGGED_TAB_SWITCH` session OR the target `ExamResult.moderationStatus` is `PENDING_REVIEW` or `VOIDED`.
- Only `ELIGIBLE` or `CLEARED` results generate a student PDF slip.

## 3. Admin & Supervisor Moderation / Analytics (`/api/v1/admin/**`)

### `GET /api/v1/admin/analytics/results` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
- Returns `PagedResponse`: `{ "data": [{ id, examSessionId, studentName, registrationNumber, totalScore, maxScore, topicBreakdown, gradedAt, moderationStatus, sessionStatus }], "meta": { page, size, totalElements, totalPages } }`. Never throws NPE on null `topicBreakdown`.

### `GET /api/v1/admin/analytics/flagged-sessions` (`ROLE_ADMIN` or `ROLE_SUPERVISOR`)
- Returns active non-superseded `FLAGGED_TAB_SWITCH`, `LATE_SUBMISSION_FLAGGED`, and `TIME_EXPIRED` sessions with student metadata (`studentId`, `studentName`, `registrationNumber`, `sessionId`, `status`, `tabSwitchCount`, `startTime`, `endTime`, linked `resultId` & `moderationStatus` if graded) so admins/supervisors can review and take action.

### `POST /api/v1/admin/exams/sessions/{sessionId}/clear-late` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
- Validates session is `LATE_SUBMISSION_FLAGGED`, promotes `ExamSession.status` to `SUBMITTED`, promotes `ExamResult.moderationStatus` to `CLEARED`, and updates Redis leaderboard (`LeaderboardService`). Returns `409 Conflict` if not `LATE_SUBMISSION_FLAGGED`.

### `POST /api/v1/admin/exams/sessions/{sessionId}/void` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
- Marks `ExamResult.moderationStatus = VOIDED` (if present), removes candidate entry from Redis leaderboard, keeping session record for audit.

### `POST /api/v1/admin/students/{id}/grant-retake` (`ROLE_ADMIN` only, audited via `AuditLogAspect`)
- Marks current non-superseded `ExamSession` rows for candidate `{id}` as `superseded = true` (voiding un-cleared/flagged prior results if configured or leaving `VOIDED` for audit, clearing active Redis session key), allowing the candidate to start one fresh exam sitting.
