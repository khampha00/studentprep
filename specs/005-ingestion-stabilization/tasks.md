---
description: "Actionable, dependency-ordered task list for Slice 005 (005-ingestion-stabilization)"
---

# Tasks: Ingestion Pipeline Hardening, Audit Hygiene & CI/UI Stabilization

**Input**: Design documents from `/specs/005-ingestion-stabilization/`  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/api-ingestion-stabilization.md`, `quickstart.md`

**Organization**: Tasks are grouped by user story (`US1`–`US4`) in priority order so each increment is independently implementable, verifiable, and reviewable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, zero incomplete dependencies)
- **[Story]**: Maps directly to User Story in `spec.md` (`US1`, `US2`, `US3`, `US4`)

---

## Phase 1: Setup & Configuration Verification

**Purpose**: Ensure baseline application configuration supports bounded HTTP timeouts and executor settings without breaking local or CI environments.

- [x] T001 Verify `gemini.timeout-ms` default configuration (`60000`) and ensure no conflicting task-executor settings exist in `backend/src/main/resources/application.yml`.
- [x] T002 Verify current baseline tests and build pass before changes via `mvn -f backend/pom.xml test`, `npm --prefix frontend run lint`, and `npm --prefix frontend run build`.

---

## Phase 2: Foundational Schema & Executor Infrastructure (Blocking Prerequisites)

**Purpose**: Schema, entity provenance, and bounded async executor foundation required across US1 and US2 before story implementation begins.

**⚠️ CRITICAL**: Complete this phase before starting User Story work so migrations, repositories, and thread pools exist cleanly without violating Spring Modulith boundaries.

- [x] T003 Author Flyway migration `backend/src/main/resources/db/migration/V24__Harden_Ingestion_Job_Links_And_Question_Provenance.sql` adding nullable `questions.ingestion_job_id UUID` referencing `ingestion_jobs(id) ON DELETE SET NULL`, index `idx_questions_ingestion_job_created (ingestion_job_id, created_at)`, `ingestion_jobs.updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`, and replacing `ingestion_jobs_subject_id_fkey` with `FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE`.
- [x] T004 [P] Add scalar `@Column(name = "ingestion_job_id") private UUID ingestionJobId;` (strictly `UUID`, zero `@ManyToOne IngestionJob` reference to preserve Spring Modulith `questionbank` isolation) in `backend/src/main/java/com/studentprep/questionbank/Question.java` and add `findByIngestionJobIdOrderByCreatedAtAsc(UUID ingestionJobId)` in `backend/src/main/java/com/studentprep/questionbank/QuestionRepository.java`.
- [x] T005 [P] Add `@Modifying void deleteBySubject_Id(UUID subjectId);` in `backend/src/main/java/com/studentprep/questionbank/QuestionContextRepository.java`.
- [x] T006 [P] Add `@Column(name = "updated_at") private LocalDateTime updatedAt;` with `@PrePersist` / `@PreUpdate` timestamp lifecycle in `backend/src/main/java/com/studentprep/ingestion/job/IngestionJob.java` and add `findByStatusIn(List<IngestionJobStatus> statuses)` in `backend/src/main/java/com/studentprep/ingestion/job/IngestionJobRepository.java`.
- [x] T007 [P] Create bounded `ingestionTaskExecutor` bean (`ThreadPoolTaskExecutor`, `corePoolSize=2`, `maxPoolSize=4`, `queueCapacity=25`, thread prefix `"ingestion-"`) in `backend/src/main/java/com/studentprep/config/AsyncConfig.java`.

**Checkpoint**: Database schema (`V24`), repositories, entity fields, and bounded `ingestionTaskExecutor` compile and pass `ModulithArchitectureTest`.

---

## Phase 3: User Story 1 - Accurate Comprehension Grouping & Reliable Asynchronous PDF Ingestion (Priority: P1) 🎯 MVP

**Goal**: Eliminate cross-passage/cross-upload question corruption by respecting `is_follow_up`, scoping post-processing to `ingestionJobId`, offloading Docling + LLM extraction asynchronously via `ingestionTaskExecutor` with startup job recovery, and hardening LLM timeouts + truncated JSON salvage.

**Independent Test**: Run unit & integration tests verifying that (`a`) explicit `is_follow_up: false` terminates passage linking (`context = null`), (`b`) job-scoped `postProcessContextGroups(jobId)` leaves earlier uploads untouched, (`c`) `POST /api/v1/admin/ingestion/pdf` returns `202 Accepted` immediately while Docling runs asynchronously, (`d`) stranded `PENDING`/`PROCESSING` jobs recover on startup as `FAILED`, and (`e`) truncated JSON arrays ending with `"questionNumber"` salvage complete question objects cleanly.

### Tests for User Story 1

- [x] T008 [P] [US1] Update `backend/src/test/java/com/studentprep/ingestion/PostProcessTest.java` to verify explicit `is_follow_up: false` resets active context (leaves standalone `q2.getContext() == null`), explicit `is_follow_up: true` links to active passage, unambiguous `"Fill in gap ..."` continuation with null flag links when active context exists, and job-scoped query (`findByIngestionJobIdOrderByCreatedAtAsc`) isolates prior uploads.
- [x] T009 [P] [US1] Update `backend/src/test/java/com/studentprep/ingestion/AsyncIngestionWorkerTest.java` and add salvage/timeout assertions in `backend/src/test/java/com/studentprep/ingestion/LlmStructuringServiceTest.java` verifying balanced JSON salvage for trailing `"questionNumber"` and `"is_follow_up"` keys, bounded connect/read timeouts, and restart recovery marking stale `PENDING`/`PROCESSING` jobs as `FAILED`.
- [x] T010 [P] [US1] Add integration/controller test coverage in `backend/src/test/java/com/studentprep/ingestion/IngestionControllerIntegrationTest.java` verifying `POST /api/v1/admin/ingestion/pdf` persists `IngestionJob(PENDING)`, returns `202 Accepted` immediately with `jobId` without waiting on synchronous Docling parsing, and transitions failed Docling calls asynchronously to `FAILED` with sanitized error messages.

### Implementation for User Story 1

- [x] T011 [P] [US1] Configure bounded `SimpleClientHttpRequestFactory` connect timeout (`10_000 ms`) and read timeout (`@Value("${gemini.timeout-ms:60000}")`) on `RestClient`, and replace trailing regex salvage with a balanced bracket/string-aware JSON object scanner supporting arbitrary trailing fields (`"questionNumber"` or `"is_follow_up"`) in `backend/src/main/java/com/studentprep/ingestion/LlmStructuringService.java`.
- [x] T012 [US1] Update `relinkContextsForSubject(UUID subjectId)` in `backend/src/main/java/com/studentprep/questionbank/QuestionService.java` to branch explicitly on `is_follow_up` (`shared_context` creates/switches context; `is_follow_up == true` retains `activeContext`; `is_follow_up == false` sets `q.setContext(null)` and resets `activeContext = null`; missing `is_follow_up` resets `activeContext` unless prompt matches `"Fill in gap ..."` continuation).
- [x] T013 [US1] Refactor `backend/src/main/java/com/studentprep/ingestion/AsyncIngestionWorker.java` to accept `(UUID jobId, UUID subjectId, byte[] pdfBytes)` on `@Async("ingestionTaskExecutor")`, invoke `DoclingService.parsePdf(new ByteArrayResource(pdfBytes) ...)` asynchronously inside the worker, replace recursive `CompletableFuture.runAsync` with an iterative loop using `TransactionTemplate` per step (no detached JPA entities across threads), tag saved draft `Question` rows with `ingestionJobId`, respect `is_follow_up` during inline chunk processing AND job-scoped `postProcessContextGroups(UUID jobId)`, and add startup `@EventListener(ApplicationReadyEvent.class)` recovery transitioning stranded `PENDING`/`PROCESSING` jobs to `FAILED` (without touching active startup integration tests unexpectedly).
- [x] T014 [US1] Update `POST /api/v1/admin/ingestion/pdf` in `backend/src/main/java/com/studentprep/ingestion/IngestionController.java` to validate `.pdf` (`<= 10 MB`, MIME, `%PDF-`), read `file.getBytes()` cleanly up-front, save `IngestionJob(PENDING)`, dispatch `asyncIngestionWorker.processPdf(job.getId(), subject.getId(), pdfBytes, originalFilename)`, and return `202 Accepted` immediately.

**Checkpoint**: User Story 1 passes `mvn -f backend/pom.xml -Dtest=PostProcessTest,AsyncIngestionWorkerTest,LlmStructuringServiceTest,IngestionControllerIntegrationTest,QuestionServiceTest test`.

---

## Phase 4: User Story 2 - Safe Subject Lifecycle, Validated Question Edits & High-Signal Audit Logs (Priority: P2)

**Goal**: Make empty-subject deletion work cleanly even with prior ingestion jobs/contexts, enforce Bean Validation on question updates with `QuestionUpdateRequest`, guarantee `AFTER_COMMIT` cache eviction, and stop routine admin `GET` polls from flooding `audit_logs`.

**Independent Test**: Run integration tests creating a subject with a failed/completed ingestion job and orphaned context, confirming `DELETE` returns `409` while questions exist and `200 OK` after questions are removed; test invalid `PUT /api/v1/admin/questions/{id}` payloads (`400 Bad Request` `ProblemDetail`); and verify admin `GET /dashboard` / `GET /ingestion/jobs/{id}` polls create `0` `audit_logs` rows while `POST`/`PUT`/`PATCH`/`DELETE` and `/script` / `/pdf` exports create `1` row with `endpoint.length() <= 255`.

### Tests for User Story 2

- [x] T015 [P] [US2] Add MockMvc / integration tests in `backend/src/test/java/com/studentprep/questionbank/SubjectAndQuestionLifecycleIntegrationTest.java` covering `DELETE /api/v1/admin/subjects/{id}` (`409 Conflict` when questions exist; `200 OK` cleaning orphaned contexts and cascading historical ingestion jobs when zero questions exist), `QuestionUpdateRequest` validation (`400` on missing `status`/`content` or promoting to `ACTIVE` without `correctOption`), and post-commit cache eviction.
- [x] T016 [P] [US2] Add test `backend/src/test/java/com/studentprep/security/AuditLogAspectIntegrationTest.java` verifying routine admin `GET` polls (`/api/v1/admin/analytics/dashboard`, `/flagged-sessions`, `/ingestion/jobs/{id}`) insert `0` rows into `audit_logs`, while `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping` mutations and sensitive `/slip` / `/script` / `/pdf` / `/credentials-pdf` `GET` exports insert `1` row with `endpoint` bounded to `<= 255` characters.

### Implementation for User Story 2

- [x] T017 [P] [US2] Create `backend/src/main/java/com/studentprep/questionbank/dto/QuestionUpdateRequest.java` with `@NotNull QuestionStatus status`, `@Size(max = 255) String topic`, and `@NotNull Map<String, Object> content`.
- [x] T018 [US2] Update `PUT /api/v1/admin/questions/{id}` in `backend/src/main/java/com/studentprep/questionbank/QuestionController.java` and `updateQuestionStatus` in `backend/src/main/java/com/studentprep/questionbank/QuestionService.java` to consume `@Valid @RequestBody QuestionUpdateRequest`, validate non-blank `text` / `correctOption` when status is `ACTIVE`, update `status`, `topic`, and `content`, and evict question cache after commit.
- [x] T019 [US2] Annotate `updateSubject` and `deleteSubject` in `backend/src/main/java/com/studentprep/questionbank/SubjectController.java` with `@Transactional`; return RFC 7807 `409 Conflict` (`ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete subject while questions remain. Delete active and draft questions first.")`) when `questionRepository.existsBySubject_Id(id)` is true, call `contextRepository.deleteBySubject_Id(id)` before `subjectRepository.deleteById(id)`, and evict Redis cache after commit.
- [x] T020 [US2] Refactor `backend/src/main/java/com/studentprep/security/AuditLogAspect.java` to include `@PatchMapping`, drop blanket `@GetMapping`/`@RequestMapping` read logging, audit `@GetMapping` ONLY when request URI targets sensitive export/audit downloads (`/slip`, `/pdf`, `/script`, `/credentials-pdf`), and truncate `endpoint` safely to max `255` chars matching `V11` column constraint (`VARCHAR(255)`).

**Checkpoint**: User Story 2 passes `SubjectAndQuestionLifecycleIntegrationTest`, `AuditLogAspectIntegrationTest`, and `ModulithArchitectureTest`.

---

## Phase 5: User Story 3 - Admin Results Pagination, Accessible Controls & KaTeX Rendering Consistency (Priority: P3)

**Goal**: Eliminate silent 20-row truncation on `/admin/results`, fix all nested interactive `<button>` `AlertDialogTrigger` instances and dark-themed `SelectContent` popups in Admin UI, synchronize `DraftQuestionCard` edits/images/errors, and unify safe `\(...\)` + `\[...\]` KaTeX rendering across `RichText` and `MathText`.

**Independent Test**: `npm --prefix frontend run lint` (`0` errors, `0` warnings on touched files) and `npm --prefix frontend run build` pass; DOM checks confirm `0` nested buttons inside `SubjectDetail` triggers and light-mode `SelectContent`; `/admin/results` traverses multiple pages when `totalElements > 20`; and `MathText` / `RichText` normalize both `\(...\)` and `\[...\]` safely.

### Implementation for User Story 3

- [x] T021 [P] [US3] Extract shared safe KaTeX delimiter preprocessor (`\(...\)` -> `$...$`, `\[...\]` -> `$$...$$`, stripping/disallowing raw HTML elements per XSS policy) in `frontend/src/lib/katex.ts` and update both `frontend/src/components/RichText.tsx` and `frontend/src/components/ui/MathText.tsx` to use it.
- [x] T022 [P] [US3] Remove hardcoded `dark` surface styling from `SelectContent` in `frontend/src/components/ui/select.tsx` so dropdown options render with light-mode `bg-white text-slate-900` legibility matching `bg-slate-50`.
- [x] T023 [P] [US3] Update `frontend/src/pages/admin/ResultsPage.tsx` to pass `?page=${page}&size=20` to `GET /api/v1/admin/analytics/results`, store `PagedResponse` `meta` (`number`, `totalPages`, `totalElements`, `size`), and render Previous/Next pagination buttons and result count indicators alongside existing `FlaggedSessionsCard` and `RowActionMenu`.
- [x] T024 [US3] Update `frontend/src/pages/admin/SubjectDetail.tsx` to convert all 6 `<AlertDialogTrigger><Button ...>` wrappers to Base UI `<AlertDialogTrigger render={<Button ... />} />` (eliminating nested buttons), fix stale `DraftQuestionCard` state synchronization when `initialQuestion` refreshes, store uploaded question diagrams strictly as `{ type: 'image', url }` objects, and surface backend `ProblemDetail` messages via `extractProblemMessage` on `/pdf` upload and draft saves.

**Checkpoint**: `npm --prefix frontend run lint` reports `0` errors / `0` warnings in touched files and `npm --prefix frontend run build` completes cleanly.

---

## Phase 6: User Story 4 - Full Automated E2E Regression Coverage in CI (Priority: P4)

**Goal**: Add executable end-to-end Playwright verification for Slice 004 (`Exam Script Audit`) and Slice 005 (`Results` pagination, `SubjectDetail` accessibility & controls, safe Subject deletion, and async PDF ingestion job lifecycle) and gate CI on all active Playwright specs across Chromium and WebKit.

**Independent Test**: Execute `npx --prefix frontend playwright test tests/auth-hardening.spec.ts tests/exam-kiosk-integrity.spec.ts tests/ingestion-and-audit-stabilization.spec.ts --reporter=list` locally against live Docker services + Spring Boot backend and verify both Chromium and WebKit pass with zero failures.

### Implementation for User Story 4

- [x] T025 [US4] Author unmocked Playwright E2E suite `frontend/tests/ingestion-and-audit-stabilization.spec.ts` covering (`a`) Admin & Supervisor Exam Script Audit modal (`Inspect Script`, `25/page` pagination, `answerReceiptCode` verification, and `Script (PDF)` download) plus Student `403` isolation, (`b`) `/admin/results` server-driven pagination across `> 20` results, (`c`) `SubjectDetail` zero nested `<button>` check + `Select` light-mode interaction + draft question `PUT` edit, (`d`) safe Subject deletion (`409` when question exists, `200 OK` cleanly when emptied even with an orphaned context/failed ingestion job), and (`e`) invalid `.pdf` / async `202` upload handling + zero polling `GET` entries in `audit_logs`.
- [x] T026 [US4] Update `.github/workflows/ci.yml` `frontend-e2e-tests` step (`npx playwright test`) to execute `tests/auth-hardening.spec.ts`, `tests/exam-kiosk-integrity.spec.ts`, and `tests/ingestion-and-audit-stabilization.spec.ts` across Chromium and WebKit.

**Checkpoint**: All Playwright E2E specs pass across Chromium and WebKit against the running backend stack.

---

## Phase 7: Polish, Contract Sync & Release Gates

**Purpose**: Synchronize API documentation and execute full end-to-end verification before committing Slice 005.

- [x] T027 [P] Update `docs/api_contract.md` with Slice 005 ingestion `202 Accepted` async semantics, `DELETE /api/v1/admin/subjects/{id}` `409 Conflict` ProblemDetails, `QuestionUpdateRequest` contract, and `AuditLog` audit rules matching `specs/005-ingestion-stabilization/contracts/api-ingestion-stabilization.md`.
- [x] T028 Run all verification gates from `specs/005-ingestion-stabilization/quickstart.md`: `mvn -f backend/pom.xml test`, `pytest -v` (`services/docling-parser`), `npm --prefix frontend run lint`, `npm --prefix frontend run build`, and Playwright suite across Chromium and WebKit.

---

## Dependencies & Execution Order

### Phase Dependencies
- **Phase 1 (Setup)** → **Phase 2 (Foundational)**: Blocks all user stories (`US1`–`US4`) because `V24` schema, repositories, and `ingestionTaskExecutor` are shared prerequisites.
- **Phase 3 (US1)** and **Phase 4 (US2)** can execute in parallel once Phase 2 passes cleanly (`QuestionService.java` touches `relinkContextsForSubject` in US1 and `updateQuestionStatus` in US2 — execute T012 before T018 if run sequentially on `QuestionService.java`).
- **Phase 5 (US3)** depends minimally on Phase 4 API contracts (`DELETE /subjects/{id}` `409` / `PUT /questions/{id}` DTO shape) for `SubjectDetail.tsx` (`T024`), while `T021` (`katex.ts`), `T022` (`select.tsx`), and `T023` (`ResultsPage.tsx`) can run in parallel immediately after Phase 2.
- **Phase 6 (US4)** runs after Phases 3–5 are implemented so end-to-end specs test live completed behavior.
- **Phase 7 (Polish & Gates)** runs last before final commit/push review.

### Parallel Opportunities
- **Phase 2**: `T004`, `T005`, `T006`, `T007` touch separate modules/files and can run concurrently after `T003`.
- **US1**: `T008`, `T009`, `T010`, and `T011` operate on separate files and can run concurrently before integrating `T012`–`T014`.
- **US2**: `T015`, `T016`, `T017`, `T019`, and `T020` touch distinct files (`SubjectAndQuestionLifecycleIntegrationTest`, `AuditLogAspectIntegrationTest`, `QuestionUpdateRequest`, `SubjectController`, `AuditLogAspect`).
- **US3**: `T021`, `T022`, and `T023` touch completely independent frontend files (`lib/katex.ts` + `RichText`/`MathText`, `select.tsx`, `ResultsPage.tsx`).

---

## Implementation Strategy

1. **MVP First (Phases 1–3 / US1)**: Deliver `V24` + ingestion/comprehension correctness first (`PostProcessTest`, `AsyncIngestionWorkerTest`, `LlmStructuringServiceTest`, `IngestionControllerIntegrationTest`), ensuring PDFs never corrupt passages or block HTTP threads.
2. **Backend Lifecycle & Audit Increment (Phase 4 / US2)**: Land safe subject deletion, DTO validation, and `AuditLogAspect` polling filter, confirming `ModulithArchitectureTest` stays green.
3. **UI Polish & Full CI E2E Gate (Phases 5–7 / US3–US4)**: Finish pagination/accessibility/KaTeX UI fixes and wire all Chromium + WebKit E2E specs into CI before moving on to **Slice 006 (`006-production-packaging`)**.
