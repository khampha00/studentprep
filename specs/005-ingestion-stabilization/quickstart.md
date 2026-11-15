# Quickstart Verification Guide: Slice 005 (`005-ingestion-stabilization`)

Run all checks after implementing Slice 005 tasks against PostgreSQL, Redis, MinIO, and Docling (`docker compose up -d`).

## 1. Backend Modulith, Unit & Integration Tests
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
mvn -f backend/pom.xml test
```
Expected highlights:
- `PostProcessTest` & `AsyncIngestionWorkerTest` verify `is_follow_up: false` clears `activeContext`, job-scoped context linking leaves unrelated uploads untouched, `LlmStructuringService` salvages trailing `questionNumber` objects, and startup sweep recovers stuck `PENDING`/`PROCESSING` jobs.
- `QuestionServiceTest` verifies `relinkContextsForSubject` respects `is_follow_up`, `SubjectController` returns `409 Conflict` when questions exist and `200 OK` when only historical jobs/orphan contexts exist, and `QuestionController` validates `QuestionUpdateRequest`.
- `AuditLogAspect` test verifies routine admin `GET` polls write `0` `audit_logs` rows while mutations and `/script` / `/pdf` exports write `1` row with `<= 255` char endpoints.
- `ModulithArchitectureTest` passes with zero cross-module violations.

## 2. Docling Python Sidecar Tests
```bash
cd services/docling-parser
pytest -v
```

## 3. Frontend Lint & Production Build
```powershell
npm --prefix frontend run lint
npm --prefix frontend run build
```
Expected: `oxlint` reports `0` errors and `0` warnings in modified files; `tsc -b && vite build` succeeds cleanly.

## 4. Full Unmocked Playwright E2E Suite (Chromium & WebKit)
With Docker services and Spring Boot backend running:
```powershell
npx --prefix frontend playwright test tests/auth-hardening.spec.ts tests/exam-kiosk-integrity.spec.ts tests/ingestion-and-audit-stabilization.spec.ts --reporter=list
```
Verify:
- `auth-hardening`, `exam-kiosk-integrity`, and `ingestion-and-audit-stabilization` (covering Exam Script Audit Modal/PDF from Slice 004 + Results pagination, Subject Detail `AlertDialog`/`Select`/draft edits, and safe Subject deletion) pass on both Chromium and WebKit.
