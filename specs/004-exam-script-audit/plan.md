# Implementation Plan: Exam Script Audit, Cryptographic Answer Receipt & Printable Verification Report

**Branch**: `deployment` (Slice `004-exam-script-audit`) | **Date**: 2026-11-15 | **Spec**: [spec.md](file:///c:/Users/User/Desktop/studentprep/specs/004-exam-script-audit/spec.md)

## Summary

Slice 004 adds end-to-end Exam Script Auditing and Printable Verification Reports for Administrators (`ROLE_ADMIN`) and Supervisors (`ROLE_SUPERVISOR`) while resolving all 5 architectural bottlenecks:
1. **Question Bank Leakage Prevention**: Restricts `/api/v1/admin/exams/sessions/{sessionId}/script` and `/script/pdf` strictly to `ROLE_ADMIN` and `ROLE_SUPERVISOR` (`403 Forbidden` for `ROLE_STUDENT`).
2. **Cryptographic Non-Repudiation (`AnswerReceiptHasher`)**: Computes a deterministic SHA-256 Answer Receipt Code (`XXXX-XXXX-XXXX` + 64-hex digest) over `sessionId` and canonical sorted `questionId=selectedOption` pairs. Surfaces this receipt code on the student's `/result` screen, student Result Slip PDF, and the Admin/Supervisor Audit Modal & Audit PDF.
3. **Sync & Expiry Provenance**: Extracts `lastClientSyncAt` (`state_payload.lastUpdated`), `expectedEndTime`, `endTime`, and `submissionTrigger` (`MANUAL_SUBMIT`, `TIMER_AUTO_SUBMIT`, `MALPRACTICE_AUTO_SUBMIT`, `LATE_OFFLINE_SYNC`, `SERVER_EXPIRY_SWEEP`) so supervisors can diagnose offline/expiry sync gaps.
4. **PostgreSQL TOAST & Browser KaTeX Performance**: Fetches `snapshot_payload` strictly by primary key (`findById(sessionId)`) on single-session audit requests, and paginates `<RichText />` question rendering (`25 questions/page`) with subject/outcome filters in the React Audit Modal.
5. **Spring Modulith Boundary Preservation**: Places `AnswerReceiptHasher` in `com.studentprep.common` and `ExamScriptAuditService` in `com.studentprep.exam`, introducing zero circular or illegal cross-module dependencies.

## Constitution Check

1. **Principle I (Modular Monolith & Asynchronous Outbox Integrity)**: PASS. `exam` uses `common` (`PdfStyle`, `AnswerReceiptHasher`) and `student` (`StudentInternalAPI`); `analytics` uses `exam` (`ExamInternalAPI`) and `common` (`AnswerReceiptHasher`). Verified by `ModulithArchitectureTest`.
2. **Principle II (Zero-Trust Security, Identity & Secrets Hygiene)**: PASS. `ROLE_ADMIN` and `ROLE_SUPERVISOR` are authorized for read-only exam script inspection and audit PDF export to support floor-level dispute resolution, while destructive moderation (`clear-late`, `void`, `grant-retake`) and credential exports remain `ROLE_ADMIN`-only. `ROLE_STUDENT` is blocked (`403`).
3. **Principle III (Exam, Snapshot & Grading Integrity)**: PASS. Reconstructs scripts strictly from `exam_sessions.snapshot_payload` and `state_payload`.
4. **Principle IV (Shared-Kiosk Isolation & Offline-First Resilience)**: PASS. No student data is cached in browser storage by the admin/supervisor modal.
5. **Principle V (Contract-First API & Test-First Verification)**: PASS. Documented in `contracts/api-exam-script-audit.md` and `docs/api_contract.md`, verified by JUnit integration tests and Playwright E2E tests.
6. **Principle VI (Code Quality, Schema & UI Discipline)**: PASS. Explicit imports, constructor injection, zero inline FQNs, SLF4J logging, Inter font, `bg-slate-50`, `#008751`, and `grid-cols-2 gap-y-2` detail layout.

## Source Code Layout Affected

```text
backend/src/main/java/com/studentprep/
├── common/
│   └── AnswerReceiptHasher.java                # Canonical SHA-256 answer receipt code + hash generator
├── exam/
│   ├── ExamScriptAuditService.java             # Snapshot script reconstruction, provenance extraction, & OpenPDF renderer
│   ├── AdminExamModerationController.java      # GET /exams/sessions/{sessionId}/script & /script/pdf (ADMIN + SUPERVISOR)
│   ├── ExamInternalAPI.java                    # Expose getSessionAnswerReceiptCode(UUID sessionId) for analytics
│   └── ExamInternalAPIImpl.java                # Implement receipt lookup from ExamSession state_payload
├── analytics/
│   ├── ExamResultSummaryService.java           # Include answerReceiptCode on ExamResultSlipData
│   ├── ExamResultSlipGenerator.java            # Print Answer Receipt Code on candidate Result Slip PDF
│   └── StudentResultService.java               # Include answerReceiptCode on LatestStudentResultDto & StudentResultDto
├── security/
│   └── SecurityConfig.java                     # Permit GET /api/v1/admin/exams/sessions/*/script and /script/pdf for ADMIN, SUPERVISOR
frontend/src/
├── lib/
│   └── pdf.ts                                  # Add buildExamScriptFilename helper
├── pages/
│   ├── admin/
│   │   └── ResultsPage.tsx                     # Inspect Script & Script (PDF) buttons + paginated Exam Script Audit Modal
│   └── student/
│       └── ResultPage.tsx                      # Display tamper-evident Answer Receipt Code badge on /result
```
