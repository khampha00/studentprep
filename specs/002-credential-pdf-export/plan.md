# Implementation Plan: Printable Student Credential PDF Export (Slice 002)

**Branch**: `deployment` (Spec dir: `002-credential-pdf-export`) | **Date**: 2026-11-14 | **Spec**: [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/spec.md)

**Input**: Feature specification from [specs/002-credential-pdf-export/spec.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/spec.md)

## Summary

Enable single-school CBT administrators (`ROLE_ADMIN`) to download both a **one-click consolidated school credential roster PDF** (`Name`, `Registration Number`, and active `Login PIN` for all registered candidates or a newly imported CSV cohort) and **one-click single-student credential PDF slips** without rotating or invalidating existing working PINs on reprint:
1. Amend credential persistence in the `student` module (`V19__Add_Student_Pin_Ciphertext.sql` + `StudentPinCryptoService`) to store an authenticated AES-256-GCM encrypted copy (`students.pin_ciphertext`) alongside irreversible Argon2id verification hashes (`students.pin_hash` & `users.pin_hash`), keyed from dedicated environment secret `CREDENTIAL_ENCRYPTION_KEY` (independent of `JWT_SECRET`).
2. Update `StudentManagementService`, `NonProdTestFixtureSeeder`, and lazy first-export legacy provisioning so every create, bulk CSV import, explicit PIN reset, and legacy/unissued PDF export updates both `pinHash` and `pinCiphertext` atomically and keeps `users` synchronized via Spring Modulith outbox events.
3. Implement consolidated multi-page OpenPDF table rendering (`GET /api/v1/admin/students/credentials/pdf` for all students and `POST /api/v1/admin/students/credentials/pdf` for imported cohort subsets) and update `RegistrationSlipGenerator` (`GET /api/v1/admin/students/{id}/slip`) to output the candidate's active Login PIN, locked strictly to `ROLE_ADMIN` in `SecurityConfig` and audited via `AuditLogAspect`.
4. Add 1-click PDF download controls in React (`StudentsPage.tsx` toolbar + bulk CSV upload result modal + row slip actions and `StudentDetailModal.tsx`), keeping directory JSON views, CSV roster exports, and `ROLE_SUPERVISOR` read-only UI strictly free of student PINs.

## Technical Context

**Language/Version**: Java 17 (`backend/pom.xml`), TypeScript / React 19 + Vite (`frontend/package.json`)
**Primary Dependencies**: Spring Boot 3.2.4, Spring Security, Spring Modulith 1.1.3, Flyway, OpenPDF (`com.github.librepdf:openpdf:1.3.32`), JCA `AES/GCM/NoPadding`, Tailwind CSS, shadcn/ui, Axios
**Storage**: PostgreSQL 16 (`students`, `users`, `audit_logs`, `event_publication` outbox), Redis (session/unlock/rate-limit keys)
**Testing**: JUnit 5 + Mockito, `@SpringBootTest` + MockMvc live Postgres/Redis integration tests, `ModulithArchitectureTest`, `npm run lint`, `npm run build`, unmocked Playwright E2E (`frontend/tests/`)
**Target Platform**: Linux/Windows JVM backend + Dockerized Postgres/Redis/MinIO + Chromium/WebKit browser admin portal (single-school local or cloud deployment)
**Project Type**: Full-stack modular monolith + React PWA
**Performance Goals**: Bulk PDF generation for a typical school cohort (200–2,000 active students with stored `pin_ciphertext`) completes in under 3 seconds cleanly using fast symmetric AES-GCM decryption without re-running Argon2id hashing; initial lazy Argon2id provisioning runs only once for legacy/unissued accounts (`pin_reset_required = true` or missing ciphertext)
**Constraints**: Preserve strict Spring Modulith boundaries (`ModulithArchitectureTest`); comply with Constitution v1.1.0 Principle II; `ROLE_SUPERVISOR` MUST receive `403 Forbidden`; standard `GET /api/v1/admin/students` JSON and `GET /api/v1/admin/students/export` CSV MUST remain PIN-free; dedicated `CREDENTIAL_ENCRYPTION_KEY` validated on `@Profile("prod")` startup without breaking `JWT_SECRET` rotation
**Scale/Scope**: Single-school cohorts (~10 to ~5,000 students); paginated multi-page A4 PDF table output with repeating column headers

## Constitution Check

*GATE: Verified against StudentPrep Constitution v1.1.0 both pre-design and post-design.*

| Principle | Status | Design Compliance Notes |
| :--- | :--- | :--- |
| **I. Modular Monolith & Asynchronous Outbox Integrity** | **PASS** | All encryption (`StudentPinCryptoService`), schema changes (`Student`), and OpenPDF generation remain encapsulated inside `@ApplicationModule` `com.studentprep.student`. Lazy legacy PIN activation publishes existing `StudentPinResetEvent` via `ApplicationEventPublisher` to synchronize `security` (`UserSyncListener`) without crossing module repositories. |
| **II. Zero-Trust Security, Identity & Secrets Hygiene** | **PASS** | Compliant with Constitution `v1.1.0`: `pin_hash` (`Argon2id`) remains sole login verification secret; Admin/Supervisor passwords remain strictly one-way hashed; student `pin_ciphertext` uses AES-256-GCM via env-injected `CREDENTIAL_ENCRYPTION_KEY` (checked in `ProductionSecurityValidator` for `prod`); `ROLE_ADMIN`-only endpoints audited by `AuditLogAspect`; list APIs, CSV exports, supervisor views, logs, and outbox events stay completely free of PINs. |
| **III. Exam, Snapshot & Grading Integrity** | **PASS** | Unchanged exam/grading paths. Downloading credential PDFs preserves existing PINs and avoids terminating or disrupting `IN_PROGRESS` exam sessions. (Exam/Grading integrity hardening proceeds in Slice 003.) |
| **IV. Shared-Kiosk Isolation & Offline-First Resilience** | **PASS** | Admin PDF downloads use authenticated Axios `blob` downloads matching existing token-refresh interceptors; no student exam kiosk state affected. |
| **V. Contract-First API & Test-First Verification** | **PASS** | `contracts/credential-pdf-api.md` defines exact endpoints, `Content-Type: application/pdf`, filenames, RFC 7807 ProblemDetails error cases, and aligns `docs/api_contract.md`. Test plan covers crypto round-trip unit tests, multi-page OpenPDF integration tests, `ROLE_SUPERVISOR` `403` enforcement, legacy activation + sign-in verification, and unmocked Playwright E2E checks. |
| **VI. Code Quality, Schema & UI Discipline** | **PASS** | Constructor injection and explicit imports enforced in Java; idempotent Flyway `V19__Add_Student_Pin_Ciphertext.sql` without `baseline-on-migrate`; UI buttons and helper text in `StudentsPage.tsx` / `StudentDetailModal.tsx` follow Inter font, JAMB Green `#008751`, `bg-slate-50`/`bg-zinc-50`, and `text-slate-900`. |

## Project Structure

### Documentation (this feature)

```text
specs/002-credential-pdf-export/
├── spec.md                              # Approved feature requirements (v1.1.0 compliant)
├── plan.md                              # This implementation plan
├── research.md                          # Phase 0 architectural decisions & tradeoffs
├── data-model.md                        # Flyway V19 schema, AES-GCM envelope & entity updates
├── quickstart.md                        # End-to-end validation & test commands
├── checklists/
│   └── requirements.md                  # Validated spec checklist
└── contracts/
    └── credential-pdf-api.md            # Admin bulk/single credential PDF REST contracts
```

### Source Code (affected repository paths)

```text
.env.example                                                                 # Document CREDENTIAL_ENCRYPTION_KEY
docs/api_contract.md                                                         # Add bulk PDF endpoints & update /{id}/slip contract

backend/src/main/
├── resources/
│   ├── application.yml                                                      # Bind security.credential-vault.key
│   └── db/migration/
│       └── V19__Add_Student_Pin_Ciphertext.sql                              # Idempotently add students.pin_ciphertext column
└── java/com/studentprep/
    ├── config/
    │   └── ProductionSecurityValidator.java                                 # Validate non-placeholder >=32-byte CREDENTIAL_ENCRYPTION_KEY in prod
    ├── security/
    │   └── SecurityConfig.java                                              # Lock bulk GET/POST credentials PDF routes to ROLE_ADMIN
    └── student/
        ├── Student.java                                                     # Add @JsonIgnore pinCiphertext field
        ├── StudentRepository.java                                           # Ordered roster fetch queries (findAllByOrderByNameAscRegistrationNumberAsc, findByRegistrationNumberIn)
        ├── StudentPinCryptoService.java                                     # AES-256-GCM encrypt/decrypt service inside student module
        ├── StudentManagementService.java                                    # Persist pinCiphertext on create/reset + ensureRecoverablePin() for legacy rows
        ├── NonProdTestFixtureSeeder.java                                    # Populate pinCiphertext for dev demo student JAMB-2026-DEMO01
        ├── RegistrationSlipGenerator.java                                   # Render active PIN in single slip + multi-page bulk credential roster PDF
        ├── StudentAdminController.java                                      # GET & POST /api/v1/admin/students/credentials/pdf (+ updated /{id}/slip)
        └── dto/
            └── BatchCredentialPdfRequest.java                               # Validated registrationNumbers list for CSV batch PDF export

backend/src/test/java/com/studentprep/
├── config/ProductionSecurityValidatorTest.java                              # Verify prod key validation for credential vault
└── student/
    ├── StudentPinCryptoServiceTest.java                                     # AES-GCM round-trip, random IVs, key mismatch handling
    ├── RegistrationSlipGeneratorTest.java                                   # Verify single & multi-page bulk PDF contents + legacy provisioning
    └── StudentIdentityLifecycleIntegrationTest.java                         # Live Postgres/Redis E2E integration (bulk/single PDF, repeat stability, 403 supervisor, login check)

frontend/src/
├── pages/admin/StudentsPage.tsx                                             # "Download All Credentials (PDF)" button + bulk CSV "Download Batch Credentials (PDF)" + slip tooltip/label
├── components/StudentDetailModal.tsx                                        # "Download Credential PDF" button + updated help copy
└── tests/ (Playwright E2E spec update)                                      # Unmocked Chromium/WebKit verification of bulk & single PDF downloads + printed PIN login
```

**Structure Decision**: Keep all domain logic cleanly inside the existing modular monolith structure (`backend/src/main/java/com/studentprep/student` + targeted security/config wiring) and admin React frontend (`frontend/src`), avoiding new subprojects or cross-module repository coupling.

## Complexity Tracking

No Constitution violations or exceptions remaining after the ratified `v1.1.0` Constitution update to Principle II.
