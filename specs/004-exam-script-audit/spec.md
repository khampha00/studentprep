# Feature Specification: Exam Script Audit, Cryptographic Answer Receipt & Printable Verification Report

**Feature Branch**: `deployment` (Slice `004-exam-script-audit`)  
**Created**: 2026-11-15  
**Status**: Approved  
**Input**: Enable `ROLE_ADMIN` and `ROLE_SUPERVISOR` to inspect and download a candidate's complete question-by-question examination script while resolving all 5 architectural bottlenecks (question bank leakage, non-repudiation via SHA-256 answer receipts, offline/expiry sync provenance, PostgreSQL TOAST & browser KaTeX DOM performance, and Spring Modulith boundaries).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Interactive Exam Script Inspection for Admins & Supervisors (Priority: P1)

When a candidate disputes their examination score or a sitting is flagged (`LATE_SUBMISSION_FLAGGED` or `FLAGGED_TAB_SWITCH`), an Administrator (`ROLE_ADMIN`) or Supervisor (`ROLE_SUPERVISOR`) needs to inspect the exact questions the candidate saw, the options they selected, the authoritative correct answers from the frozen session snapshot, and the submission timeline provenance.

**Why this priority**: Directly provides transparent dispute resolution and forensic visibility without exposing the live question bank to student devices.

**Independent Test**: Log in as `ROLE_ADMIN` or `ROLE_SUPERVISOR`, navigate to `/admin/results`, click **Inspect Script** on any completed or flagged sitting, and verify that the modal displays the candidate metadata, SHA-256 Answer Receipt Code, sync/submission provenance, subject tabs, outcome filters (`All`, `Correct`, `Incorrect`, `Unanswered`, `Flagged`), and paginated (`25/page`) KaTeX-rendered questions.

**Acceptance Scenarios**:

1. **Given** a submitted or flagged `ExamSession`, **When** an authenticated `ROLE_ADMIN` or `ROLE_SUPERVISOR` requests `GET /api/v1/admin/exams/sessions/{sessionId}/script`, **Then** the backend reconstructs the script from `exam_sessions.snapshot_payload` and `state_payload` and returns `200 OK` with candidate metadata, `answerReceiptCode`, `submissionTrigger`, `lastClientSyncAt`, subject summaries, and itemized question records.
2. **Given** a 195-question exam script in the Audit Modal, **When** an Admin or Supervisor opens the modal or switches filters, **Then** the UI paginates rendered `<RichText />` question cards (`25 per page`) and filters by subject and outcome (`ALL`, `CORRECT`, `INCORRECT`, `UNANSWERED`, `FLAGGED`) without browser main-thread freezes.

---

### User Story 2 - Printable A4 Exam Script Audit PDF Download (Priority: P1)

During an in-person result verification or disciplinary review, an Administrator (`ROLE_ADMIN`) or Supervisor (`ROLE_SUPERVISOR`) needs to download an official, printable A4 PDF containing the candidate's metadata, cryptographic Answer Receipt Code, sync/submission timeline, per-subject summary, and the complete itemized question-by-question record.

**Why this priority**: Provides a permanent, printable audit record that can be reviewed offline or signed during candidate dispute resolution.

**Independent Test**: Click **Script (PDF)** on a row in `/admin/results` (or **Download Audit Script (PDF)** inside the Audit Modal) as both `ROLE_ADMIN` and `ROLE_SUPERVISOR` and verify that a valid multi-page A4 PDF (`%PDF-1.`) downloads with filename `<CandidateName>_<RegNo>-Exam-Script-Audit.pdf`.

**Acceptance Scenarios**:

1. **Given** an existing `ExamSession`, **When** `ROLE_ADMIN` or `ROLE_SUPERVISOR` requests `GET /api/v1/admin/exams/sessions/{sessionId}/script/pdf`, **Then** the backend returns `200 OK` (`application/pdf`) with `Content-Disposition: attachment; filename="<Name>_<RegNo>-Exam-Script-Audit.pdf"`.

---

### User Story 3 - Cryptographic Answer Receipt & Strict RBAC Protection (Priority: P1)

Candidates need tamper-evident proof that the server graded the exact answers they submitted (`answerReceiptCode`), while the question stems and answer keys remain strictly restricted to `ROLE_ADMIN` and `ROLE_SUPERVISOR` (`403 Forbidden` for `ROLE_STUDENT`).

**Why this priority**: Solves both sides of the trust problem—preventing question bank harvesting by students while mathematically proving that a student's submitted answers were not altered.

**Independent Test**:
1. Verify that `GET /api/v1/student/results` (and `/result` + Result Slip PDF) includes the deterministic `answerReceiptCode` (`XXXX-XXXX-XXXX`) matching `GET /api/v1/admin/exams/sessions/{sessionId}/script`.
2. Verify that a `ROLE_STUDENT` token calling `/api/v1/admin/exams/sessions/{sessionId}/script` or `/script/pdf` receives `403 Forbidden`.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001 (Snapshot-Backed Script Reconstruction)**: `ExamScriptAuditService` MUST reconstruct every question's stem, passage context, assets, options, and `correctOption` from `exam_sessions.snapshot_payload` (falling back to deterministic `ExamPayloadGenerator.buildSessionSnapshot(userId, shuffleSeed)` only for legacy pre-`V22` rows) and match candidate answers from `exam_sessions.state_payload`.
- **FR-002 (Deterministic SHA-256 Answer Receipt)**: `AnswerReceiptHasher` (`com.studentprep.common`) MUST compute a canonical SHA-256 digest over `sessionId` and sorted `questionId=selectedOption` entries, exposing both a 12-character formatted receipt code (`XXXX-XXXX-XXXX`) and full 64-character SHA-256 hex string across candidate results, candidate result slips, and the admin/supervisor audit script.
- **FR-003 (Submission & Sync Timeline Provenance)**: The audit response and PDF MUST surface `lastClientSyncAt` (derived from `state_payload.lastUpdated`), `expectedEndTime`, `endTime`, `tabSwitchCount`, and a normalized `submissionTrigger` (`MANUAL_SUBMIT`, `TIMER_AUTO_SUBMIT`, `MALPRACTICE_AUTO_SUBMIT`, `LATE_OFFLINE_SYNC`, `SERVER_EXPIRY_SWEEP`, or `IN_PROGRESS`).
- **FR-004 (Strict RBAC Enforcement)**: `GET /api/v1/admin/exams/sessions/{sessionId}/script` and `GET /api/v1/admin/exams/sessions/{sessionId}/script/pdf` MUST permit `ROLE_ADMIN` and `ROLE_SUPERVISOR` (`200 OK`) and reject `ROLE_STUDENT` (`403 Forbidden`) and unauthenticated requests (`401 Unauthorized`).
- **FR-005 (TOAST-Safe Lazy Loading)**: List endpoints (`GET /api/v1/admin/analytics/results` and `/flagged-sessions`) MUST NOT serialize `snapshot_payload`; full snapshot loading MUST only occur on single-session primary-key audit requests.
- **FR-006 (Paginated & Filterable Audit UI)**: `ResultsPage.tsx` MUST provide **Inspect Script** and **Script (PDF)** controls on both the Flagged Sessions table and Exam Results table, rendering the Audit Modal with a 2-column metadata header (`grid-cols-2 gap-y-2`), subject tabs, outcome filters, and client-side pagination (`25 questions/page`).
