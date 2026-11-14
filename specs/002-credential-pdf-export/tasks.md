# Tasks: Printable Student Credential PDF Export (Slice 002)

**Input**: Design documents from `specs/002-credential-pdf-export/`
**Prerequisites**: [plan.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/plan.md), [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/spec.md), [research.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/research.md), [data-model.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/data-model.md), [contracts/credential-pdf-api.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/contracts/credential-pdf-api.md), [quickstart.md](file:///c:/Users/User/Desktop/studentprep/specs/002-credential-pdf-export/quickstart.md)

**Tests**: Unit, integration (`@SpringBootTest` + Flyway/Postgres/Redis), `ModulithArchitectureTest`, `npm run lint && npm run build`, and unmocked Playwright E2E tests are included per Constitution Principle V.

**Organization**: Tasks are ordered by dependency phase so parallel workers (`[P]`) own disjoint files across configuration/security, student persistence/crypto, PDF generation/controllers, frontend UI, and tests.

---

## Phase 1: Setup & Configuration Contracts

**Purpose**: Document environment variables, configuration bindings, and API contract updates before runtime validation and endpoint rollout.

- [x] T001 [P] Document `CREDENTIAL_ENCRYPTION_KEY` (32+ byte secret in production; optional local dev default) in `.env.example`
- [x] T002 [P] Bind `security.credential-vault.key: ${CREDENTIAL_ENCRYPTION_KEY:local_dev_credential_vault_key_change_in_prod}` in `backend/src/main/resources/application.yml`
- [x] T003 [P] Update `docs/api_contract.md` Sections 1.3 and 2.5 for `GET /api/v1/admin/students/credentials/pdf`, `POST /api/v1/admin/students/credentials/pdf`, `ROLE_SUPERVISOR` `403` exclusion, and PIN-inclusive `GET /api/v1/admin/students/{id}/slip` per `specs/002-credential-pdf-export/contracts/credential-pdf-api.md`

---

## Phase 2: Foundational Schema, Crypto Vault & Security Guards

**Purpose**: Core database column, AES-256-GCM service, entity/repository contracts, production startup validator, and `ROLE_ADMIN` route security required before PDF service/endpoint wiring.

**⚠️ CRITICAL**: Complete before Phase 3 backend service integration. Frontend UI tasks (Phase 6) may proceed in parallel against frozen contracts in `specs/002-credential-pdf-export/contracts/credential-pdf-api.md`.

- [x] T004 [P] Create idempotent Flyway migration `backend/src/main/resources/db/migration/V19__Add_Student_Pin_Ciphertext.sql` adding `pin_ciphertext TEXT` nullable column to `students`
- [x] T005 [P] Implement `backend/src/main/java/com/studentprep/student/StudentPinCryptoService.java` inside `@ApplicationModule` `student` using JCA `AES/GCM/NoPadding`, SHA-256 derived 256-bit key from `security.credential-vault.key`, random 12-byte IV, `v1:<base64-iv>:<base64-ciphertext-plus-tag>` envelope, and explicit `IllegalStateException` on corrupt/wrong-key decryption
- [x] T006 [P] Add `@JsonIgnore @Column(name = "pin_ciphertext", columnDefinition = "TEXT") private String pinCiphertext;` to `backend/src/main/java/com/studentprep/student/Student.java` and add ordered queries (`findAllByOrderByNameAscRegistrationNumberAsc`, `findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc`) in `backend/src/main/java/com/studentprep/student/StudentRepository.java`
- [x] T007 [P] Update `backend/src/main/java/com/studentprep/config/ProductionSecurityValidator.java` to reject blank, placeholder, `< 32` UTF-8 byte, or low-entropy `security.credential-vault.key` (`CREDENTIAL_ENCRYPTION_KEY`) in `@Profile("prod")` and add unit assertions in `backend/src/test/java/com/studentprep/config/ProductionSecurityValidatorTest.java`
- [x] T008 [P] Update `backend/src/main/java/com/studentprep/security/SecurityConfig.java` to require `hasRole("ADMIN")` explicitly on `GET` & `POST /api/v1/admin/students/credentials/pdf` (ahead of `ROLE_SUPERVISOR` wildcard `/api/v1/admin/students/*` matcher) and extend `backend/src/test/java/com/studentprep/security/SecurityConfigTest.java`

**Checkpoint**: Schema, AEAD encryption service, production key validation, and security filter chain ready.

---

## Phase 3: User Story 1 — One-Click Bulk Student Credential Roster PDF (Priority: P1) 🎯 MVP

**Goal**: Enable `ROLE_ADMIN` to download one consolidated paginated PDF of all registered students (`Name`, `Registration Number`, `Login PIN`) or a newly imported CSV cohort batch without rotating active PINs on repeat downloads, while activating legacy `pinResetRequired` accounts once on first export.

**Independent Test**: `GET /api/v1/admin/students/credentials/pdf` and `POST /api/v1/admin/students/credentials/pdf` produce valid multi-page OpenPDF rosters with exact candidate names, reg numbers, and working 5-digit PINs preserved across repeat downloads.

### Implementation for User Story 1

- [x] T009 [US1] Create `backend/src/main/java/com/studentprep/student/dto/BatchCredentialPdfRequest.java` record with `@NotEmpty @Size(min = 1, max = 5000) List<@NotBlank String> registrationNumbers` (depends on T006)
- [x] T010 [US1] Update `backend/src/main/java/com/studentprep/student/StudentManagementService.java` to persist `pinCiphertext` on `createStudentWithSubjects`, add transactional `ensureRecoverablePin(Student)` / `ensureRecoverablePins(List<Student>)` (decrypts existing `pinCiphertext` without mutation or provisions a fresh 5-digit PIN once for null/`pinResetRequired` accounts updating `pinHash`, `pinCiphertext`, `pinResetRequired = false`, evicting Redis keys, and publishing `StudentPinResetEvent`), and update `backend/src/main/java/com/studentprep/student/NonProdTestFixtureSeeder.java` to encrypt `devStudentPin` (`73914`) into `pinCiphertext` for `JAMB-2026-DEMO01` (depends on T005, T006)
- [x] T011 [US1] Implement `getBulkCredentialsPdf()` and `getBatchCredentialsPdf(List<String> registrationNumbers)` in `backend/src/main/java/com/studentprep/student/RegistrationSlipGenerator.java` using A4 OpenPDF `PdfPTable` with repeating headers (`setHeaderRows(1)`), `#`, `Candidate Name`, `Registration Number`, `Login PIN`, deterministic ordering, document metadata/timestamp, and clean empty-roster PDF output (depends on T009, T010)
- [x] T012 [US1] Add `GET /api/v1/admin/students/credentials/pdf` (`Cache-Control: no-store`, attachment `student_credentials_roster.pdf`) and `POST /api/v1/admin/students/credentials/pdf` (`@Valid @RequestBody BatchCredentialPdfRequest`, `Cache-Control: no-store`, attachment `batch_credentials_roster.pdf`) guarded by `@PreAuthorize("hasRole('ADMIN')")` in `backend/src/main/java/com/studentprep/student/StudentAdminController.java` (depends on T008, T011)

---

## Phase 4: User Story 2 — One-Click Single-Student Credential PDF Slip (Priority: P1)

**Goal**: Update individual student PDF slips (`GET /api/v1/admin/students/{id}/slip`) to display the candidate's active decrypted `Login PIN` alongside Name, Registration Number, and Subjects without rotating their PIN on repeat downloads.

**Independent Test**: `GET /api/v1/admin/students/{id}/slip` returns `%PDF-` containing the candidate's current working 5-digit PIN identical to the bulk PDF and keeps working across repeated downloads.

### Implementation for User Story 2

- [x] T013 [US2] Update `getRegistrationSlipPdf(UUID id)` in `backend/src/main/java/com/studentprep/student/RegistrationSlipGenerator.java` to call `ensureRecoverablePin(student)` inside a write transaction (`@Transactional`) and print `Login PIN: <pin>` on the slip instead of `REDACTED_CREDENTIAL_NOTICE`, while keeping `getRegistrationSlip(UUID id)` metadata PIN-free (`@Transactional(readOnly = true)`) (depends on T010, T011)

---

## Phase 5: User Story 3 — Lifecycle Sync, Role Protection & Backend Verification (Priority: P2)

**Goal**: Keep explicit PIN resets, deletions, `ROLE_SUPERVISOR` `403` enforcement, audit logging, and directory/CSV privacy verified via unit and live Postgres/Redis integration tests.

**Independent Test**: `mvn test` passes all unit, `ModulithArchitectureTest`, and `StudentIdentityLifecycleIntegrationTest` assertions including repeat-download PIN stability, legacy activation + `/api/v1/auth/login` sign-in, PIN reset propagation, deleted-student exclusion, `audit_logs` presence, zero PINs in CSV/`StudentResponse`, and `ROLE_SUPERVISOR` `403`.

### Implementation & Backend Tests

- [x] T014 [US3] Update `resetPin(UUID id)` in `backend/src/main/java/com/studentprep/student/StudentManagementService.java` to store updated `pinCiphertext` atomically alongside `pinHash` (`Argon2id`) and `StudentPinResetEvent` (depends on T010)
- [x] T015 [P] [US1] Add `backend/src/test/java/com/studentprep/student/StudentPinCryptoServiceTest.java` testing AES-256-GCM round-trip, unique random IVs per encryption, blank/corrupt envelope handling, and wrong-key `IllegalStateException` (depends on T005)
- [x] T016 [P] [US1] [US2] Update `backend/src/test/java/com/studentprep/student/RegistrationSlipGeneratorTest.java` for decrypted PIN rendering on single slips, zero `$argon2id$` / ciphertext exposure, PIN-free `getRegistrationSlip` metadata, multi-page paginated bulk PDFs (`> 60` candidates), legacy account auto-activation once, and empty-roster PDF output (depends on T011, T013)
- [x] T017 [P] [US3] Update `backend/src/test/java/com/studentprep/student/StudentManagementServiceAndControllerTest.java` wiring (`StudentPinCryptoService`, updated `StudentManagementService` & `RegistrationSlipGenerator`) plus controller tests for `GET` and `POST /api/v1/admin/students/credentials/pdf` (depends on T012, T014)
- [x] T018 [US1] [US2] [US3] Update `backend/src/test/java/com/studentprep/student/StudentIdentityLifecycleIntegrationTest.java` against live Postgres/Redis/Flyway `V19` verifying: active PIN printed in bulk & single PDFs, repeat downloads preserve exact PIN, legacy `pin_reset_required = true` student activates once and authenticates at `/api/v1/auth/login`, reset PIN updates both PDFs and rejects old PIN, deleted student omitted from bulk PDF, `audit_logs` records PDF downloads, `GET /api/v1/admin/students` & `/export` omit PINs, and `ROLE_SUPERVISOR` gets `403` on bulk GET/POST + single slip (depends on T012, T014)

---

## Phase 6: Frontend Admin UI & Unmocked E2E Tests

**Purpose**: Expose 1-click school-wide, CSV-batch, and single-student credential PDF download controls strictly to `ROLE_ADMIN` in React and verify end-to-end with unmocked Playwright.

- [x] T019 [P] [US1] [US2] Update `frontend/src/pages/admin/StudentsPage.tsx` (`ROLE_ADMIN` only): add `"Download All Credentials (PDF)"` button in header toolbar (`GET /api/v1/admin/students/credentials/pdf` blob -> `student_credentials_roster.pdf`), add `"Download Batch Credentials (PDF)"` button in bulk CSV import results (`POST /api/v1/admin/students/credentials/pdf` blob -> `batch_credentials_roster.pdf`), and update row `Slip` button title (`"Download credential slip PDF with active PIN"`); ensure zero credential PDF UI renders when `!isAdmin`
- [x] T020 [P] [US2] Update `frontend/src/components/StudentDetailModal.tsx`: add `ROLE_ADMIN`-only `"Download Credential PDF"` button calling `GET /api/v1/admin/students/${student.id}/slip` (`${student.registrationNumber}-slip.pdf`), and update credential status badge copy from `"Hashed (Never Readable)"` to `"Active (Credential PDF Ready)"`
- [x] T021 [US1] [US2] [US3] Update `frontend/tests/auth-hardening.spec.ts`: align `T037.2` profile badge assertion, verify single-student PDF download contains valid `%PDF-` + candidate PIN without exposing PINs in directory UI/JSON/CSV, verify 1-click **"Download All Credentials (PDF)"** & **"Download Batch Credentials (PDF)"** download valid `%PDF-` files whose printed PIN authenticates on `/` and stays unchanged on repeat download, and verify `ROLE_SUPERVISOR` sees zero bulk/single PDF controls (`403` on API calls) (depends on T012, T018, T019, T020)

---

## Phase 7: Cross-Cutting Quality Gates & Verification

**Purpose**: Verify full Maven/Modulith suite, frontend lint/build, live unmocked Playwright E2E suite, and repository hygiene.

- [x] T022 Run full backend verification suite (`cd backend && mvn test`, including `ModulithArchitectureTest`, Flyway `V19`, crypto/PDF tests, and `StudentIdentityLifecycleIntegrationTest`)
- [x] T023 Run frontend quality gates (`cd frontend && npm run lint && npm run build`)
- [x] T024 Run unmocked Playwright E2E suite (`cd frontend && npx playwright test`) against running Postgres/Redis/MinIO/Docling/Spring Boot stack and inspect `git status` for clean workspace hygiene

---

## Dependencies & Execution Order

- **Phase 1 (`T001`–`T003`)** & **Phase 2 (`T004`–`T008`)**: All tasks touch disjoint files and execute in parallel immediately.
- **Frontend UI (`T019`, `T020`)**: Touch disjoint `.tsx` files against locked REST contracts (`specs/002-credential-pdf-export/contracts/credential-pdf-api.md`) and run in parallel with Backend Phase 2 & Phase 3.
- **Phase 3 (`T009`–`T012`) & Phase 4 (`T013`) & `T014`**: Sequential inside `student` core services/controllers (`T005/T006 -> T010/T014 -> T011/T013 -> T012`), running in parallel with `T015` (`StudentPinCryptoServiceTest`).
- **Backend Tests (`T016`–`T018`) & E2E (`T021`)**: Run once `T012`–`T014` complete, followed by final verification gates (`T022`–`T024`).

### Parallel Swarm Allocation Strategy
1. **Monitor**: `monitor` (`swarm_monitor`) — idle stall watch.
2. **Worker 1 (`config-security`)**: Owns `T001`, `T002`, `T003`, `T007`, `T008`.
3. **Worker 2 (`schema-crypto`)**: Owns `T004`, `T005`, `T006`, `T009`, `T015`.
4. **Worker 3 (`frontend-ui`)**: Owns `T019`, `T020` (`StudentsPage.tsx`, `StudentDetailModal.tsx`).
5. **Worker 4 (`student-pdf`)** *(briefed once `schema-crypto` lands `T004`–`T009`)*: Owns `T010`, `T011`, `T012`, `T013`, `T014`.
6. **Verifier (`verifier-tests`)**: Verifies backend & frontend implementations, updates `T016`, `T017`, `T018`, `T021`, marks completed checkboxes in `specs/002-credential-pdf-export/tasks.md`, and runs `T022`–`T024`.
