# Quickstart & Validation Guide: Printable Student Credential PDF Export (Slice 002)

## Prerequisites
1. Local PostgreSQL (`5433`), Redis (`6379`), and MinIO (`9000`) running via `docker compose up -d`.
2. Environment variables configured in `.env`:
   - `CREDENTIAL_ENCRYPTION_KEY`: optional in non-`prod` dev (falls back to dev key in `application.yml`); required non-placeholder `>= 32` byte secret when `SPRING_PROFILES_ACTIVE=prod`.
   - `COOKIE_SECURE=false` and `ADMIN_BOOTSTRAP_SECRET` set for local HTTP browser verification.
3. Spring Boot backend running (`cd backend && mvn spring-boot:run`) with Flyway migration `V19__Add_Student_Pin_Ciphertext.sql` applied automatically.
4. Frontend Vite dev server running (`cd frontend && npm run dev`).

## End-to-End Verification Scenarios

### Scenario 1: 1-Click All-Student Bulk Roster PDF + Repeat Stability
1. Sign in at `http://localhost:5173/admin` as `ADMIN-001` (`ROLE_ADMIN`).
2. Navigate to **Students** (`#/admin/students` or `/admin/students`).
3. Click **"Download All Credentials (PDF)"** in the page header toolbar.
4. Verify a single PDF (`student_credentials_roster.pdf`) downloads containing every student's **Full Name**, **Registration Number**, and **5-digit Login PIN**.
5. Pick any legacy or newly registered student from the PDF and log in at `http://localhost:5173/` using their `Registration Number` and printed `PIN`; verify HTTP `200` sign-in succeeds.
6. Return to the Admin portal and click **"Download All Credentials (PDF)"** a second time; verify the student's printed `PIN` matches the first PDF identically and continues signing in.

### Scenario 2: Single-Student Credential Slip PDF + Pin Reset Sync
1. From the **Students** table (or inside **View Profile**), click the single-student **Slip / Credential PDF** download button for a specific student.
2. Verify `${registrationNumber}-slip.pdf` contains **Candidate Name**, **Registration Number**, **Registered Subjects**, and the same **Login PIN** shown in the bulk PDF.
3. Click **Reset PIN** on that student, note the new PIN, and re-download both the single-student slip PDF and bulk roster PDF; verify both PDFs print the updated PIN and the old PIN fails login.

### Scenario 3: Immediate Bulk CSV Cohort PDF
1. Click **Bulk Register CSV** on **Students** and upload a valid CSV batch.
2. In the import result view, click **"Download Batch Credentials (PDF)"** (`POST /api/v1/admin/students/credentials/pdf`).
3. Verify the downloaded PDF (`batch_credentials_roster.pdf`) lists all newly imported candidates with matching **Name**, **Registration Number**, and **PIN**.

### Scenario 4: Role Boundary & Privacy Checks
1. Sign in as `SUPERVISOR-001` (`ROLE_SUPERVISOR`) and open **Students**. Verify bulk and single credential PDF buttons are hidden, and direct `GET /api/v1/admin/students/credentials/pdf`, `POST /api/v1/admin/students/credentials/pdf`, and `GET /api/v1/admin/students/{id}/slip` return `403 Forbidden`.
2. Download CSV via **Export Roster (CSV)** (`GET /api/v1/admin/students/export`) and inspect `GET /api/v1/admin/students` JSON; verify neither includes PINs or ciphertexts.
3. Verify `audit_logs` records each `ROLE_ADMIN` PDF download action.

## Automated Gate Commands
- Backend tests (unit, crypto, OpenPDF parsing, Modulith boundary, & live integration):
  ```powershell
  cd backend
  mvn test
  ```
- Frontend lint, typecheck & production build:
  ```powershell
  cd frontend
  npm run lint
  npm run build
  ```
- Unmocked Playwright E2E suite:
  ```powershell
  cd frontend
  npx playwright test
  ```
