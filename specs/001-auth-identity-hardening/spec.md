# Feature Specification: Authentication, Identity Integrity & Secrets Hardening

**Feature Branch**: `deployment`
**Spec Directory**: `specs/001-auth-identity-hardening`
**Created**: 2026-11-13
**Status**: Clarified
**Input**: User description: "001 — Slice 001: Auth, Identity & Secrets Hardening from StudentPrep deployment readiness baseline"

## Clarifications

### Session 2026-11-14

- Q1 (Onboarding & reset PIN issuance UX) → **Option A**: System generates a one-time random numeric PIN returned only once in the immediate creation, bulk-import batch receipt, or PIN-reset response (and immediate registration slip if generated at issuance), stored strictly in irreversible hashed form and never readable afterward.
- Q2 (Active `IN_PROGRESS` exam re-login policy) → **Option A**: Same-device auto-resume is permitted when an in-progress exam candidate reloads or reconnects on the same bound device; login from a different workstation while the exam session is active is blocked until an administrator clears/unlocks the active session lock.
- Q3 (Legacy credential migration policy) → **Option B**: Existing seeded administrator (`ADMIN-001`) uses an explicit configurable hashed bootstrap secret (failing/blocking weak or unset defaults in production), and legacy unhashed `'12345'` student accounts from prior deployments are invalidated/forced-reset before production exam login (with non-production E2E/dev fixtures seeded outside production migrations).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Irreversible Credential Storage & Reliable Student Account Lifecycle (Priority: P1)

Administrators create individual student accounts, bulk-import student cohorts, update student details, reset forgotten PINs, and delete or deactivate departing accounts. Students and administrators sign in using their assigned registration numbers or identifiers and secret PINs/passwords. At every stage, credentials must remain unreadable in storage, logs, exports, and subsequent views, while identity updates (new PINs, detail updates, deletions) must take effect consistently across sign-in and student management views.

**Why this priority**: Currently credentials are stored and exposed in readable plain text and globally reset to a shared weak default, while identity updates and deletions fail to synchronize properly—leaving old PINs active and deleted students able to log in. Nothing else in the platform can be deployed safely until account credentials and lifecycle synchronization are trustworthy.

**Independent Test**: Can be fully tested by registering a student, importing a batch, resetting a PIN, deleting another student, verifying sign-in works exclusively with the newest valid PIN and fails immediately for old PINs or deleted accounts, and inspecting stored/exported records and slips to confirm no reusable readable PINs remain.

**Acceptance Scenarios**:

1. **Given** an administrator registers a new student or imports students in bulk, **When** onboarding completes, **Then** the system generates a one-time random numeric PIN returned once in the immediate creation/import response (and immediate registration slip if generated in-session), stores only its irreversible hash, and never reveals it in student directories, subsequent exports, or system event logs.
2. **Given** an administrator resets a student's PIN or deletes a student account, **When** the action completes, **Then** the previous PIN immediately stops authenticating, deleted accounts immediately lose sign-in and active access rights, and only the newly issued PIN succeeds (for active accounts).
3. **Given** the system starts up or deploys updates in any environment, **When** startup completes, **Then** no pre-existing administrator or student PIN/password is silently overwritten with a hardcoded default value.

---

### User Story 2 - Seamless Session Renewal, Logout Integrity & Active-Exam Recovery (Priority: P1)

Students taking timed exams and administrators reviewing queues experience uninterrupted work even when short-lived session credentials expire mid-task, through automatic background renewal. When a candidate signs out or is displaced, only the displaced or explicitly ended session is terminated cleanly. Single-session exclusivity continues to protect against unauthorized concurrent logins without locking out legitimate students after browser reloads or workstation crashes.

**Why this priority**: Expired short-lived credentials currently fail with a hard authorization denial instead of triggering automatic renewal, unverified sign-out actions can wipe another active workstation's session, and legitimate candidates suffer exam-day lockouts if they need to reconnect during an active exam.

**Independent Test**: Can be fully tested by letting an active user's short-lived access credential expire and verifying automatic silent renewal continues the request, attempting unauthorized logout with an outdated token to verify the active workstation stays signed in, and testing reconnection scenarios during an in-progress exam.

**Acceptance Scenarios**:

1. **Given** an authenticated user whose short-lived access credential has expired while their renewal session remains valid, **When** they perform an action or periodic background sync, **Then** the system signals expiration clearly, renews credentials seamlessly in the background, and completes the user action without forcing a manual re-login.
2. **Given** a long-lived renewal credential is presented directly as an access credential (or vice versa), **When** an endpoint validates the request, **Then** the system rejects the misused credential type cleanly.
3. **Given** an outdated or evicted session calls sign-out, **When** sign-out is processed, **Then** the current legitimate active workstation's session remains intact.
4. **Given** a candidate has an exam in progress and attempts to sign in again, **When** credentials are verified, **Then** a reconnection from the same bound device resumes the active exam session seamlessly, whereas a sign-in attempt from a different workstation is blocked until an administrator explicitly unlocks/clears the active session lock.

---

### User Story 3 - Brute-Force Protection on Sign-In & Renewal (Priority: P1)

Repeated failed sign-in attempts or automated PIN-guessing requests against candidate and administrator accounts are automatically detected and throttled or temporarily blocked, preventing attackers on the exam network from enumerating 5-digit PINs.

**Why this priority**: A 5-digit numeric PIN space contains only 90,000–100,000 combinations and currently has zero request throttling on authentication endpoints, making full brute-force compromise trivial in minutes.

**Independent Test**: Can be tested by submitting rapid invalid sign-in or renewal requests and confirming requests beyond the threshold are blocked cleanly with retry guidance while legitimate users can still sign in normally within limits.

**Acceptance Scenarios**:

1. **Given** an account or client sends repeated invalid sign-in attempts exceeding the threshold within a short window, **When** subsequent login attempts arrive, **Then** the system rejects further attempts with a clear temporary lock/rate-limit notice without exhausting server resources.
2. **Given** repeated invalid or abusive requests target the session renewal endpoint, **When** the threshold is exceeded, **Then** requests are throttled similarly without breaking normal single-user background renewal.

---

### User Story 4 - Upload Validation, Internal Service Auth & Secret Confidentiality (Priority: P2)

Authorized administrators upload past-question documents and diagrams safely without allowing dangerous active files (such as executable scripts or web markup) into publicly accessible media storage. Internal document-parsing services verify caller identity, handle malformed or oversized files without crashing or leaking disk storage, third-party AI integration secrets never appear in admin-visible error messages or logs, browser cross-origin access obeys configured allow-lists, and development-only helper utilities or debug dumps are excluded from deployable artifacts.

**Why this priority**: Publicly readable unvalidated uploads create persistent script-injection risks, unauthenticated document parsing exposes compute and disk exhaustion risks, and failed AI requests currently persist secret keys directly into readable ingestion error fields.

**Independent Test**: Can be tested by submitting disallowed file types/oversized uploads, calling the document-parsing service without valid authorization, simulating an upstream AI error and verifying stored job error messages contain no secret keys, verifying browser requests from unauthorized origins are rejected, and inspecting build outputs for zero development/debug files.

**Acceptance Scenarios**:

1. **Given** an administrator or attacker attempts to upload a disallowed file type (such as HTML, SVG, or script files) or an oversized upload, **When** upload validation runs, **Then** the system rejects the file cleanly with a clear validation error and stores nothing in public media storage.
2. **Given** a request arrives at the internal document-parsing service without valid internal service credentials, **When** the request is checked, **Then** access is refused cleanly; when valid authorized uploads fail or finish, temporary workspace files are always reclaimed safely.
3. **Given** external AI question structuring fails during ingestion, **When** the failure reason is recorded and viewed in the admin ingestion queue, **Then** zero API keys, URL secret parameters, or sensitive host credentials appear in stored messages or responses.
4. **Given** browser requests arrive from unauthorized external domains, **When** cross-origin rules evaluate the request, **Then** only configured trusted client origins are permitted, and credential cookies honor environment transport-security settings.
5. **Given** production packages and repository configuration are built for release, **When** build artifacts and tracked config files are inspected, **Then** zero committed secrets, hardcoded dev credential generators, debug JSON/PDF dumps, or stale workbox dev artifacts are shipped.

### Edge Cases

- What happens to existing seeded administrator (`ADMIN-001`) and pre-existing student accounts created under legacy unhashed deployments when upgrading? Seeded administrator bootstrap uses an explicitly configured hashed secret (blocking startup/provisioning in production if unset or weak), and legacy unhashed `'12345'` student accounts are invalidated/marked for forced PIN reset before production exam login (while non-production E2E/dev fixtures remain isolated outside production migrations).
- What happens if an administrator submits bulk student onboarding with duplicate registration numbers or invalid input formats? The system must reject invalid records deterministically without exposing credentials or crashing partial valid imports.
- What happens if an unauthenticated user submits a logout request with no session token? Clearing local client cookies succeeds safely without disturbing any other user's active server session.
- What happens if the document parser receives a file stream missing a filename or exceeding safe memory/size caps? It rejects the upload gracefully with a bounded error instead of leaving dangling temp files or exhausting memory.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST store every administrator and student password/PIN exclusively using irreversible adaptive cryptographic hashing and MUST reject plaintext credential storage or comparison.
- **FR-002**: System MUST synchronize every student creation, profile update, PIN reset, and deletion or deactivation immediately and consistently across authentication and administrative management records.
- **FR-003**: System MUST generate random one-time numeric student PINs upon individual creation, bulk import, and explicit PIN reset—returning the plaintext PIN only once in that immediate issuance response (and immediate registration slip if generated in-session)—while storing only the irreversible hash and never exposing readable PINs in student directories, subsequent re-exports, or event logs.
- **FR-004**: System MUST distinguish missing/expired access credentials from insufficient permissions so clients trigger automatic silent session renewal cleanly instead of failing with a hard permission error.
- **FR-005**: System MUST enforce strict separation between access credentials and renewal credentials so neither credential type can be accepted in place of the other.
- **FR-006**: System MUST verify active session ownership before revoking server-side session state during sign-out, ensuring expired, evicted, or unauthenticated sign-out requests never terminate a valid active session on another workstation.
- **FR-007**: System MUST allow a candidate with an in-progress exam to resume automatically when signing in from the same bound device, while blocking sign-in from a different workstation until an administrator explicitly unlocks/clears the active session lock.
- **FR-008**: System MUST apply rate limiting and temporary lockout/throttling to both authentication sign-in and session-renewal requests to protect against brute-force guessing across numeric PINs.
- **FR-009**: System MUST restrict browser cross-origin access to explicitly configured trusted origins and set secure transport flags on renewal cookies from environment configuration.
- **FR-010**: System MUST validate uploaded document and diagram files by permitted file type/signature and maximum size before persisting to object storage, blocking active web/script types.
- **FR-011**: System MUST require shared secret authorization on the internal document-parsing service, enforce bounded file-size limits, sanitize error details, and guarantee temporary file cleanup on all success and failure paths.
- **FR-012**: System MUST prevent external AI service API keys and sensitive query parameters from entering logs, database error columns, or administrative status responses.
- **FR-013**: Repository and deployable artifacts MUST contain zero committed live secrets, unauthenticated developer password utilities, or test debug dumps, and MUST neutralize the effects of legacy unhashed migration resets via forward-only hardening so zero accounts retain `'12345'` or plaintext credentials; upgraded production deployments MUST bind administrator bootstrap (`ADMIN-001`) to an explicitly configured hashed secret (refusing weak/unset defaults in production) and invalidate legacy `'12345'` student accounts for forced admin PIN reset, keeping non-production test fixtures outside production migrations.

### Key Entities

- **Account Identity**: Represents a candidate or staff principal able to sign in; uniquely identified by registration number/identifier, assigned role, active status, and irreversibly hashed credential.
- **Active Workstation Session**: Represents the currently authorized workstation binding for a single identity, tracking active session token identifier, expiration window, and explicit revocation state.
- **Ingested Asset & Parse Job**: Represents uploaded PDFs, extracted diagram media, and background structuring status, tracking sanitized error summaries and validated safe media metadata without retaining secret credentials.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of stored user/student credentials and 100% of administrative directory views, exports, and error/event logs contain zero readable PINs, passwords, or external API keys after deployment.
- **SC-002**: 100% of PIN resets and account deletions take effect immediately on the next sign-in attempt (0% of old PINs or deleted students continue authenticating).
- **SC-003**: 100% of authenticated requests using an expired short-lived session credential (with a valid renewal credential) complete transparently without forcing manual re-login.
- **SC-004**: 100% of automated brute-force guessing bursts exceeding configured thresholds are blocked before reaching 1% of the 5-digit numeric PIN space.
- **SC-005**: 100% of disallowed active web-file uploads (e.g., HTML, SVG), unauthorized parser service calls, and disallowed cross-origin credentialed requests are rejected prior to storage or execution.

## Assumptions

- Target production and staging deployments run over HTTPS behind a reverse proxy, while local development over HTTP remains configurable via explicit environment flags without weakening production defaults.
- Candidate authentication continues to use registration number plus numeric PIN so candidates experience realistic exam-center login flows, compensated by adaptive hashing plus strict brute-force throttling.
- Reversing or removing hardcoded test keys requires rotating any previously exposed key outside the codebase during environment setup.
