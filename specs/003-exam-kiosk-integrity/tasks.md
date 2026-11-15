# Tasks: Exam Session Integrity, Proctoring Moderation & Shared-Kiosk Offline Resilience

**Input**: Design documents from `specs/003-exam-kiosk-integrity/`  
**Prerequisites**: [plan.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/plan.md), [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/spec.md), [research.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/research.md), [data-model.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/data-model.md), [api-exam-kiosk-integrity.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/contracts/api-exam-kiosk-integrity.md)

**Tests**: Unit, `@SpringBootTest` Postgres/Redis integration, `oxlint`/`vite build`, and unmocked Playwright Chromium + WebKit tests are required per Constitution Principle V.

---

## Phase 1: Setup & Offline Static Assets

**Purpose**: Prepare configuration properties, offline public diagram/PWA icon assets, and font imports before schema and service work.

- [x] T001 Add `exam.timing` (`duration-minutes: 120`, `sync-grace-seconds: 30`, `offline-submit-grace-seconds: 900`), `exam.outbox.recovery-interval-ms: 60000`, `exam.expiry.sweep-interval-ms: 30000`, and `spring.modulith.events.republish-outstanding-events-on-restart: true` in `backend/src/main/resources/application.yml`
- [x] T002 [P] Create self-hosted offline SVG question diagrams (`animal-cell-diagram.svg`, `linear-graph.svg`) in `frontend/public/assets/questions/` and bundled PWA app icons (`icon-192.png` / `icon.svg`) in `frontend/public/icons/`
- [x] T003 [P] Create `ExamTimingProperties` record/class (`durationMinutes`, `syncGraceSeconds`, `offlineSubmitGraceSeconds`) and `ExamDeadlineExpiredException` in `backend/src/main/java/com/studentprep/exam/` and map `ExamDeadlineExpiredException` -> `410 Gone` RFC 7807 `ProblemDetail` in `backend/src/main/java/com/studentprep/common/GlobalExceptionHandler.java`

---

## Phase 2: Foundational (Schema, Entities & Cross-Module Events)

**Purpose**: Establish Flyway `V22`, domain entities/enums, repository queries, and Spring Modulith domain events that block all user stories.

**CRITICAL**: No user story work begins until Phase 2 completes.

- [x] T004 Write idempotent migration `backend/src/main/resources/db/migration/V22__Exam_Snapshot_Integrity_Moderation_And_Seed_Fix.sql`: add `snapshot_payload JSONB` and `superseded BOOLEAN NOT NULL DEFAULT FALSE` on `exam_sessions`, backfill `expected_end_time = start_time + INTERVAL '120 minutes'` where null, rebuild partial unique index `idx_unique_active_session ON exam_sessions(user_id) WHERE status = 'IN_PROGRESS' AND superseded = FALSE`, deduplicate `exam_results` then add `CONSTRAINT uq_exam_results_session_id UNIQUE (exam_session_id)`, add `moderation_status VARCHAR(30) NOT NULL DEFAULT 'ELIGIBLE'` (`CHECK (moderation_status IN ('ELIGIBLE', 'PENDING_REVIEW', 'CLEARED', 'VOIDED'))`) + `moderated_by`, `moderated_at`, `moderation_note` columns, convert `V2` legacy comprehension `22222222-2222-2222-2222-222222222223` into linked `question_contexts` (`22222222-2222-2222-2222-222222222220`) + two flat `MCQ` rows (`q-child-1`, `q-child-2`), repair `V4` Accounting options (`"$20,000"`, `"$30,000"`, `"$60,000"`, `"$90,000"`), and rewrite `V4` `placehold.co` URLs to `/assets/questions/*.svg` with lowercase `"image"` asset type
- [x] T005 Update `ExamSession` (`expectedEndTime`, `snapshotPayload`, `superseded`) in `backend/src/main/java/com/studentprep/exam/ExamSession.java` and add non-superseded query methods (`findByUserIdAndStatusAndSupersededFalse`, `existsByUserIdAndStatusAndSupersededFalse`, `findAllByUserIdAndSupersededFalse`, `countByIdAndStatusIn`, `findExpiredInProgressSessions`) in `backend/src/main/java/com/studentprep/exam/ExamSessionRepository.java`
- [x] T006 [P] Create `ExamResultModerationStatus` enum (`ELIGIBLE`, `PENDING_REVIEW`, `CLEARED`, `VOIDED`) in `backend/src/main/java/com/studentprep/analytics/ExamResultModerationStatus.java` and update `ExamResult` (`@Column(name = "exam_session_id", nullable = false, unique = true)`, `moderationStatus`, `moderatedBy`, `moderatedAt`, `moderationNote`) in `backend/src/main/java/com/studentprep/analytics/ExamResult.java`
- [x] T007 [P] Update `ExamSubmittedEvent` (include `ExamSessionStatus terminalStatus`, `Map<String, Object> snapshotPayload`) in `backend/src/main/java/com/studentprep/exam/ExamSubmittedEvent.java`, create `ExamSessionFlaggedEvent(UUID sessionId, UUID userId, String reason)` in `backend/src/main/java/com/studentprep/exam/ExamSessionFlaggedEvent.java`, and create `ExamModerationUpdatedEvent(UUID sessionId, UUID studentId, String action, String actor, Instant occurredAt)` in `backend/src/main/java/com/studentprep/exam/ExamModerationUpdatedEvent.java`
- [x] T008 Update `ExamInternalAPI` and refactor `ExamInternalAPIImpl` in `backend/src/main/java/com/studentprep/exam/` to remove `@Autowired @Lazy` field injection and inline FQNs (constructor injection only), exclude `superseded = true` from `hasFlaggedSession` / `hasActiveSession`, remove write calls from `getLiveSessions()`, expose `getFlaggedOrLateSessions()` & `clearLateSession()` / `voidSession()` / `grantRetake()`, and implement `countCompletedExamsBySessionId` accurately

**Checkpoint**: `V22` schema & entity validation (`mvn -f backend/pom.xml test-compile`) and `ModulithArchitectureTest` ready as foundation.

---

## Phase 3: User Story 1 - Frozen Exam Snapshot, Transaction Safety & Idempotent Grading (Priority: P1) ðŸŽ¯ MVP

**Goal**: Freeze exam payload & answer keys at session start, eliminate read-only DB writes, shuffle per session `shuffleSeed`, strip nested answer keys recursively, and guarantee idempotent single-row grading per session.

**Independent Test**: Live Spring Boot test edits an active question after `/start` and sends concurrent `/active/session` polls + concurrent final `/sync` & `/submit` calls -> `0` read-only `500` errors, `1` `ExamResult` graded against original snapshot, and two candidates get distinct permutations matching their own `shuffleSeed`.

### Tests for User Story 1

- [x] T009 [P] [US1] Create `ExamPayloadGeneratorTest` in `backend/src/test/java/com/studentprep/exam/ExamPayloadGeneratorTest.java` verifying session-specific `shuffleSeed` ordering, passage grouping preservation, and recursive `correctOption` stripping (including nested `childQuestions[]`)
- [x] T010 [P] [US1] Add snapshot-freeze, read-only poll safety, and concurrent double-submit idempotency integration tests in `backend/src/test/java/com/studentprep/exam/ExamSnapshotAndGradingIntegrationTest.java`

### Implementation for User Story 1

- [x] T011 [US1] Refactor `ExamPayloadGenerator` in `backend/src/main/java/com/studentprep/exam/ExamPayloadGenerator.java` to build full raw snapshot (`buildSessionSnapshot(userId, shuffleSeed)`), shuffle deterministically using `shuffleSeed` (replacing UUID `hashSeed`), strip `correctOption` recursively before returning `ExamPayloadResponse`, and project `ExamPayloadResponse` directly from stored `snapshotPayload`
- [x] T012 [US1] Update `ExamSessionManager.startExam` & `resumeExistingSession` in `backend/src/main/java/com/studentprep/exam/ExamSessionManager.java` to capture/persist immutable `snapshotPayload` on launch, serve resumes strictly from `existing.getSnapshotPayload()`, and convert `getActiveSession` into strictly read-only projection returning `null` (or server remaining time) without calling `finalizeExamSession` inside `@Transactional(readOnly = true)`
- [x] T013 [US1] Update `ExamGraderListener` in `backend/src/main/java/com/studentprep/analytics/ExamGraderListener.java` to grade exclusively from `event.snapshotPayload()`, perform null-safe extraction of `answers`, enforce idempotency (`findByExamSessionId` + `DataIntegrityViolationException` catch), replace `System.out`/`System.err` with SLF4J `Logger`, and stop querying live `questionInternalAPI.getActiveQuestions()` during exam grading
- [x] T014 [US1] Fix `ExamResultRepository` in `backend/src/main/java/com/studentprep/analytics/ExamResultRepository.java` to use `>= 0.5` pass threshold (`countPassedExams`, `countPassedExamsByExamId`) and filter `moderationStatus IN ('ELIGIBLE', 'CLEARED')` across official average/pass-rate/completion aggregations

**Checkpoint**: US1 independently verified â€” frozen snapshots, zero read-only 500s, recursive answer stripping, deterministic per-session shuffling, and single `ExamResult` idempotency pass.

---

## Phase 4: User Story 2 - Authoritative Server Deadlines, Offline Grace Cutoff & Sitting Policy (Priority: P1)

**Goal**: Enforce `expected_end_time` across `/sync` (`30s` sync grace), `/submit` (`900s` / 15m offline cutoff), auto-expiry sweeper, and single-sitting blocking (`409` on completed/expired `/start`).

**Independent Test**: Integration test advances clock / backdates session `start_time` & `expected_end_time` to verify `410 Gone` on overdue non-final `/sync`, `LATE_SUBMISSION_FLAGGED` within 15m `/submit`, `410 Gone` past 15m `/submit`, and `409 Conflict` on repeat `/start`.

### Tests for User Story 2

- [x] T015 [P] [US2] Add deadline enforcement, 30s sync grace, 15m offline cutoff, idempotent terminal guard (`TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`), and single-sitting `409` tests to `backend/src/test/java/com/studentprep/exam/ExamSnapshotAndGradingIntegrationTest.java`

### Implementation for User Story 2

- [x] T016 [US2] Add `@NotNull Map<String, Object> statePayload` validation to `backend/src/main/java/com/studentprep/exam/dto/ExamSyncRequest.java` and return `expectedEndTime` + `serverTime` in `ExamStartResponse.java`
- [x] T017 [US2] Implement deadline & sitting state machine in `backend/src/main/java/com/studentprep/exam/ExamSessionManager.java`: set `expectedEndTime = now + durationMinutes` at `/start`; block non-superseded `SUBMITTED`/`TIME_EXPIRED`/`LATE_SUBMISSION_FLAGGED` on `/start` (`409 Conflict`) and finalize expired `IN_PROGRESS` sessions on resume in a writable transaction before returning `409`; reject non-final `/sync` past `expectedEndTime + syncGraceSeconds` (`410 Gone` after expiring session cleanly); accept `/submit` / `isFinal` up to `expectedEndTime + offlineSubmitGraceSeconds` (tagging `> syncGraceSeconds` as `LATE_SUBMISSION_FLAGGED`), reject `> offlineSubmitGraceSeconds` (`410 Gone`), preserve `TIME_EXPIRED` status on expiry finalization, and block all terminal states (`SUBMITTED`, `TIME_EXPIRED`, `LATE_SUBMISSION_FLAGGED`, `FLAGGED_TAB_SWITCH`) from double-finalization
- [x] T018 [US2] Implement ShedLock-protected `ExamExpiryScheduler` (`@Scheduled` + `@SchedulerLock(name = "exam_expiry_sweep")`) in `backend/src/main/java/com/studentprep/exam/ExamExpiryScheduler.java` finalizing `IN_PROGRESS` sessions past `expected_end_time + offline_submit_grace_seconds` as `TIME_EXPIRED` in writable transactions

**Checkpoint**: US2 independently verified â€” deadline enforcement, 15m offline `/submit` grace, background expiry sweep, and single-sitting guard work end-to-end.

---

## Phase 5: User Story 3 - Proctoring Moderation, Malpractice Enforcement & Result Slip Gating (Priority: P1)

**Goal**: Prevent `/sync` strike wipe, immediately revoke active Redis sessions and payloads on malpractice flagging, suppress grading/leaderboard/slips for `FLAGGED_TAB_SWITCH`, withhold `LATE_SUBMISSION_FLAGGED` from leaderboard & official student slips until admin review, and provide `ROLE_ADMIN`-only `clear-late`, `void`, and `grant-retake` controls.

**Independent Test**: Trigger `FLAGGED_TAB_SWITCH` via sync/submit -> verify Redis session key deleted immediately, `GET /api/v1/exams/active/payload` returns `403`, zero `ExamResult` saved, `GET /*/slip` returns `403`, and `/api/v1/student/results` reports `UNDER_REVIEW`. Test `LATE_SUBMISSION_FLAGGED` -> blocked from leaderboard & student slips until `ROLE_ADMIN` calls `clear-late` (`ROLE_SUPERVISOR` receives `403`).

### Tests for User Story 3

- [x] T019 [P] [US3] Create `ProctoringAndModerationIntegrationTest` in `backend/src/test/java/com/studentprep/analytics/ProctoringAndModerationIntegrationTest.java` covering monotonic strikes, `FLAGGED_TAB_SWITCH` Redis token eviction + `ExamResult` suppression + slip `403`, `LATE_SUBMISSION_FLAGGED` withholding + `clear-late` / `void` / `grant-retake`, and `ROLE_SUPERVISOR` `403` enforcement
- [x] T020 [P] [US3] Update `StudentResultsControllerTest` & `StudentResultServiceTest` in `backend/src/test/java/com/studentprep/analytics/` for flagged/pending-review slip blocking (`403`)

### Implementation for User Story 3

- [x] T021 [US3] Update `ExamController.getActivePayload` in `backend/src/main/java/com/studentprep/exam/ExamController.java` and `ExamSessionManager.syncExam` in `backend/src/main/java/com/studentprep/exam/ExamSessionManager.java` to reject `FLAGGED_TAB_SWITCH` (`403`), compute `tabSwitchCount = max(existing, reported)` preserving server fields (`answers` merge safety, strike counts), publish `ExamSessionFlaggedEvent` when `tabSwitchCount >= 3` or `FLAGGED_TAB_SWITCH`, and serve active `/active/payload` from frozen snapshot
- [x] T022 [US3] Create `ExamFlaggedSessionListener` in `backend/src/main/java/com/studentprep/security/ExamFlaggedSessionListener.java` (`@TransactionalEventListener(AFTER_COMMIT)` / `@ApplicationModuleListener`) that resolves canonical student registration via `StudentInternalAPI` and revokes `SessionRedisKeys.sessionKey(canonicalId)` immediately without creating any circular dependency (`security -> exam`)
- [x] T023 [US3] Update `ExamGraderListener` (`backend/src/main/java/com/studentprep/analytics/ExamGraderListener.java`) and `StudentResultService` + `StudentResultsController` (`backend/src/main/java/com/studentprep/analytics/`): skip grading + leaderboard completely for `FLAGGED_TAB_SWITCH`, save `LATE_SUBMISSION_FLAGGED` with `moderationStatus = PENDING_REVIEW` and skip `LeaderboardService.updateScore` until cleared, return `"UNDER_REVIEW"` / `"PENDING_REVIEW"` status metadata for students, and throw `AccessDeniedException` (`403`) on any slip request (`me/results/*/slip`, `latest/slip`, or voided/flagged admin slips) unless `ELIGIBLE` or `CLEARED`
- [x] T024 [US3] Create `AdminExamModerationController` in `backend/src/main/java/com/studentprep/exam/AdminExamModerationController.java` + update `SecurityConfig.java` (`ROLE_ADMIN` only for `POST /api/v1/admin/exams/sessions/*/clear-late`, `POST /api/v1/admin/exams/sessions/*/void`, `POST /api/v1/admin/students/*/grant-retake`; `ROLE_ADMIN` + `ROLE_SUPERVISOR` `GET /api/v1/admin/analytics/flagged-sessions`), publishing `ExamModerationUpdatedEvent` consumed by an `analytics` listener (`ExamModerationListener`) to promote/void `ExamResult.moderationStatus` and sync `LeaderboardService` cleanly
- [x] T025 [US3] Update `frontend/src/pages/student/Dashboard.tsx` and `frontend/src/pages/student/ResultPage.tsx` to render explicit `"Under Administrative Review"` (`FLAGGED_TAB_SWITCH`) and `"Submission Pending Review"` (`LATE_SUBMISSION_FLAGGED`) UI cards cleanly when eligible results are withheld

**Checkpoint**: US3 independently verified â€” zero malpractice slips/leaderboard leaks, instant Redis eviction on flagging, and gated late moderation workflow work cleanly.

---

## Phase 6: User Story 4 - Shared-Kiosk Browser Isolation & Offline-First Exam Resilience (Priority: P1)

**Goal**: Scope browser IndexedDB & Redux state to the active candidate, wipe prior user/session data across all exit/switch boundaries, replace raw `fetch()` sync/submit calls with verified token-refresh Axios requests, persist frozen exam payloads for offline `/exam` reloads, precache fonts/icons/diagram SVGs + `/submit` Background Sync, and trigger monotonic auto-submit at `00:00:00`.

**Independent Test**: Automated Playwright tests on Chromium and WebKit verify (a) Student A exam/answers/termination never leak to Student B on the same browser without clearing site data manually, and (b) going offline mid-exam -> refreshing `/exam` -> answering -> reaching timer `00:00:00` or clicking Submit shows honest offline queued state (no false `/result` jump) and completes submission cleanly upon reconnect.

### Tests for User Story 4

- [x] T026 [P] [US4] Create live unmocked Playwright E2E spec `frontend/tests/exam-kiosk-integrity.spec.ts` covering shared-kiosk Student A -> logout / malpractice -> Student B isolation, offline reload with zero external CDN requests, offline answer persistence & verified submit recovery upon reconnect, timer expiry auto-submit, and Admin Dashboard recent results + late moderation flow across `chromium` and `webkit`

### Implementation for User Story 4

- [x] T027 [US4] Upgrade Dexie `StudentPrepDB` (`version(2)`) with `ownerIdentifier`, `questions`, `contexts`, `student`, `expectedEndTimeMs`, `syncPendingFinal`, and legacy v1 wipe upgrade hook in `frontend/src/store/db.ts`, and add `clearAllLocalExamData()` / `resetExamState` action in `frontend/src/store/examSlice.ts`
- [x] T028 [US4] Call `clearAllLocalExamData()` + `dispatch(resetExamState())` consistently across logout handlers (`frontend/src/pages/student/Dashboard.tsx`, `ResultPage.tsx`, `ExamDashboard.tsx`), `App.tsx` auth interceptor (`SESSION_EVICTED`, `auth_expired`), and `frontend/src/pages/auth/Login.tsx` when stored local owner differs from authenticated JWT `sub`
- [x] T029 [US4] Refactor `initializeExam`, `syncExamData`, `fetchExamPayload`, and `hydrateFromServer` in `frontend/src/store/examSlice.ts` and `frontend/src/store/persistenceMiddleware.ts` to replace raw `fetch()` calls with verified `axios` requests (automatic `401` -> `/refresh` retry, response status validation, structured `{ synced, finalConfirmed, offlineQueued, expired, flagged, errorMessage }` outcome), persist frozen questions/contexts/expectedEndTimeMs locally per `ownerIdentifier`, preserve `tabSwitchCount` & `flagged` in `/sync` payloads, and recover seamlessly from Dexie when refreshing `/exam` offline
- [x] T030 [US4] Update `frontend/src/components/SubmitButton.tsx`, `frontend/src/components/ExamTimer.tsx`, and `frontend/src/pages/exam/ExamDashboard.tsx` to navigate to `/result` ONLY after confirmed server `200`, show non-destructive `"Offline â€” Final Submission Queued"` / error alerts on offline/rejected submit, compute monotonic countdown from `expectedEndTimeMs` + `visibilitychange` clock snap, trigger one-shot auto-submit at `<= 0`, force logout cleanly after malpractice termination banner, and fix case-insensitive `asset?.type?.toLowerCase() === 'image'` rendering
- [x] T031 [P] [US4] Bundle `@fontsource-variable/inter` in `frontend/src/index.css`, remove external Google Fonts CDN `<link>` tags from `frontend/index.html`, and update `frontend/vite.config.ts` (`globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2,woff,ttf}']`, runtime `NetworkOnly` + `BackgroundSyncPlugin` for `/api/v1/exams/active/submit`, and bundled manifest icons)

**Checkpoint**: US4 independently verified â€” Chromium & WebKit E2E tests confirm zero cross-student kiosk leaks and seamless offline reload/font/asset/submit resilience.

---

## Phase 7: User Story 5 - Outbox Self-Healing, Cache Fault Tolerance & Admin Analytics (Priority: P2)

**Goal**: Enable ShedLock-protected outbox recovery, fail-open Redis cache/eviction/leaderboard paths with post-commit eviction, fix `AnalyticsController.getResults` null safety & `admin/Dashboard.tsx` `PagedResponse` mapping, and surface flagged/late moderation UI for admins.

**Independent Test**: Integration test verifies `ExamCacheService` and `ExamGraderListener`/`LeaderboardService` succeed cleanly against PostgreSQL when Redis throws `DataAccessException`, `OutboxRecoveryScheduler` republishes incomplete `event_publication` records under ShedLock, and Playwright Admin Dashboard test verifies "Recent Completed Exams" populates cleanly and clear-late/void/retake UI actions work.

### Tests for User Story 5

- [x] T032 [P] [US5] Create `OutboxAndRedisResilienceIntegrationTest` in `backend/src/test/java/com/studentprep/analytics/OutboxAndRedisResilienceIntegrationTest.java` verifying ShedLock `OutboxRecoveryScheduler` recovery of failed `ExamSubmittedEvent`, fail-open `ExamCacheService` & `QuestionService` DB fallback on mocked/down Redis, `LeaderboardService` idempotent ZSet updates + `topN` bounds, and `AnalyticsController.getResults` with null `topicBreakdown`

### Implementation for User Story 5

- [x] T033 [US5] Create ShedLock-protected `OutboxRecoveryScheduler` in `backend/src/main/java/com/studentprep/common/OutboxRecoveryScheduler.java` (`@Scheduled` + `@SchedulerLock(name = "outbox_recovery_sweep")`, calling `IncompleteEventPublications.resubmitIncompletePublicationsOlderThan`)
- [x] T034 [P] [US5] Harden `ExamCacheService` (Redis `DataAccessException` fallback to `QuestionInternalAPI` + synchronized single-flight mutex) in `backend/src/main/java/com/studentprep/exam/ExamCacheService.java`, convert `QuestionService.evictQuestionsCache` (`backend/src/main/java/com/studentprep/questionbank/QuestionService.java`) & `SubjectController` rename eviction (`backend/src/main/java/com/studentprep/questionbank/SubjectController.java`) to post-commit `TransactionSynchronization` fail-open eviction, and harden `LeaderboardService` (`try/catch DataAccessException`, `opsForZSet().add(...)`, `removeEntry`, clamp `topN` 1..100) in `backend/src/main/java/com/studentprep/analytics/LeaderboardService.java`
- [x] T035 [US5] Fix `AnalyticsController.getResults` nullable `topicBreakdown` NPE (`HashMap`/typed DTO including `examSessionId`, `moderationStatus`, `sessionStatus`) and add `getFlaggedSessions` delegation in `backend/src/main/java/com/studentprep/analytics/AnalyticsController.java`
- [x] T036 [US5] Fix `frontend/src/pages/admin/Dashboard.tsx` (`recRes?.data?.data` instead of `recRes?.data?.data?.content`) + add Pending Moderation / Flagged Sessions review panel (`Clear Late`, `Void`, `Grant Retake` controls for `ROLE_ADMIN`, read-only for `ROLE_SUPERVISOR`) in `frontend/src/pages/admin/Dashboard.tsx` and `frontend/src/pages/admin/ResultsPage.tsx`

---

## Phase 8: Polish, Documentation & Full Release Gates

**Purpose**: Synchronize API contract docs, inspect architecture boundaries, and pass every release gate cleanly against live running containers.

- [x] T037 [P] Update `docs/api_contract.md` with Slice 003 endpoints (`clear-late`, `void`, `grant-retake`, `flagged-sessions`), updated `/start` / `/sync` / `/submit` deadline & moderation contracts, and `410` / `409` / `403` error codes
- [x] T038 Run full backend verification (`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'; mvn -f backend/pom.xml test`) ensuring 100% pass across `ModulithArchitectureTest`, Slice 001/002/003 unit + live Postgres/Redis integration tests
- [x] T039 Run full frontend build & lint verification (`npm --prefix frontend run lint` + `npm --prefix frontend run build`) and verify `frontend/dist/sw.js` precaches `.woff2`/`.woff`/`.ttf`/`.svg` offline assets
- [x] T040 Start live backend + frontend and run unmocked Playwright suite (`npx --prefix frontend playwright test`) across Chromium and WebKit (`auth-hardening.spec.ts` + `exam-kiosk-integrity.spec.ts`), confirming 0 failures

---

## Dependencies & Execution Order

- **Phase 1 (T001â€“T003)**: Starts immediately; T002 & T003 parallelizable.
- **Phase 2 (T004â€“T008)**: Blocks all user stories; `V22` schema (`T004`) runs first, `T006` & `T007` parallelizable after `T005`, `T008` follows `T005`â€“`T007`.
- **Phases 3â€“5 (US1, US2, US3 â€” P1 Backend Core)**: Sequential/staged on `ExamSessionManager` (`T012` -> `T017` -> `T021`) while tests, `ExamPayloadGenerator`, `ExamFlaggedSessionListener`, `AdminExamModerationController`, and student UI cards execute cleanly.
- **Phase 6 (US4 â€” P1 Kiosk & Offline PWA)**: Can start in parallel once `T002` & Phase 2 contracts stabilize (`T027`â€“`T031`), validated against backend APIs in `T026`.
- **Phase 7 (US5 â€” P2 Outbox, Redis Fail-Open & Analytics)**: Parallelizable across `OutboxRecoveryScheduler`, `ExamCacheService`/`QuestionService`/`LeaderboardService`, and `AnalyticsController`/Admin Dashboard.
- **Phase 8 (T037â€“T040)**: Final contract sync and full end-to-end release verification.

## Notes

- All Java edits MUST use explicit imports, constructor injection (`@RequiredArgsConstructor` avoided per `.agents/rules/backend-standards.md`), zero inline FQNs, and SLF4J loggers instead of `System.out`/`System.err`.
- UI updates MUST follow `.agents/rules/ui-standards.md` (Inter typography, JAMB Green `#008751`, `bg-slate-50`/`bg-zinc-50`, `text-slate-900`).
