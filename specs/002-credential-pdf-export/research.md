# Research & Architectural Decisions: Printable Student Credential PDF Export (Slice 002)

## 1. Encryption Key Separation vs Reusing `JWT_SECRET`
- **Decision**: Introduce a dedicated property `security.credential-vault.key` mapped from `CREDENTIAL_ENCRYPTION_KEY`, with a non-production dev default in `application.yml` and strict validation (>=32 UTF-8 bytes, non-placeholder, distinct character check) in `ProductionSecurityValidator` when `@Profile("prod")` is active. Derive the 256-bit AES key deterministically via SHA-256 of the configured secret only inside `StudentPinCryptoService`.
- **Rationale**: Per [docs/secrets_management.md](file:///c:/Users/User/Desktop/studentprep/docs/secrets_management.md#L52-L54), `JWT_SECRET` must remain independently rotatable without breaking persisted encrypted data. Using a dedicated `CREDENTIAL_ENCRYPTION_KEY` avoids breaking stored student `pin_ciphertext` entries whenever JWT signing keys rotate, while still allowing local/CI environments to boot cleanly without breaking existing `.env` setups.
- **Alternatives Considered**:
  - *Encrypting directly with `JWT_SECRET`*: Rejected because rotating `JWT_SECRET` would immediately render all persisted student `pin_ciphertext` rows unreadable.
  - *Adding a separate KMS integration*: Rejected as unnecessary complexity for single-school and local deployments.

## 2. Cryptographic Scheme & Database Storage
- **Decision**: Use Java JCA `AES/GCM/NoPadding` with a fresh 12-byte `SecureRandom` IV per encryption and 128-bit authentication tag (`v1:<base64-iv>:<base64-ciphertext-plus-tag>`), stored in a nullable `TEXT` column `students.pin_ciphertext` (`V19__Add_Student_Pin_Ciphertext.sql`). Never return `pinCiphertext` from entity serialization (`@JsonIgnore`) or any DTO.
- **Rationale**: AES-256-GCM detects tampering or key mismatches reliably (`AEADBadTagException`), keeps ciphertext self-contained in a single column without separate IV columns, and ensures every PIN encryption yields unique ciphertext even if two candidates randomly draw the same 5-digit PIN. Authentication still verifies strictly against `users.pin_hash` (`Argon2id`)—never against `pin_ciphertext`.
- **Alternatives Considered**:
  - *Plaintext column with API redaction*: Rejected by Constitution Principle II (`No plaintext PIN/password storage`) because DB dumps or logs would directly expose live credentials.
  - *Detached file/Redis-only temporary PIN cache*: Rejected because server restarts or Redis flushes would break the user's requirement to reprint intact PINs anytime before or during exam day.

## 3. Lazy Auto-Provisioning for Legacy / Pre-Slice-002 Students
- **Decision**: If a student selected for bulk or single credential PDF export has `pinResetRequired == true` OR `pinCiphertext == null/blank` (e.g., the legacy `'12345'` accounts neutralized by `V18__Harden_Credentials_And_Pin_Reset.sql`), automatically generate a fresh 5-digit PIN inside a write transaction (`@Transactional`), update `pinHash` (`Argon2id`), encrypt into `pinCiphertext`, set `pinResetRequired = false`, evict stale Redis session/rate-limit keys, and publish `StudentPinResetEvent` so `users.pin_hash` synchronizes cleanly. Conversely, if a non-blank `pinCiphertext` exists and fails AEAD decryption due to a key mismatch, throw an explicit exception surfaced as an actionable error rather than silently changing active PINs.
- **Rationale**: Every active student on a printed exam roster PDF must have a usable login PIN, and this activates legacy `V18` accounts automatically on first roster export. Yet already-provisioned students never have their PINs changed on repeat downloads, and key misconfiguration errors fail loudly instead of silently invalidating distributed slips.
- **Alternatives Considered**:
  - *Printing `"Reset Required"` on the PDF*: Rejected because admins expecting a one-click printable school roster would still have to click Reset PIN individually 25+ times.
  - *Forcing a separate manual CLI/migration script*: Rejected as poor UX for single-school operators.

## 4. Bulk Cohort vs Full School PDF Endpoints & Pagination
- **Decision**: Provide two `ROLE_ADMIN`-only endpoints on `StudentAdminController`:
  1. `GET /api/v1/admin/students/credentials/pdf`: outputs all registered students ordered deterministically by `name ASC, registrationNumber ASC`.
  2. `POST /api/v1/admin/students/credentials/pdf` accepting JSON body `{ "registrationNumbers": [...] }`: renders the same consolidated OpenPDF roster strictly for that imported/selected batch using DB records and decrypted `pin_ciphertext` (never trusting client-submitted PINs).
  Both endpoints render an A4 OpenPDF `PdfPTable` with repeating column headers (`setHeaderRows(1)`), school/document header + print timestamp, candidate index (`#`), **Full Name**, **Registration Number**, and **Login PIN**.
- **Rationale**: `GET` delivers zero-argument 1-click download from the main Students toolbar (`<a / axios blob>`), while `POST` avoids URL length limits (`414 URI Too Long`) when downloading a batch PDF right after bulk CSV upload of hundreds of students.
- **Alternatives Considered**:
  - *Client-side jsPDF generation using raw frontend state*: Rejected because client tables don't have PINs for all students (`StudentResponse` never exposes PINs) and bypassing backend breaks centralized audit logging.
  - *Passing comma-separated IDs on `GET` query string*: Rejected because large CSV batches (500+ students) exceed standard browser/proxy query length limits.

## 5. Role Separation, Supervisor Isolation & Modulith Boundaries
- **Decision**: Register `/api/v1/admin/students/credentials/pdf` (`GET` and `POST`) in `SecurityConfig.java` under the explicit `.hasRole("ADMIN")` rules *before* the wildcard `GET /api/v1/admin/students/*` rule used for `ROLE_SUPERVISOR`, plus `@PreAuthorize("hasRole('ADMIN')")` on controller methods. Keep `StudentPinCryptoService` inside `com.studentprep.student` (no dependency from `security` into `student` crypto internals). Verify in `StudentsPage.tsx` and `StudentDetailModal.tsx` that bulk & single PDF download buttons render strictly when `isAdmin === true`.
- **Rationale**: Preserves Spring Modulith acyclic module verification (`ModulithArchitectureTest`), leverages `AuditLogAspect` automatically because paths match `/api/v1/admin/**`, and guarantees `ROLE_SUPERVISOR` receives `403 Forbidden`.
