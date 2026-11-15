# Quickstart & Verification Guide: Slice 003 (`003-exam-kiosk-integrity`)

## Prerequisites
1. Running infrastructure containers (`docker compose up -d postgres redis minio docling-parser`).
2. `JAVA_HOME='C:\Program Files\Java\jdk-17'`.
3. Clean builds & dependencies (`mvn -f backend/pom.xml`, `npm --prefix frontend install` already present).

## Automated Verification Suite
1. **Backend Unit + Spring Boot Live Postgres/Redis Integration Tests**:
   - Run full backend test suite (including `ModulithArchitectureTest`, `ExamIntegrityAndSnapshotIntegrationTest`, `OutboxRecoveryAndCacheFallbackIntegrationTest`):
     ```powershell
     $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
     mvn -f backend/pom.xml test
     ```
   - Expected: 100% pass, `0` skipped, verifying:
     - `V22` clean migration on live PostgreSQL (including `UNIQUE(exam_session_id)` and repaired `V2`/`V4` seed rows with zero nested `correctOption` leakage).
     - Mid-exam Question Bank edit/archive does not alter frozen `ExamSession` payload or graded `ExamResult`.
     - Concurrent `/active/session` poll on expired exam causes zero `readOnly` `500` errors.
     - Concurrent `/sync` + `/submit` race creates at most one `ExamResult`.
     - Server deadline rejections (`410`) for post-grace `/sync` and post-15m `/submit`; `LATE_SUBMISSION_FLAGGED` acceptance within 15m offline window; `409` on repeat `/start`; `403` on `FLAGGED_TAB_SWITCH` with immediate Redis token revocation, zero `ExamResult`, and blocked slips.
     - `ROLE_ADMIN` `clear-late`, `void`, and `grant-retake` transitions + `ROLE_SUPERVISOR` `403` enforcement.
     - Outbox recovery (`OutboxRecoveryScheduler`) and fail-open Redis fallback when Redis throws `DataAccessException`.

2. **Frontend Quality & PWA Precaching Verification**:
   ```powershell
   npm --prefix frontend run lint
   npm --prefix frontend run build
   ```
   - Inspect built `frontend/dist/sw.js` precache manifest for `.woff2`, `.woff`, `.ttf`, `animal-cell-diagram.svg`, `linear-graph.svg`, and PWA icons, with zero external CDN requests in `frontend/dist/index.html`.

3. **Unmocked End-to-End Playwright Verification (Chromium + WebKit)**:
   - With backend running (`:8080`) + frontend (`vite` or preview `:5173`):
     ```powershell
     npx --prefix frontend playwright test
     ```
   - Expected: All specs in `auth-hardening.spec.ts` and `exam-kiosk-integrity.spec.ts` pass on both Chromium and WebKit, verifying shared-kiosk isolation (Student A -> Student B zero leakage), offline exam reload/answer/queue/reconnect flow, and Admin Dashboard "Recent Completed Exams" + moderation actions.
