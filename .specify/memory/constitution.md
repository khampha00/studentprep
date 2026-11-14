<!--
Sync Impact Report
- Version change: 1.0.0 -> 1.1.0 (MINOR: expand Principle II for audited ROLE_ADMIN encrypted student credential PDF export)
- Modified principles: II. Zero-Trust Security, Identity & Secrets Hygiene (allow AEAD-encrypted student PIN storage alongside Argon2id pin_hash strictly for ROLE_ADMIN audited credential PDF distribution; Admin/Supervisor credentials remain irreversible; list APIs, CSV exports, slips-for-non-admins, logs, and outbox events remain PIN-free)
- Added sections: None
- Removed sections: None
- Follow-up TODOs: Slice 002 (002-credential-pdf-export) will add schema storage for encrypted student PINs, audited bulk/single PDF endpoints, and UI download actions; remove this Sync Impact Report comment before committing.
-->
# StudentPrep Constitution

## Core Principles

### I. Modular Monolith & Asynchronous Outbox Integrity
All backend code MUST preserve strict Spring Modulith module boundaries (`security`, `student`, `questionbank`, `ingestion`, `exam`, `analytics`, plus `common` and `config`) verified continuously by `ModulithArchitectureTest`. Cross-module mutations MUST NOT invoke sibling repositories directly; modules MUST communicate through local-transaction `ApplicationEventPublisher` events backed by the Transactional Outbox (`event_publication`) with ShedLock-protected `@Scheduled` sweeps and idempotent consumers.

### II. Zero-Trust Security, Identity & Secrets Hygiene
Plaintext PINs/passwords and `NoOpPasswordEncoder` are strictly forbidden; all credentials MUST authenticate against strong adaptive hashes (BCrypt or Argon2id), Administrator/Supervisor passwords MUST remain strictly one-way hashed, and student PINs MUST never be returned in list APIs, bulk CSV roster exports, non-admin responses, logs, or outbox events. For single-school exam-day distribution, student login PINs MAY additionally be stored in authenticated AEAD-encrypted form (`AES-GCM` keyed from server environment configuration) alongside their adaptive `pin_hash` strictly for `ROLE_ADMIN`-only, explicitly audited credential PDF downloads (bulk school roster PDF and single-student credential PDF) so slips can be reprinted without rotating active PINs. Unauthenticated or expired JWT requests MUST return `401 Unauthorized` via an explicit `AuthenticationEntryPoint` (never `403`), access and refresh tokens MUST enforce distinct `token_type` claims, logout MUST verify the token `jti` before revoking Redis sessions, `/api/v1/auth/*` MUST be rate-limited, CORS and cookie flags MUST derive cleanly from environment configuration, and zero raw secrets or debug credentials may be committed or leaked into `ingestion_jobs` error strings.

### III. Exam, Snapshot & Grading Integrity (NON-NEGOTIABLE)
Every `ExamSession` MUST freeze its selected question IDs, passage contexts, options, and answer keys (along with `shuffle_seed`) at `/start` so live Question Bank edits or archives cannot alter in-flight or historical scoring. The server (`start_time`, `expected_end_time` + explicit latency grace) is the sole authority for exam deadlines and terminal states (`SUBMITTED`, `TIME_EXPIRED`, `FLAGGED_TAB_SWITCH`, `LATE_SUBMISSION_FLAGGED`), read-only transactions MUST NEVER perform writes, `exam_results` MUST enforce `UNIQUE(exam_session_id)` with idempotent grading, and malpractice-terminated sessions MUST NEVER earn valid official result slips or leaderboard rank.

### IV. Shared-Kiosk Isolation & Offline-First Resilience
Client IndexedDB (`StudentPrepDB`) and Redux state MUST be strictly scoped to the authenticated user/session and completely cleared on logout, session eviction, or termination so successive students on a shared lab PC never inherit prior questions, answers, or timers. All `/sync` and `/submit` mutations MUST validate HTTP success and trigger token refresh cleanly (no silent raw `fetch()` failures or false `/result` navigations), Workbox PWA MUST precache all offline app bundles plus Inter/KaTeX fonts (`.woff2`, `.woff`, `.ttf`) and Background Sync `/api/v1/exams/active/submit`, and timer expiry MUST trigger monotonic client auto-submit while still respecting server deadline enforcement.

### V. Contract-First API & Test-First Verification
Every REST endpoint MUST strictly follow `docs/api_contract.md` (`{ data, meta }` envelopes for 2xx responses and RFC 7807 `ProblemDetails` for 4xx/5xx responses) and Bean Validation + `@RestControllerAdvice` exception mapping; changing any DTO, status code, or query contract MUST update `docs/api_contract.md` in the same slice plan. No backend or frontend feature or remediation slice is complete without executable tests matching its `spec.md` acceptance scenarios: `@SpringBootTest` + Flyway/Postgres/Redis integration tests for persistence, concurrency, and transactional flows, plus clean `npm run lint`, `npm run build`, and unmocked Playwright E2E coverage on Chromium and WebKit.

### VI. Code Quality, Schema & UI Discipline
Backend Java classes MUST use explicit imports (no inline FQNs) and constructor injection (no `@Autowired` fields) per `.agents/rules/backend-standards.md`, all schema changes MUST ship via idempotent Flyway migrations without `baseline-on-migrate: true` in production, and admin bulk CSV imports/exports MUST run transaction-safely with formula-injection sanitization. Frontend views MUST enforce `.agents/rules/ui-standards.md` and `docs/frontend_architecture.md`: Inter typography, JAMB Green (`#008751`), eye-strain-safe `bg-slate-50`/`bg-zinc-50` surfaces with `text-slate-900`, `-ml-2` red required asterisks, shadcn `<Popover>` + `<Calendar>` DatePickers, `<Accordion>` and `grid-cols-2 gap-y-2` structure for dense views, and sanitized (`DOMPurify`) KaTeX rendering without `.katex` CSS border overrides.

## Architecture, Security & Design Constraints

- **Stack Baseline:** Java 17 (`pom.xml`), Spring Boot 3.2+, Spring Modulith, PostgreSQL 16 + Flyway, Redis (session `jti` enforcement, question cache, rate limiting, leaderboard), MinIO/S3 object storage with MIME/size validation, Python FastAPI `docling-parser` sidecar (authenticated via `DOCLING_API_KEY`, bounded uploads, deterministic temp-file cleanup), and React + TypeScript + Vite + Tailwind CSS + shadcn/ui + Redux Toolkit + Dexie.js + KaTeX + Workbox.
- **Availability & Fail-Open Rules:** Read-path exam caching and sync rate limiting MUST degrade safely without crashing an active exam on transient Redis unavailability, while production runtime packaging MUST run behind a reverse proxy routing `/api/v1/*` and expose Actuator health probes.
- **Role Boundaries:** `ROLE_STUDENT` is restricted to own sessions/results; `ROLE_SUPERVISOR` is read-only for live monitoring and MUST NOT mutate questions, students, or ingestion state (nor access student credential PDFs); `ROLE_ADMIN` owns administrative CRUD, audited credential PDF downloads, ingestion, and HITL approval, all audited without high-frequency polling bloat.

## Development Workflow & Verification Gates

- **Spec-Kit Slice Lifecycle:** All new capabilities and deployment fixes on `deployment` proceed in vertical slices via `/speckit-specify` → `/speckit-clarify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-analyze` → `/speckit-implement`. Product ambiguities (credential onboarding UX, malpractice slip handling, late offline cutoff) MUST be resolved in `spec.md` before planning code.
- **Release Gate Commands:** Before merging or marking a slice done, verify `mvn test` (including `ModulithArchitectureTest` and live integration specs), `npm run lint`, `npm run build`, and `npx playwright test` against running infrastructure (`docker-compose.yml` + Spring Boot backend + Docling parser).

## Governance

This Constitution supersedes ad-hoc patches, conflicting historical docs, and unverified manual edits across `studentprep`. `.agents/rules/backend-standards.md` and `.agents/rules/ui-standards.md` remain active companion style enforcement rules and MUST stay consistent with this document. Any deviation from a principle requires an explicit amendment recorded here alongside migration/test updates: `MAJOR` bumps remove or redefine existing invariants, `MINOR` bumps add principles or materially expand constraints, and `PATCH` bumps refine wording or non-semantic details. Every specification review, `/speckit-analyze` pass, and PR into `deployment` or `main` MUST verify compliance with all six principles before merge.

**Version**: 1.1.0 | **Ratified**: 2026-11-13 | **Last Amended**: 2026-11-14
