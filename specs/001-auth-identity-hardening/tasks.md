# Tasks: Authentication, Identity Integrity & Secrets Hardening (Slice 001)

**Input**: Design documents from `/specs/001-auth-identity-hardening/`
**Prerequisites**: [plan.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/plan.md) (required), [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/spec.md) (required), [research.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/research.md), [data-model.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/data-model.md), [contracts/](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/contracts/), [quickstart.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/quickstart.md)

**Tests**: Required per Constitution Principle V (`@SpringBootTest` + Flyway/Postgres/Redis integration tests, `pytest`, `npm run lint` & `build`, and unmocked Playwright E2E scenarios). Tests are listed before implementation inside each user story phase.

**Organization**: Grouped by user story (`US1`–`US4`) so each increment can be verified independently. No two tasks marked `[P]` in the same wave modify the same file.

---

## Phase 1: Setup & Repository Hygiene

**Purpose**: Remove committed debug utilities/artifacts, lock ignore rules, and expose required security configuration placeholders.

- [x] T001 Remove tracked debug utilities, test credential dumps, and build reports (`backend/src/main/java/com/studentprep/BcryptGen.java`, `backend/src/main/java/com/studentprep/HashGen.java`, `backend/src/test/java/com/studentprep/HashGenTest.java`, `fix.sql`, `parsed_pdf.json`, `test00.pdf`, `frontend/src/pages/admin/update.cjs`, `frontend/test-circ.js`, `frontend/test-katex.js`, `frontend/test-sqrt.js`, `frontend/dev-dist/*`, `frontend/playwright-report/*`, `frontend/test-results/*`) and update `.gitignore` and `frontend/.gitignore` to ignore `*.pdf`, `parsed_*.json`, `.env`, `.env.*` (except `.env.example`), `frontend/dev-dist/`, `playwright-report/`, and `test-results/`
- [x] T002 [P] Update `.env.example` with environment templates for `ADMIN_BOOTSTRAP_SECRET`, `DOCLING_API_KEY`, `DOCLING_PARSER_URL`, `CORS_ALLOWED_ORIGINS`, `COOKIE_SECURE`, `COOKIE_SAME_SITE`, `FLYWAY_BASELINE_ON_MIGRATE`, and `S3_PUBLIC_BASE_URL`
- [x] T003 [P] Configure security, cookie (`security.cookie.secure`, `security.cookie.same-site`), bootstrap (`security.bootstrap.admin-secret`), rate-limit (`security.rate-limit.*`), docling (`docling.parser.url`, `docling.api-key`), `s3.public-base-url`, and Flyway flags (`baseline-on-migrate: ${FLYWAY_BASELINE_ON_MIGRATE:false}`) in `backend/src/main/resources/application.yml` and enable `validate-on-migrate: true` in `backend/src/test/resources/application.yml`

---

## Phase 2: Foundational Security & Schema Prerequisites

**Purpose**: Establish cryptographic hashing, schema hardening, RFC 7807 security error responses, CORS/RBAC baseline, and production fail-fast checks before user story implementation.

**⚠️ CRITICAL**: No user story work begins until Phase 2 is complete.

- [x] T004 Create idempotent Flyway migration `backend/src/main/resources/db/migration/V18__Harden_Credentials_And_Pin_Reset.sql` to rename `users.pin` to `users.pin_hash` and `students.pin` to `students.pin_hash`, add `pin_reset_required BOOLEAN NOT NULL DEFAULT FALSE` to both tables, set `pin_reset_required = TRUE` and `pin_hash = '!LEGACY_INVALIDATED!'` on non-Argon2id/non-BCrypt `students` and `ROLE_STUDENT` `users` rows, and set unhashed `ADMIN-001` rows (e.g. after `V16`) to `'!ADMIN_BOOTSTRAP_REQUIRED!'` while leaving already-Argon2id-hashed `ADMIN-001` rows untouched
- [x] T005 [P] Implement `backend/src/main/java/com/studentprep/security/PinHashingPasswordEncoder.java` encoding strictly with `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`, verifying only `$argon2id$` and `$2a$`/`$2b$`/`$2y$` prefixes, and rejecting plaintext or unrecognized hashes
- [x] T006 [P] Update `backend/src/main/java/com/studentprep/common/GlobalExceptionHandler.java` so `MethodArgumentNotValidException`, `NoSuchElementException`, `ResponseStatusException`, `IllegalArgumentException`, and `IllegalStateException` consistently emit RFC 7807 `ProblemDetail` payloads without leaking internal stack traces, and create `backend/src/main/java/com/studentprep/common/SessionRedisKeys.java` defining canonical Redis key helpers (`sessionKey`, `examUnlockKey`, `loginFailIdKey`, `loginIpKey`, `refreshIpKey`, `syncKey` using trimmed `UPPERCASE` identifiers)
- [x] T007 Harden `backend/src/main/java/com/studentprep/security/SecurityConfig.java` to wire `PinHashingPasswordEncoder` (eliminating `NoOpPasswordEncoder`), add explicit `AuthenticationEntryPoint` (RFC 7807 `401 Unauthorized`) and `AccessDeniedHandler` (RFC 7807 `403 Forbidden`), restrict `/api/v1/students/**` & `/api/v1/student/**` to `ROLE_STUDENT`, enforce `ROLE_ADMIN` on mutating `/api/v1/admin/**`, `/api/v1/admin/students/export`, and `/api/v1/admin/students/*/slip` while preserving read-only `ROLE_SUPERVISOR` monitoring access on `GET /api/v1/admin/analytics/**` and read views, and parse `cors.allowed-origins` into strict origin/method/header allow-lists (rejecting `*`)
- [x] T008 [P] Implement `backend/src/main/java/com/studentprep/config/ProductionSecurityValidator.java` (`@Profile("prod")` startup guard) failing fast if `JWT_SECRET` is < 32 UTF-8 bytes or equals the dev example key, `cors.allowed-origins` contains `*`, `S3_ACCESS_KEY`/`S3_SECRET_KEY` equal `minioadmin`, or `ADMIN_BOOTSTRAP_SECRET` is missing/weak when bootstrap is required

**Checkpoint**: Database hardened to `V18`, Argon2id `PasswordEncoder` active, `401` entry point & RBAC guards enforced, and prod fail-fast checks wired.

---

## Phase 3: User Story 1 — Irreversible Credential Storage & Student Lifecycle (Priority: P1) 🎯 MVP

**Goal**: Store and synchronize student and admin credentials strictly as irreversible hashes across Postgres and Outbox events, enforce Q1 Option A (one-time server-generated random PIN returned once on create/bulk-import/reset + immediate printable slip), enforce Q3 Option B (configurable hashed `ADMIN-001` bootstrap + invalidation of legacy `'12345'` student accounts), and eliminate all plaintext PIN exposure across directory, exports, slips, logs, and UI.

**Independent Test**: Register a student, bulk-import a cohort, export roster CSV, reset a PIN, delete a student, download `/slip`, inspect `users`/`students`/`event_publication`, verify immediate invalidation of old PINs/deleted accounts on next login, and confirm `ADMIN-001` bootstrap rejects weak prod defaults.

### Tests for User Story 1

- [x] T009 [P] [US1] Create `@SpringBootTest` integration suite `backend/src/test/java/com/studentprep/student/StudentIdentityLifecycleIntegrationTest.java` verifying sequential `V2`+`V16`+`V18` Flyway migration + `AdminBootstrapRunner`, Argon2id `pin_hash` storage, `V18` invalidation of `'12345'` (`403` `PIN_RESET_REQUIRED`), `User.id == Student.id`, immediate `401` failure after PIN reset (old PIN) and student deletion (zero outbox race window), zero `pin`/`pinHash` fields in `GET /api/v1/admin/students` and `/{id}`, redacted `/slip` PDF, zero plaintext PIN in `event_publication`, configurable `ADMIN-001` bootstrap, and `403` when `ROLE_SUPERVISOR` calls student mutations or `/export`
- [x] T010 [P] [US1] Expand `backend/src/test/java/com/studentprep/student/CsvStudentImporterTest.java` verifying `processBulkCsv` returns one-time generated `oneTimePin` values once without saving plaintext, rejects invalid rows deterministically into `errors`, omits `Login PIN` column from `exportStudentsCsv()`, and neutralizes CSV formula injection (`=`, `+`, `-`, `@`, `\t`, `\r`)

### Implementation for User Story 1

- [x] T011 [P] [US1] Update `backend/src/main/java/com/studentprep/student/Student.java` and `backend/src/main/java/com/studentprep/student/User.java` to map `@Column(name = "pin_hash", nullable = false)` (`@JsonIgnore` on `Student.pinHash`), add `@Column(name = "pin_reset_required", nullable = false) private boolean pinResetRequired` (default `false`), and replace `User extends BaseEntity` with assigned `@Id` + `Persistable<UUID>` + `createdAt`/`updatedAt` so `User.id == Student.id` stays exact
- [x] T012 [P] [US1] Update Modulith event records `StudentCreatedEvent.java`, `StudentUpdatedEvent.java`, `StudentPinResetEvent.java`, and `StudentDeletedEvent.java` under `backend/src/main/java/com/studentprep/student/` to carry `pinHash`, `pinResetRequired`, and `registrationNumber` only (removing all plaintext PIN fields)
- [x] T013 [US1] Update `backend/src/main/java/com/studentprep/student/StudentManagementService.java` to remove manual `student.setId(...)`, share a single `SecureRandom` for 5-digit PIN and registration-number generation (`JAMB-2026-XXXXXX`), hash PINs synchronously via `PasswordEncoder` before save, clear `pinResetRequired = false` on create/reset, publish sanitized events, and return one-time plaintext PINs synchronously (`depends on T011, T012`)
- [x] T014 [US1] Update `backend/src/main/java/com/studentprep/student/CsvStudentImporter.java` to emit `oneTimePin` once inside bulk upload results (`{ "name", "registrationNumber", "oneTimePin" }`), remove duplicate `Random` registration-number generator, omit `Login PIN` from `exportStudentsCsv()`, and prefix CSV cells starting with `=`, `+`, `-`, `@`, `\t`, or `\r` with `'` (`depends on T013`)
- [x] T015 [P] [US1] Update `backend/src/main/java/com/studentprep/student/RegistrationSlipGenerator.java` so `getRegistrationSlipPdf(UUID)` renders redacted credential status (`Login PIN: Issued separately (hashed) — use Admin PIN Reset to issue a new one-time PIN`) instead of reading stored hashes
- [x] T016 [P] [US1] Update `backend/src/main/java/com/studentprep/security/UserSyncListener.java` (idempotent `findById`/`findByIdentifierIgnoreCase` upsert preserving `event.studentId()`, safe deletion by ID + `registrationNumber`, Redis `SessionRedisKeys.sessionKey(...)` & `SessionRedisKeys.examUnlockKey(...)` eviction on PIN reset and delete) and `backend/src/main/java/com/studentprep/security/CustomUserDetailsService.java` (enforce live `Student` existence & `pinResetRequired` state on `ROLE_STUDENT` lookups) (`depends on T006, T011, T012`)
- [x] T017 [US1] Add Bean-Validated `CreateStudentRequest` & `UpdateStudentRequest` DTOs under `backend/src/main/java/com/studentprep/student/dto/` (`@NotBlank name`, optional `examCenter`, `Set<UUID> subjectIds`, never binding raw `Student` entity fields) and refactor `backend/src/main/java/com/studentprep/student/StudentAdminController.java` endpoints (`POST /students`, `PUT /students/{id}`, `POST /students/bulk`, `POST /students/{id}/reset-pin`, `GET /students`, `GET /students/{id}`, `GET /students/export`, `GET /students/{id}/slip`, including read-only `hasActiveSession` via `SessionRedisKeys`) with explicit `@PreAuthorize("hasRole('ADMIN')")` on mutations/exports/slips (`depends on T006, T013, T014, T015`)
- [x] T018 [P] [US1] Create `backend/src/main/java/com/studentprep/security/AdminBootstrapRunner.java` (hashing configurable `${ADMIN_BOOTSTRAP_SECRET}` into `ADMIN-001` at startup) and `backend/src/main/java/com/studentprep/student/NonProdTestFixtureSeeder.java` (`@Profile("!prod")`, non-production fixture seeding isolated from Flyway migrations) (`depends on T005, T011`)
- [x] T019 [P] [US1] Update `frontend/src/pages/admin/StudentsPage.tsx` (remove `Login PIN` plaintext column and `'12345'` fallback; display `Credential Status`; rename export button to `Export Roster (CSV)`; render one-time `credentials` table + downloadable one-time CSV/print slip in bulk modal; add single-student registration modal matching `.agents/rules/ui-standards.md`; hide mutating/export actions when decoded JWT `role !== 'ROLE_ADMIN'`) and `frontend/src/components/StudentDetailModal.tsx` (remove `student.pin` readout; add one-time PIN reveal panel + immediate `Print One-Time Slip` view on `Regenerate PIN`; gate `Reset PIN`/`Delete` to `ROLE_ADMIN`, styled in `bg-slate-50`/`#008751`) (`depends on T017`)

**Checkpoint**: User Story 1 passes `StudentIdentityLifecycleIntegrationTest` and `CsvStudentImporterTest`; zero plaintext credentials remain in DB, Outbox, exports, slips, or UI.

---

## Phase 4: User Story 2 — Seamless Session Renewal, Logout Integrity & Active-Exam Recovery (Priority: P1)

**Goal**: Enable silent `401` access token refresh, enforce `token_type` isolation, verify `jti` ownership before deleting Redis sessions on `/logout`, and implement Q2 Option A (`IN_PROGRESS` exam auto-resumes on the same bound workstation while cross-workstation sign-ins fail with `409 Conflict` until `ROLE_ADMIN` calls `POST /api/v1/admin/students/{id}/unlock-session`).

**Independent Test**: Let an access JWT expire during work and verify silent renewal succeeds; call `/logout` from an evicted client and verify active session survives; test same-device cookie reload vs second-device `409` during `IN_PROGRESS` exam followed by admin `/unlock-session` takeover.

### Tests for User Story 2

- [x] T020 [P] [US2] Create `@SpringBootTest` integration suite `backend/src/test/java/com/studentprep/security/AuthSessionLifecycleIntegrationTest.java` verifying expired access JWT returns `401` ProblemDetail (not `403`), refresh JWT rejected on protected routes & access JWT rejected on `/auth/refresh` (`401`), evicted `/auth/logout` leaves Redis `session:<ID>` intact while owning `/auth/logout` removes it, `IN_PROGRESS` exam allows same-device refresh-cookie login with `jti` rotation, second device receives `409`, `POST /api/v1/admin/students/{id}/unlock-session` clears lock + records `audit_logs` + permits single replacement login, and `pinResetRequired` returns `403` (`PIN_RESET_REQUIRED`)
- [x] T021 [P] [US2] Expand `backend/src/test/java/com/studentprep/security/JwtAuthenticationFilterTest.java` to cover `token_type = "access"` enforcement (rejecting refresh tokens on API filter), canonical Redis key matching, and RFC 7807 `401` JSON body containing `code: "SESSION_EVICTED"` and `detail: "Session active on another device"`

### Implementation for User Story 2

- [x] T022 [US2] Update `backend/src/main/java/com/studentprep/security/JwtUtil.java` to include `token_type` (`"access"` vs `"refresh"`), add `isAccessTokenValid`/`isRefreshTokenValid` helpers, and verify HMAC signatures before reading expired claims in `extractClaimsAllowExpired`
- [x] T023 [US2] Update `backend/src/main/java/com/studentprep/security/JwtAuthenticationFilter.java` to validate `token_type = "access"`, resolve `SessionRedisKeys.sessionKey(identifier)`, and write RFC 7807 `401 Unauthorized` JSON (`code: "SESSION_EVICTED"`) when Redis `jti` differs (`depends on T006, T022`)
- [x] T024 [US2] Update `backend/src/main/java/com/studentprep/security/AuthController.java` to reject `pinResetRequired` (`403` `PIN_RESET_REQUIRED`), evaluate `isSameDevice` (verified refresh cookie matching Redis `jti`) & consume single-use `SessionRedisKeys.examUnlockKey(identifier)` BEFORE checking `hasActiveSession` (`409`), verify caller `jti` (via bearer header or `refreshToken` cookie) before deleting Redis key in `/logout`, enforce `token_type = "refresh"` in `/refresh`, wrap `authenticationManager.authenticate(...)` in `try/catch (AuthenticationException)` for rate-limit accounting, and emit `HttpOnly`, configurable `Secure`, `SameSite`, `Path=/api/v1/auth` cookies (`depends on T006, T022, T023`)
- [x] T025 [P] [US2] Implement `backend/src/main/java/com/studentprep/security/AdminSessionController.java` (`@PreAuthorize("hasRole('ADMIN')")` `POST /api/v1/admin/students/{id}/unlock-session`) using `StudentInternalAPI` and `SessionRedisKeys` (preserving acyclic Modulith `security -> student` dependencies) to delete Redis `session:<UPPERCASE_ID>` and store 15-minute `exam_unlock:<UPPERCASE_ID>` permit (`depends on T006, T017, T024`)
- [x] T026 [P] [US2] Update `frontend/src/App.tsx` (handle `401` `SESSION_EVICTED` via `detail`/`code` check without calling `/api/v1/auth/logout`, trigger silent `/api/v1/auth/refresh` cleanly on access token expiry, stop calling `/logout` on failed refresh) and `frontend/src/pages/AdminLogin.tsx` (read `error.response?.data?.detail || error.response?.data?.message`)
- [x] T027 [US2] Add active workstation session indicator and `ROLE_ADMIN`-gated `Unlock Session` action (`POST /api/v1/admin/students/{id}/unlock-session`) to `frontend/src/pages/admin/StudentsPage.tsx` and `frontend/src/components/StudentDetailModal.tsx` (`depends on T019, T025`)

**Checkpoint**: User Story 2 passes `AuthSessionLifecycleIntegrationTest` and `JwtAuthenticationFilterTest`; silent refresh, safe logout, and Q2 exam resume/unlock work end-to-end.

---

## Phase 5: User Story 3 — Brute-Force Protection on Sign-In & Renewal (Priority: P1)

**Goal**: Protect `/api/v1/auth/login` and `/api/v1/auth/refresh` against 5-digit PIN brute-force guessing using Redis-backed (plus bounded local fallback) per-identifier failure lockouts and lab-NAT-safe per-IP rate ceilings without interrupting legitimate campus cohorts.

**Independent Test**: Exceed failed login threshold for one candidate and assert `429 Too Many Requests` + `Retry-After` while other distinct candidates behind the same IP still log in cleanly; exceed `/refresh` burst threshold and verify `429`.

### Tests for User Story 3

- [x] T028 [P] [US3] Create `@SpringBootTest` integration suite `backend/src/test/java/com/studentprep/security/AuthRateLimitingIntegrationTest.java` verifying 6th invalid login attempt for an identifier returns `429` (`Retry-After` present), successful login resets failure counter, distinct accounts from same IP succeed within NAT ceiling, `/auth/refresh` burst returns `429`, and `/api/v1/exams/active/sync` cap continues returning `429`

### Implementation for User Story 3

- [x] T029 [US3] Refactor `backend/src/main/java/com/studentprep/security/RateLimitInterceptor.java` using `SessionRedisKeys` to handle unauthenticated `/api/v1/auth/login` (per-IP ceiling `60`/min; pre-check per-identifier failure lock `5`/15m) and `/api/v1/auth/refresh` (`30`/min per IP) with `429` RFC 7807 ProblemDetails, `Retry-After` header, bounded local fallback if Redis degrades, and sync rate-limit preservation; expose `recordLoginFailure(identifier)` / `clearLoginFailures(identifier)` helpers invoked from `AuthController.login`'s `try/catch (AuthenticationException)` block and success path (`depends on T006, T024`)
- [x] T030 [US3] Register `/api/v1/auth/login` and `/api/v1/auth/refresh` alongside `/api/v1/exams/active/sync` in `backend/src/main/java/com/studentprep/config/WebMvcConfig.java` (`depends on T029`)

**Checkpoint**: User Story 3 passes `AuthRateLimitingIntegrationTest` cleanly; brute-force PIN enumeration is blocked under 0.01% of keyspace.

---

## Phase 6: User Story 4 — Upload Validation, Internal Service Auth & Secret Confidentiality (Priority: P2)

**Goal**: Block unsafe active uploads (SVG/HTML/JS) to MinIO/S3, require `X-API-Key` + bounded streaming + deterministic temp cleanup on `docling-parser`, pass Gemini API keys in headers with sanitized persisted error messages, and verify explicit CORS configuration.

**Independent Test**: Submit SVG/HTML/oversized image to `/api/v1/admin/ingestion/assets` and verify `400`/`415` without S3 writes; call `docling-parser` `/parse` without `X-API-Key`, without filename, and oversized PDF; simulate Gemini error and inspect `ingestion_jobs.error_message` for zero key leakage; test unauthorized CORS Origin.

### Tests for User Story 4

- [x] T031 [P] [US4] Create `@SpringBootTest` / MockMvc suite `backend/src/test/java/com/studentprep/ingestion/IngestionSecurityTest.java` verifying `/api/v1/admin/ingestion/assets` rejects SVG, HTML, spoofed PNG-extension scripts, and `>5MB` uploads with `400`/`415` ProblemDetails (zero S3 calls), accepts valid PNG/JPEG/GIF/WebP under UUID keys, verifies `LlmStructuringService` uses `x-goog-api-key` header (zero `?key=` query param), asserts `AsyncIngestionWorker` redacts API keys from `ingestion_jobs.error_message`, and verifies CORS preflight rejects unconfigured origins
- [x] T032 [P] [US4] Create `services/docling-parser/test_main.py` pytest suite verifying `401` on missing/invalid `X-API-Key`, `503` if `DOCLING_API_KEY` env var is unset, `400` on `filename=None` or non-`%PDF-` payload, `413` above `10MB`, temp-file unlink on parser failure, generic `500` without traceback exposure, and `GET /health` `200 OK`

### Implementation for User Story 4

- [x] T033 [P] [US4] Create `backend/src/main/java/com/studentprep/ingestion/ErrorSanitizer.java` stripping API keys, `?key=...`, `key=...`, `x-goog-api-key`, `x-api-key`, and `Bearer ...` tokens, and update `backend/src/main/java/com/studentprep/ingestion/LlmStructuringService.java` (pass `x-goog-api-key` header instead of `?key=` query param; replace `System.out` with SLF4J logger) and `backend/src/main/java/com/studentprep/ingestion/AsyncIngestionWorker.java` (sanitize `job.setErrorMessage(...)` via `ErrorSanitizer`; remove `printStackTrace`)
- [x] T034 [P] [US4] Harden `backend/src/main/java/com/studentprep/ingestion/S3Service.java` (validate non-empty, `<=5MB`, MIME + binary magic bytes for PNG/JPEG/GIF/WebP, block SVG/HTML/JS, UUID key naming, configured `s3.public-base-url`) and `backend/src/main/java/com/studentprep/ingestion/IngestionController.java` (close `InputStream` cleanly, throw RFC 7807 validation errors instead of wrapping in `500`, annotate `@PreAuthorize("hasRole('ADMIN')")`, sanitize error messages)
- [x] T035 [P] [US4] Update `backend/src/main/java/com/studentprep/ingestion/DoclingService.java` to inject `@Value("${docling.parser.url}")` & `@Value("${docling.api-key}")` and send `X-API-Key`, and pass `DOCLING_API_KEY` environment variable in `docker-compose.yml`
- [x] T036 [P] [US4] Harden `services/docling-parser/main.py` (`X-API-Key` `hmac.compare_digest` check returning `401`, fail-closed `503` if `DOCLING_API_KEY` unset, `None` filename guard, chunked streaming `10MB` cap -> `413`, `%PDF-` magic header check -> `400`, `finally:` `doc.close()` & `os.unlink`, sanitized `500`, `GET /health`), create `services/docling-parser/requirements.txt`, and update `services/docling-parser/Dockerfile`

**Checkpoint**: User Story 4 passes `IngestionSecurityTest` and `services/docling-parser` `pytest`; zero public XSS uploads, unauthenticated sidecar access, or leaked API keys remain.

---

## Phase 7: Polish, End-to-End Verification & Code Quality Gate

**Purpose**: Validate integrated Chromium/WebKit user journeys, enforce coding/UI standards, and confirm clean git/build gates before moving to Slice 002.

- [x] T037 [P] Add unmocked Playwright E2E security & identity suite `frontend/tests/auth-hardening.spec.ts` covering admin login, bulk CSV onboarding one-time PIN display + hidden directory PINs, PIN reset one-time slip + immediate old-PIN rejection, expired access JWT silent renewal mid-session, same-device `IN_PROGRESS` exam resume vs second-context `409` block + admin `/unlock-session` takeover, and rate-limit `429` lockout on Chromium and WebKit
- [x] T038 [P] Audit all modified Java files for zero inline FQNs and constructor injection per `.agents/rules/backend-standards.md`, and verify modified React views adhere to `.agents/rules/ui-standards.md` (Inter typography, `#008751`, `bg-slate-50`, `-ml-2` red required asterisks)
- [x] T039 Run full verification gate from `quickstart.md`: backend `mvn test` (including `ModulithArchitectureTest`, `StudentIdentityLifecycleIntegrationTest`, `AuthSessionLifecycleIntegrationTest`, `AuthRateLimitingIntegrationTest`, `IngestionSecurityTest`), `pytest` in `services/docling-parser`, frontend `npm run lint`, `npm run build`, unmocked `npx playwright test`, and `git ls-files` clean artifact check

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: Starts immediately (`T001` sequential for git cleanup; `T002`–`T003` parallel).
- **Phase 2 (Foundational)**: Depends on Phase 1 (`T003` config properties); **BLOCKS** all user stories (`T004`–`T008`).
- **Phases 3–6 (User Stories `US1`–`US4`)**: All depend on Phase 2 completion:
  - `US1` (P1) and `US3` (P1) have disjoint file scopes (`T029` touches `RateLimitInterceptor` & `AuthController.login`, so if running `US2` (`T024`) and `US3` (`T029`) sequentially/coordinating `AuthController.java`, run `T024` before `T029`!).
  - `US4` (P2) (`ingestion/*` & `services/docling-parser/*`) has zero file overlap with `US1`, `US2`, or `US3` and can execute fully in parallel after Phase 2!
  - `T025` (`AdminSessionController.java` `unlock-session`) stays isolated in `com.studentprep.security` after `T017`/`T024`; `T027` (admin unlock UI) updates `StudentsPage.tsx` and `StudentDetailModal.tsx` after `T019` and `T025` complete.
- **Phase 7 (Polish)**: Depends on `US1`–`US4` completion (`T037`, `T038` parallel before `T039` gate).

### Parallel Execution Example (Safe Non-Overlapping Waves After Phase 2)

```text
Wave A (immediately after Phase 2):
  - Tests: T009, T010, T020, T021, T028, T031, T032 [all separate test files]
  - US4 Backend/Sidecar: T033, T034, T035, T036 [disjoint ingestion & Python files]
  - US1 Models/Events/Slip: T011, T012, T015
  - US2 JWT Core & Frontend App: T022, T026

Wave B (after Wave A models/events/JWT):
  - T013 -> T014 -> T017 -> T019 (Student service -> CSV -> Controller -> Admin UI)
  - T016 (UserSyncListener & CustomUserDetailsService)
  - T018 (AdminBootstrapRunner & NonProdTestFixtureSeeder)
  - T023 -> T024 -> T025 -> T027 (JwtFilter -> AuthController -> Unlock endpoint -> Unlock UI)
  - T029 -> T030 (RateLimitInterceptor -> WebMvcConfig, coordinating AuthController.login failure hook after T024)

Wave C (Verification):
  - T037, T038 -> T039
```

## Implementation Strategy

- **MVP Increment (`Setup + Foundational + US1 + US2 + US3`)**: Eliminates all plaintext credentials, fixes broken identity sync, enforces Q1/Q2/Q3 policy decisions cleanly, restores silent JWT refresh, and stops brute-force PIN attacks.
- **Hardening Increment (`US4`)**: Closes public upload XSS, unauthenticated Docling OCR exposure, and Gemini key persistence leaks before final Chromium/WebKit release gate (`Phase 7`).
