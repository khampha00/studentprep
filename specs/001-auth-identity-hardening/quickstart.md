# Quickstart & Verification Guide: Slice 001 — Auth, Identity & Secrets Hardening

**Feature**: `specs/001-auth-identity-hardening/spec.md` | **Prerequisites**: Java 17, Maven (`mvn`), Node.js + npm, Python 3.11+ (with `pytest` & `httpx` for sidecar tests), Docker Compose (`postgres`, `redis`, `minio`, `docling-parser`).

## 1. Environment Preparation (Pre-Run)

1. Ensure `.env` exists locally (copied from `.env.example`, never committed) with:
   - High-entropy `JWT_SECRET` (>= 32 characters, distinct from `.env.example`)
   - Explicit `ADMIN_BOOTSTRAP_SECRET` (non-weak, >= 12 chars in `prod`, e.g., distinct dev value locally)
   - `DOCLING_API_KEY` matching both backend and `docling-parser` container
   - `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000`
   - `COOKIE_SECURE=false` for local HTTP (`true` in HTTPS staging/production) and `COOKIE_SAME_SITE=Strict`
   - `FLYWAY_BASELINE_ON_MIGRATE=false`
2. Rotate any external `LLM_API_KEY` previously present in the local environment before sharing credentials externally.

## 2. Infrastructure & Migration Verification

1. Start dependencies:
   ```powershell
   docker compose up -d postgres redis minio docling-parser
   ```
2. Verify `V18__Harden_Credentials_And_Pin_Reset.sql` applies cleanly and neutralizes legacy `'12345'` credentials while keeping `validate-on-migrate: true`.
3. Verify `prod` profile fails fast when started with missing/weak `JWT_SECRET`, `minioadmin` S3 keys, wildcard CORS, or weak `ADMIN_BOOTSTRAP_SECRET`.

## 3. Test & Validation Suite Execution

1. **Backend Modulith + Security + Identity Integration Suite** (from `backend/`):
   ```powershell
   mvn test
   ```
   Confirms `ModulithArchitectureTest`, `Argon2id` credential hashing (no plaintext fallback), immediate invalidation of old PINs/deleted accounts, `401` on expired JWTs, strict `access` vs `refresh` `token_type`, `jti`-verified logout, same-device `IN_PROGRESS` exam resume vs cross-workstation `409` block + admin `/unlock-session` recovery, auth brute-force `429` throttling, CORS allow-list, CSV formula neutralization + PIN omission, safe S3 raster upload validation, and error-message key redaction.
2. **Python Docling Sidecar Tests** (from `services/docling-parser/`):
   ```powershell
   pytest
   ```
   Confirms `X-API-Key` enforcement, missing-filename handling, size cap (`413`), `%PDF-` magic-byte rejection, error redaction, and deterministic temp-file cleanup.
3. **Frontend Lint, Build & Playwright E2E** (from `frontend/`):
   ```powershell
   npm run lint
   npm run build
   npx playwright test
   ```
   Confirms no plaintext PINs or `'12345'` fallbacks remain in Students directory/detail views, silent `401` token refresh restores active sessions transparently, evicted sessions do not kill active sessions, one-time PIN slip creation/reset UI renders cleanly, and live Playwright flows pass on Chromium and WebKit.
4. **Hygiene & Packaging Verification** (from repo root):
   ```powershell
   git ls-files | Select-String "BcryptGen|HashGen|fix\.sql|parsed_pdf|test00\.pdf|update\.cjs|test-circ|test-katex|test-sqrt|dev-dist|playwright-report|test-results|\.env$"
   ```
   Expect zero matches (plus `git diff --check`).

For detailed endpoint schemas and table/Redis contracts, see [auth-and-student-api.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/contracts/auth-and-student-api.md), [ingestion-and-docling-api.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/contracts/ingestion-and-docling-api.md), and [data-model.md](file:///c:/Users/User/Desktop/studentprep/specs/001-auth-identity-hardening/data-model.md).
