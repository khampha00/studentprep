# Implementation Plan: Exam Session Integrity, Proctoring Moderation & Shared-Kiosk Offline Resilience

**Branch**: `deployment` (Slice `003-exam-kiosk-integrity`) | **Date**: 2026-11-14 | **Spec**: [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/spec.md)  
**Input**: Feature specification from [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/spec.md)

## Summary

Slice 003 resolves every remaining exam engine, snapshot, proctoring moderation, outbox/Redis resilience, seed question, analytics, and shared-kiosk/offline PWA vulnerability blocking safe CBT deployment. On the backend, `V22` adds `snapshot_payload JSONB` + `superseded` to `exam_sessions`, enforces `UNIQUE(exam_session_id)` + `moderation_status` on `exam_results`, repairs broken `V2`/`V4` seed data into standard linked `QuestionContext`/flat `MCQ` questions with self-hosted diagrams, eliminates read-only transaction writes from `getActiveSession`, freezes session questions/answers/shuffle order at `/start`, enforces authoritative server deadlines (`120m` + `30s` sync grace + `15m` offline `/submit` grace) and single-sitting policy, suppresses grading/slips/leaderboard for `FLAGGED_TAB_SWITCH` (immediately revoking Redis sessions across Modulith boundaries), gates `LATE_SUBMISSION_FLAGGED` behind admin moderation (`clear-late`/`void`/`grant-retake`), activates ShedLock-protected Outbox & expiry sweepers, and makes Redis exam caching/eviction/leaderboard cleanly fail-open. On the frontend, Dexie `v2` & Redux isolate all state by active student identity with automatic cleanup on logout/eviction/malpractice/user switch, Axios replaces raw `fetch()` calls for verified token-refresh-capable `/sync` & `/submit`, frozen payloads cache locally for offline reload recovery with self-hosted Inter/KaTeX fonts + Workbox `/submit` Background Sync, monotonic timers enforce automatic `00:00:00` submission, image normalization fixes asset rendering, and Admin Dashboard parses `PagedResponse` recent results and displays flagged/late moderation controls.

## Technical Context

**Language/Version**: Java 17 (`backend/pom.xml`, Spring Boot 3.2.4, Spring Modulith 1.1.3) + TypeScript ~6.0.2 / React 19 (`frontend/package.json`)  
**Primary Dependencies**: Spring Web, Spring Data JPA, Spring Security, Spring Data Redis, Flyway 10.10.0, ShedLock 5.13.0, OpenPDF, React, Redux Toolkit, Dexie 4, Axios, KaTeX, Workbox (`vite-plugin-pwa`), Tailwind CSS, shadcn/ui  
**Storage**: PostgreSQL 16 (Flyway `V22`), Redis (session `jti`, fail-open question cache, rate-limiting, leaderboard ZSet), Browser IndexedDB (`StudentPrepDB` v2) & CacheStorage (Workbox SW)  
**Testing**: JUnit 5 + Mockito + `@SpringBootTest` live Postgres/Redis integration tests (`mvn -f backend/pom.xml test` incl. `ModulithArchitectureTest`), `oxlint`, `tsc -b && vite build`, unmocked Playwright (Chromium + WebKit)  
**Target Platform**: Single-school LAN / Docker Compose Linux server & school lab desktop browsers (Chromium, WebKit/Safari) with intermittent Wi-Fi  
**Project Type**: Modular Monolith Web Application (`backend/` + `frontend/` + `services/docling-parser/`)  
**Performance Goals**: `< 200ms` `/sync` & `/active/payload` latency under lab concurrency; zero DB lock stalls on heartbeats; `< 5s` offline reload recovery  
**Constraints**: Strict Spring Modulith unidirectional module dependencies (`security -> exam -> questionbank/student`, `analytics -> exam/student`), offline zero-CDN execution, RFC 7807 error contracts  
**Scale/Scope**: Single-school concurrent CBT cohort (hundreds of workstations per sitting, 5000+ student roster)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

1. **Principle I (Modular Monolith & Asynchronous Outbox Integrity)**: PASS.
   - `exam` NEVER imports or injects `security` or `analytics`. When `FLAGGED_TAB_SWITCH` occurs, `exam` publishes `ExamSessionFlaggedEvent` (`com.studentprep.exam`) via `ApplicationEventPublisher`; `security` handles revocation via `@TransactionalEventListener(AFTER_COMMIT)`. `analytics` continues depending only on `exam` (`ExamInternalAPI`, `ExamSubmittedEvent`) and `student`.
   - Scheduled `OutboxRecoveryScheduler` uses `@SchedulerLock` and Spring Modulith `IncompleteEventPublications`.
2. **Principle II (Zero-Trust Security, Identity & Secrets Hygiene)**: PASS.
   - Revoking active Redis session immediately upon malpractice termination closes the residual token gap; `ROLE_SUPERVISOR` retains read-only monitoring/moderation-queue views while `clear-late`, `void`, `grant-retake`, and credential/result PDFs stay restricted to `ROLE_ADMIN`. Zero plaintext PINs added.
3. **Principle III (Exam, Snapshot & Grading Integrity)**: PASS.
   - Directly implements immutable session snapshots, `expected_end_time` enforcement + 15m offline cutoff, single-sitting guard, zero read-only tx writes, `UNIQUE(exam_session_id)` + idempotent grading, `FLAGGED_TAB_SWITCH` exclusion, and `LATE_SUBMISSION_FLAGGED` moderation gating.
4. **Principle IV (Shared-Kiosk Isolation & Offline-First Resilience)**: PASS.
   - Directly implements `ownerIdentifier`-scoped `StudentPrepDB` v2 + full client purge across logout/eviction/malpractice/identity switch, verified Axios sync/submit with refresh retry, offline payload persistence, self-hosted Inter/KaTeX `.woff2`/`.woff`/`.ttf` + PWA `/submit` Background Sync, and monotonic deadline timer with `00:00:00` auto-submit.
5. **Principle V (Contract-First API & Test-First Verification)**: PASS.
   - [contracts/api-exam-kiosk-integrity.md](file:///c:/Users/User/Desktop/studentprep/specs/003-exam-kiosk-integrity/contracts/api-exam-kiosk-integrity.md) defines exact REST envelopes & RFC 7807 status codes; `docs/api_contract.md` will be updated in implementation; tests span unit, live Postgres/Redis integration, and unmocked Playwright Chromium + WebKit specs.
6. **Principle VI (Code Quality, Schema & UI Discipline)**: PASS.
   - Explicit imports + constructor injection per `.agents/rules/backend-standards.md` (clean up existing `@RequiredArgsConstructor` if modified? Note: constructor injection explicitly preferred, zero inline FQNs, replace `System.out`/`System.err` with SLF4J loggers); Tailwind `bg-slate-50`, `#008751`, Inter font, shadcn accordions/dialogs per `.agents/rules/ui-standards.md`.

*Post-Phase-1 Re-check*: All schema (`V22`), cross-module event flow, endpoints, and client persistence design verified clean against Principles I–VI. No Constitution violations requiring justification.

## Project Structure

### Documentation (this feature)

```text
specs/003-exam-kiosk-integrity/
├── spec.md                                     # Feature specification (resolved scope & policy decisions)
├── checklists/
│   └── requirements.md                         # Specification quality checklist
├── plan.md                                     # This implementation plan
├── research.md                                 # Technical & architectural decisions
├── data-model.md                               # V22 Flyway DDL, entities, IndexedDB v2 schema
├── quickstart.md                               # Live verification & test suite commands
├── contracts/
│   └── api-exam-kiosk-integrity.md             # REST endpoint, validation, & error contracts
└── tasks.md                                    # Phase 2 actionable checklist (generated next)
```

### Source Code Layout Affected

```text
backend/src/main/java/com/studentprep/
├── exam/
│   ├── ExamSession.java                        # Map expectedEndTime, snapshotPayload, superseded
│   ├── ExamSessionRepository.java              # Non-superseded lookup queries + count queries
│   ├── ExamSessionManager.java                 # Frozen snapshots, deadlines, offline grace, single-sitting, admin retake/clear/void
│   ├── ExamPayloadGenerator.java               # Session-seed shuffling, recursive correctOption stripping, snapshot-backed generation
│   ├── ExamCacheService.java                   # Fail-open Redis catch/fallback + single-flight stampede guard
│   ├── ExamController.java                     # Malpractice /active/payload guard, @Valid request DTO, deadline responses
│   ├── ExamInternalAPI.java                    # Flagged/late queries & accurate completion count
│   ├── ExamInternalAPIImpl.java                # Remove @Autowired @Lazy & inline FQNs; implement session/moderation lookups
│   ├── ExamSubmittedEvent.java                 # Carry terminalStatus & frozen snapshotPayload
│   ├── ExamSessionFlaggedEvent.java            # Cross-module event triggering Redis session eviction
│   ├── AdminExamModerationController.java      # clear-late, void, grant-retake, flagged-sessions endpoints
│   ├── ExamTimingProperties.java               # ConfigurationProperties for duration, sync grace, 15m offline cutoff
│   ├── ExamExpiryScheduler.java                # ShedLock sweeper closing expired unsubmitted sessions
│   └── dto/                                    # Validated ExamSyncRequest, ExamStartResponse updates, AdminSessionSummaryDto
├── analytics/
│   ├── ExamResult.java                         # unique examSessionId, ExamResultModerationStatus, moderation audit columns
│   ├── ExamResultModerationStatus.java         # ELIGIBLE, PENDING_REVIEW, CLEARED, VOIDED
│   ├── ExamResultRepository.java               # >= 0.5 pass threshold, moderation-filtered stats & findByExamSessionId
│   ├── ExamGraderListener.java                 # Grade from frozen snapshotPayload, idempotent save, malpractice skip, fail-open leaderboard
│   ├── LeaderboardService.java                 # Fail-open Redis ZSet ops, set score (no incrementScore), removeEntry, bounded topN
│   ├── StudentResultService.java               # Block flagged/pending/voided slips, surface review status
│   ├── StudentResultsController.java           # Expose student review status & gated slip endpoints
│   └── AnalyticsController.java                # Null-safe HashMap/DTO getResults, flagged-sessions endpoint, moderation filter
├── questionbank/
│   └── QuestionService.java                    # TransactionSynchronization AFTER_COMMIT fail-open Redis cache eviction (and SubjectController)
├── security/
│   ├── SecurityConfig.java                     # ROLE_ADMIN vs ROLE_SUPERVISOR routes for new moderation/analytics endpoints
│   └── ExamFlaggedSessionListener.java         # AFTER_COMMIT listener evicting Redis active workstation token on malpractice
└── common/
    ├── OutboxRecoveryScheduler.java            # ShedLock-protected IncompleteEventPublications sweep
    └── GlobalExceptionHandler.java             # Map ExamDeadlineExpiredException -> 410 Gone RFC 7807 ProblemDetail

backend/src/main/resources/
├── application.yml                             # exam.timing.*, exam.outbox.*, spring.modulith.events.republish-outstanding-events-on-restart: true
└── db/migration/
    └── V22__Exam_Snapshot_Integrity_Moderation_And_Seed_Fix.sql

frontend/
├── index.html                                  # Remove Google Fonts CDN links
├── vite.config.ts                              # Precache woff2/woff/ttf/svg + icons + BackgroundSync /api/v1/exams/active/submit
├── public/assets/questions/                    # Offline animal-cell-diagram.svg, linear-graph.svg + PWA icons
├── src/
│   ├── index.css                               # @import "@fontsource-variable/inter";
│   ├── lib/auth.ts & App.tsx                   # Kiosk state purge helper invoked on logout, session_expired, auth_expired
│   ├── store/db.ts                             # Dexie v2 scoped by ownerIdentifier + offline snapshot fields
│   ├── store/examSlice.ts                      # Scoped rehydration, verified Axios sync/submit (no raw fetch), expectedEndTimeMs, resetExamState
│   ├── store/persistenceMiddleware.ts          # Persist owner-scoped snapshot + monotonic timeLeft
│   ├── components/QuestionRenderer.tsx & ExamTimer.tsx & SubmitButton.tsx # Offline asset render + verified submit navigation + auto-submit
│   ├── pages/auth/Login.tsx                    # Clear foreign kiosk IndexedDB/Redux on different student login
│   ├── pages/exam/ExamDashboard.tsx            # Monotonic wall-clock timer, 00:00:00 auto-submit, offline queue banner, case-insensitive 'image'
│   ├── pages/student/{Dashboard.tsx,ResultPage.tsx} # Malpractice "Under Administrative Review" & Late "Pending Review" states
│   └── pages/admin/{Dashboard.tsx,ResultsPage.tsx}   # Fix PagedResponse recRes.data.data, flagged sessions moderation panel (Clear/Void/Grant Retake)

docs/
└── api_contract.md                             # Updated contract definitions for Slice 003 endpoints & statuses
```

## Complexity Tracking

No Constitution violations or extra architectural layers needed. `V22` snapshot storage, `OutboxRecoveryScheduler`, `ExamFlaggedSessionListener`, and Dexie `v2` kiosk isolation use the existing Spring Modulith, ShedLock, Flyway, and Redux/Dexie foundations cleanly.
