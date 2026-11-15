# Tasks: Exam Script Audit, Cryptographic Answer Receipt & Printable Verification Report (`004-exam-script-audit`)

## Phase 1: SDD Documentation & Contracts
- [x] `T001` Author `specs/004-exam-script-audit/spec.md`, `plan.md`, `contracts/api-exam-script-audit.md`, and `tasks.md`.
- [x] `T002` Update `.specify/memory/constitution.md` and `docs/api_contract.md` with `/api/v1/admin/exams/sessions/{sessionId}/script`, `/script/pdf`, and `answerReceiptCode` contracts.

## Phase 2: Shared Cryptographic Answer Receipt & Student Result Integration
- [x] `T003` Create `AnswerReceiptHasher` in `com.studentprep.common` (`computeReceiptCode` and `computeSha256`) and expose `getSessionAnswerReceiptCode(UUID sessionId)` on `ExamInternalAPI` / `ExamInternalAPIImpl`.
- [x] `T004` Include `answerReceiptCode` on `StudentResultService` DTOs (`LatestStudentResultDto`, `StudentResultDto`), `ExamResultSummaryService.ExamResultSlipData`, and `ExamResultSlipGenerator` PDF output.

## Phase 3: Backend Script Audit Service, Provenance & RBAC
- [x] `T005` Implement `ExamScriptAuditService` in `com.studentprep.exam` with snapshot question reconstruction, `answerReceiptCode` + `answerSha256`, `lastClientSyncAt` + `submissionTrigger` provenance extraction, and OpenPDF A4 report rendering.
- [x] `T006` Expose `GET /api/v1/admin/exams/sessions/{sessionId}/script` and `GET /api/v1/admin/exams/sessions/{sessionId}/script/pdf` in `AdminExamModerationController` and configure `hasAnyRole("ADMIN", "SUPERVISOR")` in `SecurityConfig`.

## Phase 4: Frontend Paginated Audit Modal, PDF Download & Student Receipt Badge
- [x] `T007` Add `buildExamScriptFilename` to `frontend/src/lib/pdf.ts` and display `answerReceiptCode` on `frontend/src/pages/student/ResultPage.tsx`.
- [x] `T008` Update `frontend/src/pages/admin/ResultsPage.tsx` with **Inspect Script** and **Script (PDF)** actions (in both Flagged Sessions and Exam Results tables) and the paginated (`25/page`), filterable **Exam Script Audit Modal**.

## Phase 5: Verification Gates
- [x] `T009` Add backend unit & integration tests in `ProctoringAndModerationIntegrationTest` covering script JSON audit, PDF download, `answerReceiptCode` parity, provenance metadata, and RBAC (`ADMIN` `200`, `SUPERVISOR` `200`, `STUDENT` `403`), and run `mvn -f backend/pom.xml test` (`140/140` passing).
- [x] `T010` Run `npm --prefix frontend run lint` (`0` errors) and `npm --prefix frontend run build` (`dist/sw.js` generated with `87` precached entries).
