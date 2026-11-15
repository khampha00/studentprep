# Feature Specification: Ingestion Pipeline Hardening, Audit Hygiene & CI/UI Stabilization

**Feature Branch**: `deployment` (Slice `005-ingestion-stabilization`)  
**Created**: 2026-11-15  
**Status**: Draft  
**Input**: User description: "Slice 005 of pre-deployment remediation: harden asynchronous PDF/LLM ingestion and comprehension passage linking, align subject deletion and validation behavior, eliminate high-frequency GET polling bloat from admin audit logs, fix remaining Admin UI defects (results pagination, draft review card state sync, Select theme, AlertDialog semantics, KaTeX delimiter parity), and run the full unmocked Playwright E2E suite in CI."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Accurate Comprehension Grouping & Reliable Asynchronous PDF Ingestion (Priority: P1)

An Administrator uploads a multi-page examination PDF for a subject so questions are extracted into the draft review queue, comprehension passages stay linked strictly to their actual follow-up questions, standalone questions remain ungrouped, and partial/slow upstream responses do not corrupt existing bank questions or fail silently.

**Why this priority**: In JAMB CBT mocks (especially English Language and Economics), attaching unrelated multiple-choice questions to an earlier reading passage or silently losing extracted items renders an entire exam bank unusable and breaks candidate fairness.

**Independent Test**: Upload or replay a multi-chunk extraction containing a shared reading passage, two follow-up questions, and subsequent standalone questions alongside pre-existing questions in the same subject; verify only the passage owner and explicit follow-ups link to the passage while standalone and prior-batch questions stay untouched.

**Acceptance Scenarios**:

1. **Given** an uploaded PDF containing a shared passage followed by two explicit follow-up questions (`is_follow_up = true`) and then a standalone question (`is_follow_up = false`), **When** ingestion completes and context post-processing runs (or an Admin clicks Relink Contexts), **Then** only the two follow-up questions link to the passage and the subsequent standalone question remains unlinked (`contextId = null`).
2. **Given** a subject that already contains drafts and active questions from earlier uploads, **When** a new PDF ingestion job runs post-processing on its newly extracted questions, **Then** pre-existing questions and passages from previous uploads are NOT mutated or pulled into the new job's contexts.
3. **Given** a large PDF upload submitted to the ingestion endpoint, **When** the Administrator starts extraction, **Then** the request immediately returns an accepted job identifier so long-running PDF text extraction and structuring proceed asynchronously with live chunk progress and resilient failure reporting, and any jobs interrupted mid-processing across a server restart are transitioned safely so the Administrator receives a clear retryable failure state rather than an infinite spinner.
4. **Given** an upstream AI extraction timeout or truncated structured response array ending mid-question, **When** timeouts or truncation occur, **Then** bounded request timeouts prevent indefinite stalls, completely structured objects (including trailing `questionNumber` fields) are salvaged cleanly, and sanitized error messages never leak API keys or raw stack traces.

---

### User Story 2 - Safe Subject Lifecycle, Question Edit Validation & High-Signal Audit Logs (Priority: P2)

An Administrator creates, edits, or deletes subjects and review-queue questions safely, and security auditors inspect an accurate, concise administrative audit trail free of noisy dashboard polling entries.

**Why this priority**: Deleting an unused subject after a failed PDF upload currently fails with internal database constraint errors, unvalidated question edits risk invalid states, and logging every automated dashboard `GET` refresh floods the audit table and obscures real administrative mutations.

**Independent Test**: Trigger routine dashboard/results/job-status `GET` polls alongside an explicit admin mutation (`PUT`/`PATCH`/`DELETE`/`POST`) and official document export; verify only mutating actions and high-value document exports create audit rows, and verify deleting a subject with no active/draft questions succeeds cleanly while deleting one with questions returns a clear conflict explanation.

**Acceptance Scenarios**:

1. **Given** an existing subject that has zero questions (`ACTIVE` or `DRAFT`) but has historical failed/completed ingestion jobs or orphaned unlinked passage contexts, **When** an Administrator deletes the subject, **Then** the subject is deleted cleanly without server errors and exam question caches update after the transaction commits.
2. **Given** an existing subject that still contains active or draft questions, **When** an Administrator attempts to delete it, **Then** the system blocks deletion cleanly with a descriptive conflict error explaining that existing questions must be removed first.
3. **Given** an Admin updating a question via the review UI or edit API, **When** invalid status values or malformed content are submitted, **Then** validation rejects bad payloads clearly while valid updates save and refresh parent state reliably without stale card state or raw string vs object asset shape drift.
4. **Given** active Administrator and Supervisor browser sessions polling overview, live-session, flagged-session, and ingestion-job `GET` endpoints every few seconds, **When** polling occurs alongside intentional mutations (`POST`, `PUT`, `PATCH`, `DELETE`) and sensitive exports (`/script`, `/pdf`, `/slip`), **Then** routine read polling does NOT write repetitive rows into `audit_logs`, while every mutation and official export/script audit IS recorded with safely bounded endpoint strings.

---

### User Story 3 - Complete Admin Results Pagination, Accessible Controls & KaTeX Rendering Consistency (Priority: P3)

An Administrator reviewing cohorts larger than 20 candidates can page cleanly through all completed exam results, interact with dropdowns/confirmation dialogs without dark-theme or nested-button defects, and read inline math delimiters consistently across question previews and audit views.

**Why this priority**: Silent 20-row truncation hides candidate scores in real school cohorts, nested `<button>` dialog triggers violate HTML/accessibility standards, and mismatched LaTeX delimiter support breaks formulas between draft preview and student exam views.

**Independent Test**: Seed more than 20 completed exam results, open `/admin/results` and `/admin/subjects/:id`, verify pagination controls traverse page 2+ accurately, inspect confirmation triggers for zero nested interactive elements, and render questions using `\(...\)`, `\[...\]`, `$...$`, and `$$...$$`.

**Acceptance Scenarios**:

1. **Given** 25+ graded exam results, **When** an Administrator views `/admin/results`, **Then** the page surfaces pagination metadata (`totalElements`, `totalPages`) and Next/Previous controls so all candidate results beyond the first 20 rows are accessible.
2. **Given** an Administrator editing or confirming bulk actions in Subject Detail, **When** opening dropdown selectors (`SelectContent`), confirmation modals (`AlertDialogTrigger`), or adding diagram images, **Then** selectors use legible light surfaces matching `bg-slate-50`, confirmation triggers render valid single interactive buttons, draft edits reflect fresh parent state immediately, and newly uploaded diagrams use canonical `{ type: "image", url }` asset metadata.
3. **Given** questions containing `\(...\)` or `\[...\]` LaTeX expressions or inline math reviewed across question-bank cards and exam views, **When** rendered, **Then** expressions display consistently cleanly and strip/escape unsafe HTML.

---

### User Story 4 - Full Automated E2E Regression Coverage in CI (Priority: P4)

Engineering merges changes confident that the CI pipeline automatically validates authentication hardening, exam kiosk/moderation integrity, script auditing, and ingestion stabilization across browsers on every push and pull request.

**Why this priority**: Leaving `exam-kiosk-integrity.spec.ts` and audit/ingestion flows outside CI allows regressions to slip past passing unit tests.

**Independent Test**: Run the Playwright suites configured in CI locally against the running stack and inspect the CI workflow definition to verify all end-to-end specs execute cleanly on Chromium and WebKit.

**Acceptance Scenarios**:

1. **Given** the CI workflow running against live Postgres, Redis, MinIO, Docling, Spring Boot, and built frontend assets, **When** the `frontend-e2e-tests` job executes, **Then** it runs `auth-hardening.spec.ts`, `exam-kiosk-integrity.spec.ts`, and the Slice 004/005 script-audit and stabilization Playwright scenarios across configured browsers with zero skipped regressions.

### Edge Cases

- What if an LLM omits `is_follow_up` (`null`) on a clearly numbered gap-fill question (e.g., `"Fill in gap 12"`) immediately following a passage? Wait-safe rule: explicit `is_follow_up: true` links to active passage; explicit `is_follow_up: false` ALWAYS terminates passage linking (`activeContext = null`); omitted/null `is_follow_up` only continues an active passage if no explicit termination occurred and text matches unambiguous continuation (`"Fill in gap ..."`).
- What if a backend instance restarts while an `IngestionJob` is in `PENDING` or `PROCESSING` status? A startup/scheduled recovery sweep marks interrupted jobs `FAILED` with a clean retry message rather than leaving admins stuck polling forever.
- What if `PdfParsing` fails inside the background ingestion job after `202 Accepted` was returned? The `IngestionJob` transitions to `FAILED` with a sanitized error message (with zero API keys, URLs with secrets, or stack traces) surfaced to the Admin UI toast when polling `/jobs/{jobId}`.
- What if `AuditLogAspect` intercepts an endpoint URI longer than 255 characters (e.g., long query/path strings)? The recorded endpoint string is safely truncated with no SQL truncation error disrupting the underlying request.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Asynchronous PDF ingestion and context relinking MUST respect `is_follow_up` semantics: questions with `shared_context` start/link a passage context; questions with `is_follow_up: true` (without their own `shared_context`) attach to the current passage context; questions with `is_follow_up: false` and no `shared_context` MUST clear the active passage context (`null`) and remain standalone.
- **FR-002**: Ingestion post-processing MUST be scoped strictly to questions belonging to the triggering ingestion job rather than mutating questions from earlier uploads in the same subject.
- **FR-003**: Uploaded PDF extraction MUST immediately persist an ingestion job record and return `202 Accepted` with `jobId`, executing document parsing (`Docling`) and LLM chunk structuring asynchronously on a bounded managed executor using persisted IDs (no detached JPA entity sharing across threads).
- **FR-004**: Any `IngestionJob` left in `PENDING` or `PROCESSING` across server startup/crash MUST be transitioned cleanly to `FAILED` with a user-safe recovery message, and `LlmStructuringService` MUST enforce bounded HTTP connect/read timeouts and reliably salvage completed JSON objects (supporting trailing `"questionNumber"` or `"is_follow_up"` keys).
- **FR-005**: Deleting a subject with zero remaining questions MUST succeed cleanly even when historical ingestion jobs or orphaned passage contexts exist, evict active question caches strictly after commit, and return a clear conflict response when questions remain linked.
- **FR-006**: `PUT /api/v1/admin/questions/{id}` and related subject/question write endpoints MUST enforce Bean Validation on structured request payloads and sanitize inputs without breaking existing approval workflows.
- **FR-007**: `AuditLogAspect` MUST audit mutating admin endpoints (`POST`, `PUT`, `PATCH`, `DELETE`) and explicit sensitive document/audit export endpoints (`/slip`, `/pdf`, `/script`, `/credentials-pdf`), MUST NOT persist routine admin `GET` polling requests (e.g., `/dashboard`, `/live-sessions`, `/flagged-sessions`, `/jobs/*`), and MUST safely truncate `endpoint` values to fit the 255-character database column.
- **FR-008**: `/admin/results` MUST render server-driven pagination controls (`page`, `size`, `totalElements`, `totalPages`) matching the backend `PagedResponse` contract so cohorts larger than 20 results are fully browsable.
- **FR-009**: `SubjectDetail` MUST fix nested interactive `<button>` markup inside `AlertDialogTrigger`, replace the dark `SelectContent` popup styling with light-mode-compatible surface styles, keep `DraftQuestionCard` synchronized with refreshed parent props, surface detailed ProblemDetails error messages on upload/save failures, and persist uploaded assets using structured `{ type: 'image', url }` objects.
- **FR-010**: `MathText` and `RichText` MUST consistently normalize inline `\(...\)` and block `\[...\]` KaTeX delimiters and prevent raw HTML XSS execution.
- **FR-011**: The CI workflow MUST execute all active unmocked Playwright E2E suites (`auth-hardening.spec.ts`, `exam-kiosk-integrity.spec.ts`, and Slice 004/005 regression specs).

### Key Entities *(include if feature involves data)*

- **IngestionJob** (`ingestion_jobs`): Tracks PDF extraction lifecycle (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`), chunk counters (`totalChunks`, `processedChunks`), sanitized `errorMessage`, `createdAt`, `updatedAt`, and cascading subject reference so unused subjects can be deleted cleanly.
- **Question** (`questions`): Carries optional `ingestionJobId` (`UUID`, nullable, referencing `ingestion_jobs(id)` with `ON DELETE SET NULL` and zero Java cross-module entity coupling) so post-processing operates strictly on the current job's questions.
- **AuditLog** (`audit_logs`): Records high-signal administrative mutations and sensitive document exports with bounded `endpoint` (<= 255 chars), `username`, `action`, and `timestamp`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Re-running or completing PDF ingestion on a multi-passage + standalone mixed batch links 100% of follow-up questions accurately and leaves 0% of `is_follow_up: false` standalone questions attached to prior passages.
- **SC-002**: `POST /api/v1/admin/ingestion/pdf` returns `202 Accepted` in under `500 ms` even for multi-MB PDFs whose upstream parsing exceeds 5 seconds, without blocking servlet request workers.
- **SC-003**: Polling the Admin Overview and Ingestion Job status for 60 seconds generates `0` routine `GET` rows in `audit_logs`, while every admin mutation and official slip/script PDF export generates `1` audit row.
- **SC-004**: Cohorts with `25+` graded results allow browsing every row across pages on `/admin/results`, and Subject Detail passes automated DOM checks with `0` nested `<button>` violations.
- **SC-005**: Backend suite (`mvn test` incl. `ModulithArchitectureTest`), Python Docling tests (`pytest`), frontend lint (`0` errors, `0` warnings added in touched files), production Vite build, and full Playwright E2E suites pass in CI.

## Assumptions

- Production Dockerfiles (`backend/Dockerfile`, `frontend/Dockerfile`), Nginx reverse proxy configuration, `/actuator/health`, and full-stack Compose orchestration remain intentionally out of scope for Slice 005 and will be delivered in **Slice 006 (`006-production-packaging`)**.
- Spring Modulith package boundaries (`ingestion -> questionbank`, never `questionbank -> ingestion`) remain enforced by `ModulithArchitectureTest`.
