# Feature Specification: Exam Session Integrity, Proctoring Moderation & Shared-Kiosk Offline Resilience

**Feature Branch**: `deployment` (Slice 003)  
**Created**: 2026-11-14  
**Status**: Approved Scope — Ready for Planning  
**Input**: User request: "Plan Slice 003: Exam, Snapshot, Proctoring & Offline/Kiosk Integrity" combining backend exam/snapshot/grading/outbox integrity, shared-kiosk browser isolation and offline PWA resilience, seed question remediation, and admin recent-results analytics fixes with resolved malpractice slip & late offline cutoff policies.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Frozen Exam Snapshot, Transaction Safety & Idempotent Grading (Priority: P1)

As a candidate writing an examination—and as an administrator reviewing official results—every started examination session must freeze its exact question set, passages, answer options, and scoring key at launch so live edits or deletions in the question bank cannot alter in-flight candidates or corrupt grades, and every submitted exam must produce at most one accurate grade without read-only query failures or duplicate score inflation.

**Why this priority**: Non-deterministic shuffling, mid-exam question bank edits shifting `maxScore`, `500` errors on dashboard session polling due to database writes inside read-only queries, and duplicate grading rows break fundamental fairness and crash active exam cohorts.

**Independent Test**: Start an exam for a candidate, edit/archive an active question mid-exam, trigger concurrent session status polls and repeated/simultaneous final submissions after expiry, and verify zero server `500` errors, unchanged candidate question count/answers, and exactly one graded result scored against the frozen launch snapshot.

**Acceptance Scenarios**:

1. **Given** an active exam session has started with a deterministic question set and shuffle seed, **When** an administrator modifies, approves, or archives questions in the bank while the exam is running, **Then** resuming or submitting that exam continues using the exact frozen snapshot captured when the session started.
2. **Given** an exam session whose 120-minute window plus grace period has elapsed, **When** the candidate's dashboard polls active session status, **Then** the server safely transitions the expired session in a writable transaction without crashing and reports no active `IN_PROGRESS` exam.
3. **Given** a candidate finishes an exam and triggers both a final state synchronization and a submission request (or retries submission), **When** both requests reach the server concurrently, **Then** only one terminal transition triggers grading, and at most one exam result record is created for that session.
4. **Given** two different candidates enrolled in identical subjects start exams, **When** payloads are generated using each session's stored shuffle seed, **Then** each candidate receives a distinct permutation consistent across reloads for their own session.

---

### User Story 2 - Authoritative Server Deadlines, Offline Grace Cutoff & Sitting Policy (Priority: P1)

As an examination authority, the server must remain the sole authority over exam start/end deadlines (`start_time` + 120 minutes), reject overdue routine syncs cleanly, honor an explicit offline-submission recovery window for queued final submissions after network drops, and enforce a strict single-sitting policy by default unless an administrator grants an explicit retake or workstation unlock.

**Why this priority**: Without server deadline checks on sync and a single-sitting guard after expiration, candidates can synchronize answers hours past expiry or immediately restart a brand-new 2-hour exam after timing out.

**Independent Test**: Advance a session past `expected_end_time` cleanly in tests and verify routine sync requests past the sync grace window are rejected (`410`), final submissions within the 15-minute offline grace window succeed as `LATE_SUBMISSION_FLAGGED`, final submissions past 15 minutes are rejected (`410`), and subsequent exam `/start` calls return `409 Conflict` unless an admin grants retake clearance.

**Acceptance Scenarios**:

1. **Given** an active exam session whose `expected_end_time` plus sync grace (30 seconds) has passed, **When** a routine non-final `/sync` arrives, **Then** the server rejects the sync (`410 Gone`) and transitions expired unsubmitted work instead of extending answers.
2. **Given** a candidate lost Wi-Fi near the end of the 2-hour exam and their device queued final submission within 15 minutes of `expected_end_time`, **When** connectivity returns within that 15-minute cutoff and Background Sync delivers the submission, **Then** the server records the attempt as `LATE_SUBMISSION_FLAGGED` using answers captured up to cutoff.
3. **Given** an offline final submission arrives more than 15 minutes after `expected_end_time`, **When** delivered to the server, **Then** the server rejects late modification (`410 Gone`) while a scheduled background sweeper finalizes overdue sessions automatically using last synced answers (`TIME_EXPIRED`).
4. **Given** a candidate already holds a completed or expired session (`SUBMITTED`, `TIME_EXPIRED`, or `LATE_SUBMISSION_FLAGGED`), **When** the candidate attempts to start another exam without explicit administrative retake clearance, **Then** the server rejects `/start` with `409 Conflict`.

---

### User Story 3 - Proctoring Moderation, Malpractice Enforcement & Result Slip Gating (Priority: P1)

As a school administrator and supervisor, server-tracked anti-cheat strike counts must never be erased by routine client syncs, malpractice-terminated candidates (`FLAGGED_TAB_SWITCH`) must be immediately locked out of exam payloads and active sessions, malpractice sessions must never be graded or earn result slips or leaderboard rank, and late-flagged submissions (`LATE_SUBMISSION_FLAGGED`) must remain withheld from official student slips and leaderboard until reviewed and cleared by an administrator.

**Why this priority**: Issuing valid downloadable result slips and leaderboard positions to malpractice or unreviewed late submissions defeats CBT exam integrity.

**Independent Test**: Trigger 3 tab-switch violations to flag a session, verify immediate session revocation, blocked payload fetch (`403`), zero graded `ExamResult` created, `"Under Administrative Review"` banner on candidate portal, and `403` on slip downloads; separately test a `LATE_SUBMISSION_FLAGGED` exam verifying graded review visibility for admins while blocked from student slips/leaderboard until admin clearance.

**Acceptance Scenarios**:

1. **Given** a session has accumulated 1 or 2 proctoring strikes on the server, **When** the client sends a routine `/sync` request omitting or resetting strike counters, **Then** the server monotonically preserves `max(serverCount, clientCount)` and never reduces recorded violations.
2. **Given** a session transitions to `FLAGGED_TAB_SWITCH`, **When** that candidate refreshes, calls `/active/payload`, `/start`, or any protected endpoint with their existing token, **Then** the server revokes their active session immediately, refuses further exam access (`403 Forbidden`), does not grade or award leaderboard points, and displays an `"Under Administrative Review"` status in the candidate portal.
3. **Given** a session is marked `LATE_SUBMISSION_FLAGGED`, **When** automated grading completes, **Then** the score is stored strictly as withheld/pending moderation (visible to admins/supervisors for review, excluded from Redis leaderboard ranking and blocked from official student slip download) until an administrator explicitly clears the submission to normal `SUBMITTED` standing or voids it.

---

### User Story 4 - Shared-Kiosk Browser Isolation & Offline-First Exam Resilience (Priority: P1)

As candidates sharing school lab workstations across back-to-back exam shifts—including environments with spotty Wi-Fi—signing in as a new student must never expose a previous student's questions, answers, or terminated state, offline browser reloads during an active exam must continue seamlessly, answer syncs/submissions must never report false success on HTTP errors or expired tokens, and reaching `00:00:00` must reliably submit answers.

**Why this priority**: In school CBT labs, successive candidates reuse the same browser on shared PCs; cross-user local data leaks, unrecoverable blank screens on offline reload, and false `"Submitted"` messages cause severe exam loss.

**Independent Test**: Complete or terminate an exam for Student A in one browser profile, log out (or simulate eviction), log in as Student B in the same browser without clearing site data manually, and verify Student B sees only fresh own-subject questions and zero Student A state; separately load an exam, drop network connectivity, reload `/exam`, answer questions, let the timer expire (or click Submit), verify offline queue UX, and restore network to confirm verified submission.

**Acceptance Scenarios**:

1. **Given** Student A used the exam interface on a workstation and then logged out, was evicted, or was terminated for malpractice, **When** Student B logs in on the same browser without closing the window, **Then** all prior in-memory exam state and local IndexedDB exam records belonging to other users/sessions are purged, and Student B sees strictly their own scoped session.
2. **Given** an authenticated student is mid-exam when their access token expires or the network disconnects, **When** `/sync` or `/submit` executes, **Then** expired `401` tokens trigger seamless token refresh and retry without losing answers, network failures transition cleanly to `"Offline — Queued Locally"`, and clicking Submit never navigates to a false success page if offline or server-rejected.
3. **Given** an active student refreshes the exam browser tab while offline, **When** the cached application shell loads, **Then** scoped local questions, passages, fonts (`Inter` & `KaTeX`), and timer/answers render cleanly with zero external CDN dependencies, and final submission retries cleanly via PWA Background Sync when online.
4. **Given** a student's exam tab goes to sleep or minimizes while time elapses, **When** the tab regains focus or wall-clock `expected_end_time` reaches zero, **Then** the timer snaps to monotonic server-synced remaining time and automatically triggers final submission at `00:00:00`.

---

### User Story 5 - Outbox Self-Healing, Cache Fault Tolerance, Seed Data Cleanup & Admin Analytics (Priority: P2)

As a platform operator and school administrator, asynchronous grading events interrupted by transient Redis or process failures must automatically recover via distributed-locked scheduled sweeps, active exam caching must survive Redis outages without crashing students, all active seed questions must render offline without leaking answer keys, and the Admin Dashboard must accurately display recent completed exams and consistent pass-rate statistics (`>= 50.0%`).

**Why this priority**: Unrecovered outbox events orphan scores, unguarded Redis calls turn brief cache hiccups into exam outages, legacy nested comprehension seed data leaks answer keys, and a shape mismatch hides recent results from admins.

**Independent Test**: Simulate a transient Redis failure during question fetch and outbox grading, verify active exam continues from database fallback and outbox scheduler completes grading once Redis recovers; verify seeded English comprehension and Accounting questions render with clean offline SVG/self-hosted assets and zero exposed `correctOption`; verify Admin Dashboard shows paginated recent results and `50.0%` exact scores count as passing.

**Acceptance Scenarios**:

1. **Given** Redis becomes temporarily unavailable during an exam, **When** a student starts/resumes an exam or submits for grading, **Then** exam payload generation falls back transparently to PostgreSQL, grading completes in the database without crashing if leaderboard update cannot reach Redis, and ShedLock-protected background sweeps republish any incomplete outbox publications.
2. **Given** Question Bank admins approve, edit, or delete active questions, **When** the database transaction commits, **Then** question cache eviction runs after commit (`AFTER_COMMIT`) and ignores transient Redis errors without rolling back valid DB saves.
3. **Given** seeded active questions from early migrations (`V2` comprehension and `V4` accounting/diagrams), **When** normalized by migration, **Then** comprehension passages use standard linked context rows without nested `childQuestions[].correctOption` leaks, corrupted accounting options and external `placehold.co` URLs are replaced with valid offline-safe assets, and exam payload generation recursively strips any residual answer keys.
4. **Given** administrators or supervisors open the Admin Dashboard with completed graded results (including results with empty/null topic breakdowns and `>= 50.0%` scores), **When** analytics load, **Then** `"Recent Completed Exams"` renders paginated records correctly (`data` array + `meta`), zero `500` NPEs occur, and `>= 50.0%` consistently counts as passing across dashboard, summaries, and result slips.

---

### Edge Cases

- **Simultaneous Final Sync + Manual Submit + Tab-Switch Auto-Submit**: Concurrent requests hitting `/sync` (`isFinal: true`), `/submit`, and `/active/session` must serialize cleanly via optimistic locking/status guards, never producing duplicate `ExamResult` rows or optimistic locking `500` errors on duplicate finalization.
- **Exam Clock Skew / Client Tampering**: Client `timeLeft` may be older or tampered downward/upward; server always clamps remaining time to `max(0, expected_end_time - server_now)` and syncs authoritative `serverTime` & `expectedEndTime` back to the client.
- **Admin Retake Clearance vs Workstation Unlock**: `unlock-session` continues to let an interrupted `IN_PROGRESS` student move to a spare lab PC; a separate explicit Admin action (`grant-retake` / `clear-late`) handles retakes after completion/expiry or clearing `LATE_SUBMISSION_FLAGGED` reviews.
- **Supervisor Read-Only Enforcement**: `ROLE_SUPERVISOR` can view flagged/late sessions and recent completed exams in analytics, but CANNOT trigger retake resets, late-submission clearances, or official slip generation (`403`).
- **Offline Browser Reload Immediately After Login**: Even if a student never previously opened `/exam` before losing network, offline start attempts must surface clear actionable errors rather than dead empty screens or another student's cached data.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST capture and persist a frozen exam snapshot (`frozenQuestions` + `frozenContexts` or immutable snapshot JSONB, `shuffle_seed`, `expected_end_time`) at `ExamSession` creation so mid-exam Question Bank edits, approvals, or archives never alter in-flight sessions or grading outcomes.
- **FR-002**: System MUST enforce `UNIQUE(exam_session_id)` on `exam_results` and idempotent grading so concurrent or replayed `ExamSubmittedEvent` publications never insert duplicate results or double-increment scores.
- **FR-003**: System MUST remove database write side-effects from read-only transactions (`getActiveSession` and `getLiveSessions`), moving expired-session finalization into explicit writable transactions and a ShedLock-protected scheduled background sweeper.
- **FR-004**: System MUST enforce server deadlines (`expected_end_time = start_time + 120 minutes`, configurable):
  - Reject routine non-final `/sync` past `expected_end_time + sync_grace (30s)` with `410 Gone` (RFC 7807 `ProblemDetail`).
  - Accept final `/submit` within `offline_grace_seconds (900s / 15m)` past `expected_end_time`, tagging arrivals beyond `30s` grace as `LATE_SUBMISSION_FLAGGED`; reject `/submit` beyond the `900s` offline cutoff with `410 Gone`.
- **FR-005**: System MUST enforce a single-sitting policy by default (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED` -> `409 Conflict` on `/start`; `FLAGGED_TAB_SWITCH` -> `403 Forbidden` on `/start` and `/active/payload`) unless an Administrator grants explicit retake clearance.
- **FR-006**: System MUST preserve proctoring violation counts monotonically on `/sync` (`max(existingCount, reportedCount)`), terminate sessions cleanly at threshold (`>= 3`), immediately revoke active Redis login tokens when flagged via cross-module domain event, block `FLAGGED_TAB_SWITCH` from grading/leaderboard/slips, and store `LATE_SUBMISSION_FLAGGED` results in pending-moderation status excluded from student result slips and leaderboard until an Administrator clears or voids them.
- **FR-007**: System MUST handle `ExamResultRepository` pass-rate threshold consistently at `>= 50.0%` (`(totalScore * 2 >= maxScore)` or `>= 0.5`), avoid `NPE` on null `finalState`, null `topicBreakdown`, or non-string answer values, and compute `ExamInternalAPI.countCompletedExamsBySessionId` accurately.
- **FR-008**: System MUST execute ShedLock-protected `@Scheduled` sweeps to recover incomplete Spring Modulith `event_publication` rows and sweep overdue `IN_PROGRESS` sessions, and gracefully degrade on transient Redis errors across exam caching, cache eviction (post-commit `AFTER_COMMIT`), and leaderboard updates without interrupting active exams or rolling back graded `ExamResult` writes.
- **FR-009**: Client state MUST scope IndexedDB exam records and Redux state strictly to the active authenticated student identifier (`studentRegNo`/`userId` + `sessionId`), clear prior student state upon logout, session eviction, malpractice termination, or identity change, persist frozen exam questions/contexts locally for offline `/exam` refreshes, replace raw `fetch()` sync/submit calls with token-refresh-aware verified HTTP calls, compute monotonic countdown from `expectedEndTime` with automatic submission at `00:00:00`, self-host/precache Inter & KaTeX fonts (`.woff2`, `.woff`, `.ttf`) + PWA icons + Background Sync for `/api/v1/exams/active/submit`, and fix image asset type normalization (`'image'` case-insensitive) and Admin Dashboard `"Recent Completed Exams"` data mapping.
- **FR-010**: System MUST ship an idempotent Flyway migration (`V22+`) converting broken `V2` legacy nested comprehension questions into standard linked `QuestionContext` + flat `MCQ` questions, fixing corrupted `V4` Accounting options & replacing external `placehold.co` URLs with offline-safe local diagram assets, and stripping nested answer keys recursively as defense-in-depth in payload generation.

### Key Entities

- **ExamSession**: Represents a candidate's single exam attempt; extended with authoritative `expected_end_time`, frozen question/context snapshot (`snapshot_payload` JSONB), `moderation_status` / retake clearance linkage, and monotonic anti-cheat `tab_switch_count`.
- **ExamResult**: Represents the unique (`UNIQUE(exam_session_id)`) grading outcome of an eligible exam session, including `moderation_status` (`ELIGIBLE`, `PENDING_REVIEW`, `CLEARED`, `VOIDED`) governing leaderboard inclusion and student result-slip eligibility.
- **LocalExamState (IndexedDB)**: Scoped offline exam record keyed/tagged with owner registration identity (`ownerIdentifier`), frozen `questions`/`contexts`, `expectedEndTimeMs`, monotonic `timeLeft`, `answers`, `flagged`, `tabSwitchCount`, and sync/submission status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero database read-only transaction errors (`500`) and zero duplicate `ExamResult` rows across concurrent session polling, double-submit retries, and outbox replay tests.
- **SC-002**: 100% of active questions edited, approved, or archived mid-exam leave in-flight candidates' question lists and final graded scores unchanged against their session start snapshot.
- **SC-003**: 100% of `FLAGGED_TAB_SWITCH` sessions are immediately blocked from active payloads/sessions, excluded from `exam_results` and leaderboard ranking, and return `403`/review status on slip requests; 100% of un-cleared `LATE_SUBMISSION_FLAGGED` sessions remain withheld from student slips and leaderboard until cleared by an Administrator.
- **SC-004**: Overdue `/sync` calls past `expected_end_time + 30s` return `410 Gone` 100% of the time; offline `/submit` calls succeed cleanly within the 15-minute offline grace window (marked `LATE_SUBMISSION_FLAGGED` after `30s`) and return `410 Gone` past 15 minutes; repeat `/start` calls after terminal completion return `409 Conflict` unless admin-cleared.
- **SC-005**: In automated Playwright E2E checks across Chromium and WebKit on shared-browser session transitions (Student A logout/malpractice -> Student B login) and offline reload -> reconnect -> submit flows, 0 items of Student A data leak to Student B, 0 external network errors occur for fonts/assets offline, and Admin Dashboard displays recent completed exams cleanly.

## Assumptions

- Single-sitting policy is enabled per candidate by default for a school mock exam sitting; administrators can explicitly clear a late submission or issue a retake reset when legitimate technical disruptions occur.
- Exam duration remains configurable cleanly (defaulting to 120 minutes) via configuration alongside sync grace (default 30s) and offline submit cutoff (default 15 minutes = 900s).
- Redis outages during live exam hours are treated as transient cache/rate-limit/leaderboard disruptions that MUST fail open for active exam delivery and outbox grading rather than halting an active exam room.
