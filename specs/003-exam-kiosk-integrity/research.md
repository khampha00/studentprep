# Phase 0 Research: Exam Session Integrity, Proctoring Moderation & Shared-Kiosk Offline Resilience

**Feature**: `003-exam-kiosk-integrity` | **Date**: 2026-11-14

## Decision 1: Immutable Session Snapshot Storage on `exam_sessions`
- **Decision**: Persist `expected_end_time` (already in `V1__Init_Schema.sql`, unmapped in `ExamSession.java`) and a `snapshot_payload JSONB` column (`V22`) directly on `exam_sessions` when a candidate first starts an exam (`POST /api/v1/exams/start`). `snapshot_payload` contains the exact ordered list of frozen question records (including `id`, `subject`, `topic`, `content` with `options` & `correctOption`, and `contextId`) plus `contexts` (`contextId -> passage`) captured at session start.
- **Rationale**:
  - Single-query self-contained replay & grading: resuming via `/start`, fetching `/active/payload`, and grading in `ExamGraderListener` read from `ExamSession` without querying mutable live `questions` or `question_contexts` rows.
  - Zero live-bank drift: mid-exam edits, draft-reversions, deletions, or new approvals in `QuestionService` leave in-flight candidates' `questions`, option sets, `maxScore`, and `correctOption` untouched.
  - `ExamPayloadGenerator` MUST strip `correctOption` recursively (top-level AND any nested `childQuestions[]` maps as defense-in-depth) before returning `ExamPayloadResponse` to `ROLE_STUDENT`, and MUST shuffle groups deterministically using `session.getShuffleSeed()` rather than question UUID hash sums.
- **Alternatives Considered**: Separate normalized `exam_session_questions` join table with versioned `question_revisions`. Rejected for single-school pragmatism: `snapshot_payload JSONB` aligns with PostgreSQL 16 + `JsonType` already used across `questions`, `exam_sessions`, and `exam_results`, adds 1 column in `V22`, and avoids N+1 joins during 2-hour exam resumes and grading.

## Decision 2: Server Deadlines, Sync Grace & 15-Minute Offline Submission Cutoff
- **Decision**:
  - Configurable via `ExamTimingProperties` / `@Value`:
    - `exam.timing.duration-minutes: 120` (default 2 hours)
    - `exam.timing.sync-grace-seconds: 30` (clock/jitter cushion for active heartbeat & regular `/sync`)
    - `exam.timing.offline-submit-grace-seconds: 900` (15 minutes offline submission window for queued `/submit` / Workbox Background Sync)
  - On `/start` (new session): `start_time = now`, `expected_end_time = now + durationMinutes`.
  - On non-final `/sync`:
    - Allowed iff session is `IN_PROGRESS` and `now <= expected_end_time + syncGraceSeconds`.
    - Past `expected_end_time + syncGraceSeconds`: finalize session cleanly as `TIME_EXPIRED` (if not already finalized) and return RFC 7807 `410 Gone` (`code: "EXAM_WINDOW_EXPIRED"`), never overwriting saved answers with late payload.
  - On `/submit` (or `/sync` with `isFinal: true`):
    - If `now <= expected_end_time + syncGraceSeconds`: finalize as `SUBMITTED` (or `FLAGGED_TAB_SWITCH` if reason or `tabSwitchCount >= 3`).
    - If `expected_end_time + syncGraceSeconds < now <= expected_end_time + offlineSubmitGraceSeconds`: finalize as `LATE_SUBMISSION_FLAGGED` (or `FLAGGED_TAB_SWITCH` if triggered by tab-switch), persisting client answers submitted before offline drop.
    - If `now > expected_end_time + offlineSubmitGraceSeconds`: finalize any lingering `IN_PROGRESS` session as `TIME_EXPIRED` using its last saved `state_payload` and reject the overdue submission with RFC 7807 `410 Gone` (`code: "OFFLINE_CUTOFF_EXCEEDED"`).
  - Read-only queries (`GET /api/v1/exams/active/session`, `ExamInternalAPIImpl.getLiveSessions`) MUST stay strictly read-only (`@Transactional(readOnly = true)`): when an `IN_PROGRESS` session has passed `expected_end_time + offlineSubmitGraceSeconds` (or `expected_end_time + syncGraceSeconds` if treated as client-expired), `getActiveSession` computes `remainingTime = 0` / returns `{ "status": "TIME_EXPIRED" }` or delegates expiration to a `REQUIRES_NEW` / background writable sweeper rather than mutating inside a read-only transaction. Specifically, placing `finalizeExpiredSessions()` inside a dedicated `ExamExpirySweeper` (`@Scheduled` + `@SchedulerLock`) + non-readonly write boundary guarantees zero Postgres `read-only transaction` 500s.
- **Alternatives Considered**: Converting `getActiveSession` to read-write everywhere on every 5s/10s poll. Rejected because unnecessary write locks on every student heartbeat conflict with optimistic locking under high concurrency; a clean `TIME_EXPIRED` status projection on read plus a dedicated ShedLock sweeper every 30s + `REQUIRES_NEW` boundary when strictly triggered avoids all read-only errors and lock contention.

## Decision 3: Single-Sitting Policy & Retake / Late-Moderation Workflows
- **Decision**:
  - `POST /api/v1/exams/start`:
    - If student has any session with `FLAGGED_TAB_SWITCH` (and no active retake reset clearing it? Wait: malpractice sessions remain blocked unless an Admin explicitly voided/reset via `grant-retake`): return `403 Forbidden` (`code: "MALPRACTICE_BLOCKED"`).
    - If student has `IN_PROGRESS`: resume existing session (if `now > expected_end_time + syncGraceSeconds`, transition via writable transaction to `TIME_EXPIRED` and return `409 Conflict` with `code: "EXAM_ALREADY_COMPLETED"` — closing the infinite restart loophole!).
    - If student has any completed/terminal session (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`) and no unused retake clearance: return `409 Conflict` (`code: "EXAM_ALREADY_COMPLETED"`).
    - Add `retake_granted` boolean (or `retake_override_until` / `superseded` / `ARCHIVED_FOR_RETAKE`?): Keeping a boolean `retake_cleared` on `exam_sessions` (or marking superseded terminal sessions `superseded = true`) preserves historical audit records while allowing `POST /api/v1/admin/students/{id}/grant-retake` (`ROLE_ADMIN`, audited) to mark existing terminal/flagged sessions superseded and allow one fresh `/start` sitting! Even simpler: add `superseded BOOLEAN NOT NULL DEFAULT FALSE` on `exam_sessions`. Queries checking blocking/completed sessions filter `superseded = FALSE`.
- **Rationale**: Simple, auditable (old sessions and old `exam_results` remain intact for audit), closes post-expiry restart completely, and handles legitimate lab failures cleanly for `ROLE_ADMIN`.

## Decision 4: Malpractice & Late-Submission Moderation (`FLAGGED_TAB_SWITCH` vs `LATE_SUBMISSION_FLAGGED`)
- **Decision**:
  - `FLAGGED_TAB_SWITCH`:
    - Triggered cleanly when `tabSwitchCount >= 3` or explicit `FLAGGED_TAB_SWITCH` reason on `/sync` or `/submit`.
    - `ExamSessionManager` updates status to `FLAGGED_TAB_SWITCH`, persists `state_payload` preserving `tabSwitchCount = max(existing, incoming)`, and publishes `ExamSessionFlaggedEvent(sessionId, userId, reason)` (Spring Modulith).
    - `security` module listens via `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` (or `@ApplicationModuleListener` + direct immediate revocation inside `SecuritySessionEvictionListener`) to delete `SessionRedisKeys.sessionKey(canonicalIdentifier)` and revoke active refresh/access session immediately. Also `GET /api/v1/exams/active/payload` and `POST /api/v1/exams/active/sync` enforce `sessionRepository.existsByUserIdAndStatusAndSupersededFalse(userId, FLAGGED_TAB_SWITCH)` returning `403`.
    - **No `ExamResult` is created** for `FLAGGED_TAB_SWITCH`, `LeaderboardService` is never updated, and all slip endpoints (`GET /api/v1/students/me/results/*/slip`, `latest/slip`, admin result slip for a flagged/voided result) verify session status and return `403 Forbidden` (`code: "MALPRACTICE_REVIEW_REQUIRED"`).
    - Student portal (`GET /api/v1/students/me/results`, `/api/v1/student/results`, Student `Dashboard.tsx`, `ResultPage.tsx`) exposes `sessionReviewStatus: "UNDER_REVIEW"` when the latest non-superseded session is `FLAGGED_TAB_SWITCH` so candidates see `"Under Administrative Review"` instead of an empty spinner.
  - `LATE_SUBMISSION_FLAGGED`:
    - Graded normally from frozen `snapshot_payload`, persisted into `exam_results` with `moderation_status = 'PENDING_REVIEW'` and `session_status = 'LATE_SUBMISSION_FLAGGED'`.
    - Excluded from `LeaderboardService` Redis ZSet (`studentprep:global_leaderboard`) AND blocked (`403`, `code: "RESULT_PENDING_REVIEW"`) from student slip downloads (`/api/v1/students/me/results/*/slip` and `latest/slip`) until an Administrator clears it.
    - Visible to `ROLE_ADMIN` & `ROLE_SUPERVISOR` in Admin Analytics (`ResultsPage`, `Dashboard`, flagged sessions queue) with moderation badge `PENDING_REVIEW`.
    - `ROLE_ADMIN` can call `POST /api/v1/admin/exams/sessions/{sessionId}/clear-late` (promotes `ExamSession` -> `SUBMITTED`, `ExamResult.moderationStatus` -> `CLEARED`, updates `LeaderboardService`) or `POST /api/v1/admin/exams/sessions/{sessionId}/void` (sets `ExamResult.moderationStatus = 'VOIDED'` and removes from leaderboard).

## Decision 5: Outbox Recovery (`ShedLock`), Redis Fail-Open & Cache Timing
- **Decision**:
  - Enable `spring.modulith.events.republish-outstanding-events-on-restart=true` in `application.yml` AND implement `OutboxRecoveryScheduler` using `@Scheduled(fixedDelayString = "${exam.outbox.recovery-interval-ms:60000}")` + `@SchedulerLock(name = "outbox_recovery_sweep", lockAtMostFor = "5m", lockAtLeastFor = "10s")` injecting Spring Modulith's `IncompleteEventPublications` (`resubmitIncompletePublicationsOlderThan(Duration.ofSeconds(30))`).
  - Also run `ExamExpiryScheduler` every 30s with `@SchedulerLock(name = "exam_expiry_sweep", lockAtMostFor = "2m", lockAtLeastFor = "5s")` to close any `IN_PROGRESS` sessions where `now > expected_end_time + offlineSubmitGraceSeconds`.
  - Wrap all `RedisTemplate` / `StringRedisTemplate` operations in `ExamCacheService` (with synchronized single-flight/mutex stampede lock on cache fill), `QuestionService.evictQuestionsCache()` (executed via `TransactionSynchronizationManager.registerSynchronization` `afterCommit()`), `SubjectController`, and `LeaderboardService` inside `try { ... } catch (DataAccessException ex)` with structured SLF4J warn logging, ensuring DB transactions & exam grading NEVER roll back due to Redis downtime.
  - Fix `LeaderboardService` to use `ZSetOperations.add(key, studentId, bestOrLatestScore)` instead of `incrementScore`, clamp `topN` (`1..100`), and store `maxScore`-aware scores cleanly.
  - Fix `ExamGraderListener`: idempotent check `examResultRepository.findByExamSessionId(event.sessionId())` + `UNIQUE(exam_session_id)` DB constraint catching `DataIntegrityViolationException` cleanly; remove `System.out.println` / `System.err.println`; defensive handling of null `finalState` / non-string `answers`.
  - Fix `ExamResultRepository`: replace `> 0.5` pass threshold in `countPassedExams()` and `countPassedExamsByExamId()` with `(CAST(e.totalScore AS double) / e.maxScore) >= 0.5` and `e.moderationStatus IN ('ELIGIBLE', 'CLEARED')` so dashboard pass rate / average / completed count exclude `VOIDED` results and align with PDF `>= 50.0%`.

## Decision 6: Shared-Kiosk Browser Isolation & Offline-First PWA Hardening
- **Decision**:
  - Upgrade Dexie `StudentPrepDB` to `version(2)`: store `ownerIdentifier` (canonical registration number from JWT `sub`), `questions`, `contexts`, `student`, `expectedEndTimeMs`, `syncPendingFinal`, `answers`, `flagged`, `tabSwitchCount`, `isExamTerminated`, `lastUpdated`, `isSynced`. Enforce cleanup (`clearExamClientState()` -> clears `db.examStates` + dispatches Redux `resetExamState()`) in:
    1. Student/Admin `logout()` handlers and `App.tsx` Axios `SESSION_EVICTED` / `auth_expired` interceptor redirects,
    2. `ExamDashboard` malpractice auto-logout handler,
    3. `Login.tsx` before storing a newly authenticated student token whenever the incoming `sub` differs from stored local owner (belt-and-suspenders protection for abrupt tab closes without clicking Logout!).
  - Replace raw `fetch()` calls in `examSlice.ts` `syncExamData` with a dedicated Axios-backed helper (`axios` already runs token attachment, automatic `401` -> `/api/v1/auth/refresh` cookie rotation -> retry, and `SESSION_EVICTED` redirect!), preserving `navigator.sendBeacon`/Service Worker Background Sync fallback ONLY on unload, and strictly checking response status:
    - When `isFinal: true`, `SubmitButton` / auto-submit checks returned `{ delivered, offlineQueued, errorCode }`: navigate to `/result` ONLY when confirmed `200 OK` by server; if queued offline (network error), persist `syncPendingFinal = true` in IndexedDB + show `"Offline: Submission queued locally — keep browser open or reconnect within 15 minutes"` banner; if server rejects (`403`/`410`/`409`), surface clear error toast/modal instead of false success!
  - Offline `/exam` refresh resilience: persist fetched `questions`, `contexts`, `student`, and `expectedEndTimeMs` inside scoped Dexie record. On `/exam` mount offline (`!navigator.onLine` or network failure), rehydrate directly from scoped Dexie record matching current JWT `sub` without failing at `fetchExamPayload()`.
  - Monotonic timer: store authoritative `expectedEndTimeMs` derived from server `serverTime` + `timeLeft` (`Date.now() + timeLeft * 1000` adjusted monotonically by server responses) so tab sleep/background throttling recomputes exact remaining seconds (`Math.max(0, Math.floor((expectedEndTimeMs - Date.now()) / 1000))`) on every tick & `visibilitychange`, and triggers one-shot auto-submit at `<= 0`.
  - PWA / Fonts / Offline Assets:
    - Remove Google Fonts `<link>` from `frontend/index.html`; import `@fontsource-variable/inter` in `frontend/src/index.css`.
    - Update `frontend/vite.config.ts`: include `woff2`, `woff`, `ttf` in `workbox.globPatterns`, add runtime `NetworkOnly` + `BackgroundSyncPlugin` (`exam-submit-queue`, `maxRetentionTime: 15`) for `/api/v1/exams/active/submit` in addition to `/api/v1/exams/active/sync`, and bundle SVG/PNG app icons under `frontend/public/icons/`.
  - Seed & Image Normalization:
    - `V22` SQL migration decomposes `22222222-2222-2222-2222-222222222223` (`V2` legacy nested `COMPREHENSION` JSON) into a proper `question_contexts` row + two standard `MCQ` questions in `questions` (`q-child-1`, `q-child-2`), restores valid Accounting working capital options (`A: "$20,000"`, `B: "$30,000"`, `C: "$60,000"`, `D: "$90,000"` — since Working Capital = Current Assets `$50,000` - Current Liabilities `$30,000` = `$20,000`, matching `correctOption: "A"`!), and replaces `https://placehold.co/...` diagram URLs in `V4` (`33333333-...-3331`, `-3332`) with self-hosted local SVG/PNG static assets under `frontend/public/assets/questions/` (bundled cleanly into PWA precache and offline-safe!). Note: also fix `QuestionRenderer` / `ExamDashboard.tsx` image rendering (`asset?.type?.toLowerCase() === 'image'`, or string asset URL support!).
  - Admin Dashboard Fix:
    - `AnalyticsController.getResults` uses nullable-safe HashMap/DTO (eliminating `Map.of` NPE on null `topicBreakdown`), filters/annotates `moderationStatus` & `sessionStatus`, and `frontend/src/pages/admin/Dashboard.tsx` reads `recRes?.data?.data` (matching `PagedResponse` `{ data: [...], meta: {...} }` instead of `.data.content`!).
