# Implementation Plan: Authentication, Identity Integrity & Secrets Hardening (Slice 001)

**Branch**: `deployment` (Spec dir: `001-auth-identity-hardening`) | **Date**: 2026-11-14 | **Spec**: [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/spec.md)

**Input**: Feature specification from [specs/001-auth-identity-hardening/spec.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/spec.md)

## Summary

Harden StudentPrep authentication, credential lifecycle synchronization, brute-force defenses, session recovery, internal service boundaries, and repository hygiene for production deployment:
1. Replace `NoOpPasswordEncoder` with Argon2id-first adaptive hashing (`PinHashingPasswordEncoder`), rename credential columns back to `pin_hash` via Flyway `V18__Harden_Credentials_And_Pin_Reset.sql`, neutralize legacy `'12345'` accounts (`pin_reset_required = true`), enforce configurable production `ADMIN-001` bootstrapping, and remove all plaintext PIN exposures across student directories, exports (with CSV formula sanitization), registration slips, and Modulith outbox events (`pinHash` only).
2. Implement Clarification **Q1 Option A** (one-time random 5-digit PIN returned once on create, bulk CSV import, and PIN reset, with immediate printable slip UI), **Q2 Option A** (same-device `IN_PROGRESS` exam auto-resume via verified refresh cookie `jti` match, blocking cross-workstation sign-ins with `409 Conflict` until `ROLE_ADMIN` executes `POST /api/v1/admin/students/{id}/unlock-session`), and **Q3 Option B** (configurable `ADMIN_BOOTSTRAP_SECRET` + legacy `'12345'` forced reset + non-prod fixtures isolated outside migrations).
3. Enforce RFC 7807 `401 Unauthorized` `AuthenticationEntryPoint`, strict `access` vs `refresh` `token_type` claims, `jti`-verified `/auth/logout`, Redis/NAT-safe `/auth/login` & `/auth/refresh` rate limiting, `ROLE_ADMIN` vs read-only `ROLE_SUPERVISOR` authorization, explicit `cors.allowed-origins`, environment-configured cookies (`Secure`, `SameSite=Strict`, `Path=/api/v1/auth`), production fail-fast validators, safe image magic-byte validation on S3 uploads, Gemini `x-goog-api-key` header + error redaction, `X-API-Key` + bounded streaming on `services/docling-parser/main.py`, and cleanup of tracked debug/dev artifacts.

## Technical Context

**Language/Version**: Java 17 (`backend/pom.xml`), TypeScript + React 18/19 (`frontend/package.json`), Python 3.11 (`services/docling-parser/Dockerfile`)
**Primary Dependencies**: Spring Boot 3.2.4, Spring Modulith 1.1.3, Spring Security + `Argon2PasswordEncoder` (`bcpkix-jdk18on` 1.78), JJWT 0.12.5, Spring Data JPA / PostgreSQL 16 + Flyway 10.10.0, Spring Data Redis, AWS S3 SDK 2.25.11 (MinIO), OpenPDF 1.3.32, Axios, React Router, Tailwind CSS + shadcn/ui, FastAPI + PyMuPDF
**Storage**: PostgreSQL 16 (`users`, `students`, `event_publication`, `audit_logs`, `ingestion_jobs`), Redis 7 (`session:*`, `exam_unlock:*`, `rate_limit:*`), MinIO/S3 (`studentprep-assets`)
**Testing**: JUnit 5 + `@SpringBootTest` + MockMvc + `ModulithArchitectureTest` (`mvn test`), Python `pytest` + `httpx` TestClient, `npm run lint` + `npm run build` + Playwright E2E (Chromium & WebKit)
**Target Platform**: Docker Compose (Linux containers behind reverse proxy in production; Windows/Linux dev workstations)
**Project Type**: Full-stack modular monolith web application + Python FastAPI OCR sidecar
**Performance Goals**: Auth & session validation `<150ms` p95; Argon2id verification tuned for concurrency (`m=16384, t=2, p=1`); fail-safe Redis degradation without dropping active exams
**Constraints**: Zero plaintext PIN/secret persistence (`users`, `students`, `event_publication`, `ingestion_jobs`, CSV, PDF, logs); `validate-on-migrate: true`; non-breaking API envelopes (`{ data, meta }` + RFC 7807 `ProblemDetail`)
**Scale/Scope**: Exam-center cohorts (hundreds to thousands of concurrent candidates per NAT router IP) across `security`, `student`, `ingestion`, `config`, `common`, `frontend`, and `services/docling-parser`

## Constitution Check

*GATE: Passed both before Phase 0 design and after Phase 1 contracts & data model.*

| Principle | Pre-Plan Status | Post-Design Verification & Mechanisms |
|-----------|-----------------|---------------------------------------|
| **I. Modular Monolith & Outbox Integrity** | PASS | Keeps `security`, `student`, `ingestion`, `exam`, `questionbank`, `analytics` boundaries intact; fixes `User` ID assignment (`Persistable`) so `UserSyncListener` syncs reliably via Outbox (`pinHash` only, zero plaintext in `event_publication`); `ModulithArchitectureTest` required in test gate. |
| **II. Zero-Trust Security, Identity & Secrets** | PASS | Eliminates `NoOpPasswordEncoder` and `V16` plaintext state via `V18`, adds `401` `AuthenticationEntryPoint`, `token_type` claim separation, `jti`-checked `/logout`, `/auth/*` rate limiting, `ROLE_ADMIN` vs read-only `ROLE_SUPERVISOR` enforcement, explicit CORS allow-list, env cookie flags, upload MIME/magic checks, `docling-parser` `X-API-Key`, Gemini header + `ErrorSanitizer`, and repo cleanup. |
| **III. Exam, Snapshot & Grading Integrity** | PASS | Does not weaken exam freeze/timer rules; strengthens exam integrity by preventing unauthorized cross-workstation takeover during `IN_PROGRESS` exams without `ROLE_ADMIN` unlock (`Q2 = Option A`). |
| **IV. Shared-Kiosk & Offline Resilience** | PASS | Fixes `App.tsx` silent refresh on `401` and stops background `/logout` during session eviction; full IndexedDB cross-user wipe & Workbox PWA offline precache scoped to Slice 003. |
| **V. Contract-First API & Test-First Verification** | PASS | Updates `docs/api_contract.md` and `specs/001-auth-identity-hardening/contracts/` alongside plan; requires integration `@SpringBootTest`, Python `pytest`, and unmocked Chromium/WebKit Playwright coverage. |
| **VI. Code Quality, Schema & UI Discipline** | PASS | Explicit imports & constructor injection per `.agents/rules/backend-standards.md`; idempotent Flyway `V18` (`baseline-on-migrate` disabled in prod); CSV formula sanitization; React UI adheres to `.agents/rules/ui-standards.md` (`#008751`, Inter, `bg-slate-50`, `-ml-2` asterisks). |

## Project Structure

### Documentation (this feature)

```text
specs/001-auth-identity-hardening/
├── plan.md                                 # This file
├── research.md                             # Phase 0 architectural & security decisions
├── data-model.md                           # Entities, Outbox events, Redis keys & JWT claims
├── quickstart.md                           # Environment setup & verification checklist
├── checklists/
│   └── requirements.md                     # Validated specification checklist
└── contracts/
    ├── auth-and-student-api.md             # Auth, Session Unlock & Student Admin contracts
    └── ingestion-and-docling-api.md        # S3 asset validation & Docling sidecar contracts
```

### Source Code (affected repository paths)

```text
.env.example
.gitignore
docker-compose.yml
docs/api_contract.md                        # Updated contract v1 (auth, students, assets)

backend/
├── src/main/java/com/studentprep/
│   ├── common/
│   │   ├── GlobalExceptionHandler.java     # RFC 7807 ProblemDetails & validation/auth mapping
│   │   └── SessionRedisKeys.java           # Canonical Redis keys shared across modules (no cycle)
│   ├── config/
│   │   ├── ProductionSecurityValidator.java # New @Profile("prod") fail-fast secret/CORS guard
│   │   └── WebMvcConfig.java               # Auth & sync rate-limit interceptor registration
│   ├── security/
│   │   ├── AdminBootstrapRunner.java       # Configurable hashed ADMIN-001 bootstrapper
│   │   ├── AdminSessionController.java     # ROLE_ADMIN POST /students/{id}/unlock-session endpoint
│   │   ├── AuthController.java             # Q2 same-device/unlock login, jti logout, cookie flags
│   │   ├── CustomUserDetailsService.java   # Authoritative Student existence & pinReset check
│   │   ├── JwtAuthenticationFilter.java    # token_type="access", Redis jti & RFC 7807 401
│   │   ├── JwtUtil.java                    # token_type claim, signature-verified expired claims
│   │   ├── PinHashingPasswordEncoder.java  # Argon2id primary + BCrypt verify, zero plaintext
│   │   ├── RateLimitInterceptor.java       # Login (id+IP), refresh, and sync throttling
│   │   ├── SecurityConfig.java             # 401 EntryPoint, 403 Handler, RBAC, CORS allow-list
│   │   └── UserSyncListener.java           # Idempotent Outbox sync (User.id==Student.id + Redis)
│   ├── student/
│   │   ├── CsvStudentImporter.java         # One-time PIN bulk response + CSV formula sanitization
│   │   ├── NonProdTestFixtureSeeder.java   # @Profile("!prod") opt-in dev/E2E seeder (if configured)
│   │   ├── RegistrationSlipGenerator.java  # Redacts stored PIN on GET /slip
│   │   ├── Student.java                    # pin_hash column, pinResetRequired, @JsonIgnore pinHash
│   │   ├── StudentAdminController.java     # Validated DTOs, Q1 one-time PIN responses, hasActiveSession read
│   │   ├── StudentCreatedEvent.java        # Carries pinHash + pinResetRequired only
│   │   ├── StudentDeletedEvent.java        # Carries studentId + registrationNumber
│   │   ├── StudentManagementService.java   # Synchronous Argon2id hashing, SecureRandom, session evict
│   │   ├── StudentPinResetEvent.java       # Carries pinHash only + Redis eviction trigger
│   │   ├── StudentUpdatedEvent.java        # Carries registrationNumber only
│   │   ├── User.java                       # Persistable<UUID> manual ID, pin_hash, pinResetRequired
│   │   └── dto/                            # CreateStudentRequest & UpdateStudentRequest (Bean Validation)
│   └── ingestion/
│       ├── AsyncIngestionWorker.java       # Uses ErrorSanitizer before saving errorMessage
│       ├── DoclingService.java             # Configured URL + X-API-Key header
│       ├── ErrorSanitizer.java             # Redacts API keys / tokens / URL secrets
│       ├── IngestionController.java        # Clean stream handling + RFC 7807 validation errors
│       ├── LlmStructuringService.java      # Sends x-goog-api-key header instead of ?key= URI
│       └── S3Service.java                  # Raster image MIME+magic-byte & size checks + public URL
├── src/main/resources/
│   ├── application.yml                     # Secure defaults (COOKIE_SECURE, FLYWAY_BASELINE, rate-limit)
│   └── db/migration/
│       └── V18__Harden_Credentials_And_Pin_Reset.sql
└── src/test/
    ├── resources/application.yml           # validate-on-migrate: true, non-prod cookie/bootstrap flags
    └── java/com/studentprep/...            # Expanded integration & unit verification suites

frontend/
├── .gitignore                              # Ignores dev-dist, playwright-report, test-results
├── src/
│   ├── App.tsx                             # 401 silent refresh, no spurious /logout on eviction
│   ├── components/
│   │   └── StudentDetailModal.tsx          # Credential status, Reset One-Time PIN + Print Slip, Unlock Session
│   └── pages/
│       ├── AdminLogin.tsx                  # Displays ProblemDetail detail/error cleanly
│       └── admin/
│           └── StudentsPage.tsx            # Roster without PINs, Create Student + Bulk One-Time Slips, Unlock
└── tests/
    └── auth-hardening.spec.ts              # Unmocked Chromium/WebKit login, refresh, lockout & UI specs

services/docling-parser/
├── Dockerfile                              # Uses requirements.txt
├── main.py                                 # X-API-Key auth, streaming 10MB cap, None filename guard, cleanup
├── requirements.txt                        # Pinned Python runtime dependencies
└── test_main.py                            # Pytest suite for auth, limits, cleanup, and /health

[REMOVED FROM GIT]:
├── BcryptGen.java, HashGen.java, HashGenTest.java, fix.sql, parsed_pdf.json, test00.pdf
├── frontend/src/pages/admin/update.cjs, frontend/test-circ.js, test-katex.js, test-sqrt.js
└── frontend/dev-dist/*, frontend/playwright-report/*, frontend/test-results/*
```

**Structure Decision**: Retain the existing Spring Modulith backend (`backend/`), Vite React frontend (`frontend/`), and Python FastAPI sidecar (`services/docling-parser/`) layout without introducing extra microservices or repositories. Keep `V16__Revert_To_Plain_Text_Pins.sql` preserved in the Flyway history chain so checksums remain valid on upgraded instances while `V18__Harden_Credentials_And_Pin_Reset.sql` and `AdminBootstrapRunner` enforce strict cryptographic credentials before serving any traffic.

## Complexity Tracking

No Constitution violations requiring waivers.
