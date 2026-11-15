# Phase 0 Research: Ingestion Pipeline Hardening, Audit Hygiene & CI/UI Stabilization (`005-ingestion-stabilization`)

## 1. Comprehension Passage Linking & `is_follow_up` Semantics

- **Decision**: Enforce three explicit rules in both `AsyncIngestionWorker` (inline chunk extraction & job-scoped post-processing) and `QuestionService.relinkContextsForSubject`:
  1. Non-empty `shared_context` creates/switches `activeContext` and links that question to it.
  2. Explicit `is_follow_up == true` (with empty `shared_context`) links to `activeContext` if non-null.
  3. Explicit `is_follow_up == false` (with empty `shared_context`) **clears `activeContext = null`** and leaves the question standalone (`context = null`). If `is_follow_up` is omitted/null (older or partial LLM output), only treat unmistakable gap-fill prompts (`Fill in gap <number>`) as implicit continuations; otherwise treat missing `is_follow_up` as standalone and reset `activeContext = null`.
- **Rationale**: In `QuestionService.java:230-245`, `AsyncIngestionWorker.java:201-216`, and `AsyncIngestionWorker.java:288-328`, `is_follow_up` was extracted or prompted for by the LLM yet never branched on, pulling every subsequent standalone question in an exam paper onto the first comprehension passage. `PostProcessTest.java:27-41` even asserted that `is_follow_up: false` should attach `q2` to `q1`'s context.
- **Alternatives Considered**: Completely removing `postProcessContextGroups` was rejected because batch boundary follow-ups (where an LLM splits a passage across chunks) still benefit from sequential validation when scoped strictly to a single job and respectful of `is_follow_up: false`.

## 2. Job-Scoped Question Tracking Without Violating Spring Modulith

- **Decision**: Add nullable `ingestion_job_id UUID` to `questions` via Flyway `V24` referencing `ingestion_jobs(id) ON DELETE SET NULL`, mapped on `com.studentprep.questionbank.Question` strictly as a plain scalar `@Column(name = "ingestion_job_id") private UUID ingestionJobId;` (no `@ManyToOne IngestionJob` JPA association), and add `findByIngestionJobIdOrderByCreatedAtAsc(UUID jobId)` to `QuestionRepository`.
- **Rationale**: `ingestion` depends on `questionbank`, whereas `questionbank` MUST NOT depend on `ingestion` (`ModulithArchitectureTest`). Mapping `ingestionJobId` as a scalar `UUID` preserves strict unidirectional module boundaries while letting `AsyncIngestionWorker.postProcessContextGroups(UUID jobId)` inspect ONLY questions created during that upload rather than rewriting historical approved/draft questions in the subject.
- **Alternatives Considered**: Passing an in-memory `List<UUID>` across `@Async` chunk callbacks was rejected because it loses linkage across retries/persistence and provides no DB provenance for debugging or cleanup.

## 3. Non-Blocking Async PDF Extraction, Managed Executor & Restart Recovery

- **Decision**:
  1. In `IngestionController.ingestPdf`, validate the multipart `.pdf` header/MIME/size (`<= 10 MB`), read bytes synchronously once (`file.getBytes()`), persist `IngestionJob(PENDING)` immediately, and hand off `(jobId, subjectId, pdfBytes, originalFilename)` to `AsyncIngestionWorker` -> return `202 Accepted` immediately.
  2. Replace recursive `CompletableFuture.runAsync` (`ForkJoinPool.commonPool`) with a dedicated Spring `ThreadPoolTaskExecutor` (`ingestionTaskExecutor`, `core=2`, `max=4`, `queue=25`) running an iterative chunk loop using `TransactionTemplate` per DB state update (avoiding detached entity reuse and `@Transactional` self-invocation bypasses).
  3. Run a startup `@EventListener(ApplicationReadyEvent.class)` sweep (or ShedLock-guarded recovery) marking pre-existing `PENDING`/`PROCESSING` jobs from crashed/restarted JVMs as `FAILED` with `"Ingestion interrupted by server restart. Please re-upload the PDF."`.
- **Rationale**: Currently `doclingService.parsePdf` blocks the HTTP request thread up to 60 seconds, `processChunkAsync` mixes detached entities into `commonPool()`, and server restarts strand polling admins on an infinite spinner.
- **Alternatives Considered**: Writing PDFs to local disk temp storage on the backend adds filesystem cleanup failure modes, whereas PDFs are already strictly capped at 10 MB and parsed sequentially.

## 4. `LlmStructuringService` Timeouts & Balanced JSON Object Salvage

- **Decision**: Configure `SimpleClientHttpRequestFactory` connect timeout (`10s`) and read timeout (`60s`, configurable via `gemini.timeout-ms`) on `LlmStructuringService`, matching `DoclingService`. Replace the trailing-field regex `"is_follow_up" : true|false }` with a bracket/string-aware JSON object scanner over truncated arrays `[` so any complete `{ ... }` object (whether ending in `"questionNumber"` or `"is_follow_up"`) is recovered cleanly.
- **Rationale**: `LlmStructuringService` prompt asks for `..., is_follow_up: false, questionNumber: '1'`, making the current regex match zero objects when truncated mid-stream, and an unconfigured `RestClient` has infinite socket read timeouts by default.
- **Alternatives Considered**: Switching to Gemini structured output mode changes external API behavior across model versions; a clean bracket-depth parser over partial arrays works deterministically regardless of key ordering.

## 5. Safe Subject Deletion & Cache Eviction Within Module Boundaries

- **Decision**: Make `SubjectController.updateSubject` and `SubjectController.deleteSubject` `@Transactional`. In `deleteSubject`: check `questionRepository.existsBySubject_Id(id)` -> return RFC 7807 `409 Conflict` (`"Cannot delete subject while questions remain. Delete active and draft questions first."`), then delete any orphaned unlinked `question_contexts` for that subject (`contextRepository.deleteBySubject_Id(id)`), delete the subject, and let `V24`'s updated foreign key `ingestion_jobs.subject_id -> subjects(id) ON DELETE CASCADE` clean historical ingestion job records atomically in the same transaction. Evict `exam:questions:all` via `TransactionSynchronization.afterCommit`.
- **Rationale**: `V6` defined `ingestion_jobs.subject_id REFERENCES subjects(id)` without cascade, and `deleteSubject` never checked `question_contexts` or wrapped in `@Transactional`, causing `DataIntegrityViolationException` when deleting empty subjects after previous PDF imports or context links and running cache eviction prematurely.
- **Alternatives Considered**: Injecting `IngestionJobRepository` into `SubjectController` violates Spring Modulith (`questionbank` -> `ingestion` cycle). Updating `V24` FK to `ON DELETE CASCADE` (after `deleteSubject` verifies zero questions remain) keeps modules isolated.

## 6. High-Signal `AuditLogAspect` Pruning

- **Decision**: Scope `AuditLogAspect` away from blanket `@GetMapping` logging. Audit all `/api/v1/admin/**` mutating requests (`@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`) plus explicit sensitive read endpoints (`/slip`, `/pdf`, `/script`, `/credentials-pdf`), and cap stored `endpoint` strings at 255 characters (`VARCHAR(255)`). Routine admin dashboard/queue/job-status `GET` polls no longer emit Postgres writes.
- **Rationale**: `AuditLogAspect.java:25-29` currently inserts a Postgres row on every 3s ingestion poll and every dashboard fetch while missing `@PatchMapping`.
- **Alternatives Considered**: Async queueing of GET logs still bloats storage with zero forensic value; selective audit captures every state mutation and document/PII access cleanly.

## 7. Admin UI Defects & CI Playwright Suite Coverage

- **Decision**:
  - `ResultsPage.tsx`: Consume `PagedResponse` `meta` (`totalElements`, `totalPages`, `page`, `size`), expose Previous/Next pagination controls (`size=20`), and display total result counts.
  - `SubjectDetail.tsx` & `Select.tsx`: Convert all 6 `<AlertDialogTrigger><Button>` blocks to Base UI `<AlertDialogTrigger render={<Button ... />} />`; remove hardcoded `dark` surface class from `SelectContent` so dropdown menus render cleanly in light themes; sync `DraftQuestionCard` state with refreshed `initialQuestion`; surface backend `ProblemDetail` errors on `/pdf` and question saves via `extractProblemMessage`; store uploaded assets as `{ type: 'image', url }`.
  - `MathText.tsx` & `RichText.tsx`: Share a common KaTeX normalization + safe Markdown utility (normalizing both `\(...\)` and `\[...\]` delimiters to `$` and `$$` and disallowing raw HTML injection).
  - `.github/workflows/ci.yml`: Execute `tests/auth-hardening.spec.ts`, `tests/exam-kiosk-integrity.spec.ts`, and `tests/ingestion-and-audit-stabilization.spec.ts` in Chromium and WebKit.
- **Rationale**: Fixes real silent truncation (`ResultsPage`), invalid HTML (`<button><button>`), dark popup clash, asset schema mismatch, delimiter drift, and unexercised E2E specs in CI.
