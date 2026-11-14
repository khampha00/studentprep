# Feature Specification: Printable Student Credential PDF Export

**Feature Branch**: `deployment`
**Spec Directory**: `specs/002-credential-pdf-export`
**Created**: 2026-11-14
**Status**: Approved for Planning
**Input**: User request: "in my university, the student credentials are printed in pdf, meaning list of all registered student, containing student name, registration number and pin. so what i want is, functionality to download one pdf file listing these details with one click, while also having the capability to download for one student."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One-Click Bulk Student Credential Roster PDF (Priority: P1)

Exam coordinators and school administrators preparing for CBT examination sessions need to print and distribute login credentials for an entire cohort or school in one step. From the student management dashboard (as well as immediately after bulk CSV onboarding), an administrator clicks a single action to download one consolidated printable PDF listing every registered student's **Full Name**, **Registration Number**, and **Login PIN**. Re-downloading the roster PDF—for example, after a printer jam or to print extra hall sheets—produces the exact same active PINs without resetting or invalidating previously distributed slips.

**Why this priority**: Manually recording or resetting individual student PINs one by one during school registration is impractical for an entire exam hall cohort, and forcing PIN rotation on every reprint disrupts distributed slips.

**Independent Test**: Can be fully tested by registering several students individually and via bulk CSV import, clicking the one-click bulk credential PDF download, verifying the generated PDF lists every candidate's name, registration number, and working 5-digit PIN, logging in with a printed PIN from that PDF, re-downloading the PDF a second time, and confirming the PINs remain identical and continue authenticating.

**Acceptance Scenarios**:

1. **Given** multiple students are registered in the school directory, **When** an administrator clicks the one-click bulk credential PDF download button, **Then** the system generates and downloads a single paginated PDF file containing a table/roster of all registered students with each candidate's full name, registration number, and active login PIN.
2. **Given** an administrator has already downloaded the bulk credential PDF and distributed slips to candidates, **When** the administrator downloads the bulk credential PDF again, **Then** every previously issued candidate retains the exact same PIN without disruption.
3. **Given** the directory contains legacy or pre-upgrade student accounts that currently lack a retrievable PIN (such as accounts flagged as requiring a PIN reset), **When** an administrator downloads the bulk credential PDF, **Then** the system automatically issues and activates a random 5-digit PIN once for each unissued student, includes those newly active PINs in the downloaded PDF, and keeps them stable on future downloads.
4. **Given** an administrator finishes a bulk CSV upload of new students, **When** the import summary appears, **Then** the administrator can immediately download a batch credential PDF for those newly registered students with one click (in addition to the CSV receipt).

---

### User Story 2 - One-Click Single-Student Credential PDF Slip (Priority: P1)

When an individual student registers late, misplaces their physical slip, or requests confirmation at the exam help desk, an administrator needs to download a single candidate's individual printable PDF slip containing their **Candidate Name**, **Registration Number**, **Registered Subjects**, and **Login PIN** with one click. Downloading the slip does not rotate or invalidate the candidate's current PIN unless the administrator explicitly clicks **Reset PIN**.

**Why this priority**: Individual help-desk reprints are the most common exam-day administrative request alongside batch roster printing.

**Independent Test**: Can be tested by opening the student directory or profile view for a specific candidate, downloading their single-student PDF slip, verifying it contains their name, registration number, and valid login PIN (matching the bulk roster PDF), and signing in as that candidate with the printed PIN.

**Acceptance Scenarios**:

1. **Given** an active student exists in the directory, **When** an administrator clicks the single-student credential slip download button, **Then** the system downloads a single printable PDF slip containing that candidate's name, registration number, enrolled subjects, and working login PIN.
2. **Given** the same student's single-student PDF slip is downloaded repeatedly without resetting their PIN, **When** the downloaded slips are compared and tested against student sign-in, **Then** the printed PIN remains unchanged and continues authenticating cleanly.
3. **Given** a candidate had no retrievable PIN yet (legacy reset-required account), **When** an administrator downloads their single-student PDF slip, **Then** the system automatically provisions and activates a new random 5-digit PIN once and prints it on the downloaded slip.

---

### User Story 3 - Role Protection, Directory Privacy & Audit Synchronization (Priority: P2)

Credential lifecycle actions (creation, bulk import, explicit PIN reset, and deletion) must stay synchronized across login authentication and credential PDFs while ensuring non-admin roles and non-credential exports never expose secret PINs.

**Why this priority**: Even in a single-school deployment, supervisors and students must remain isolated from bulk credential lists, general tables/CSV exports must stay clean, and explicit PIN resets must take effect immediately across both login and printed slips.

**Independent Test**: Can be tested by calling bulk and single credential PDF actions as an administrator (succeeds and generates audit records), as a supervisor or student (denied), resetting a student's PIN and downloading both single and bulk PDFs (shows newest PIN while old PIN stops working), and inspecting standard student directory views and CSV roster exports (no PINs included).

**Acceptance Scenarios**:

1. **Given** an administrator explicitly resets a student's PIN, **When** the reset completes, **Then** the candidate's previous PIN stops working for sign-in immediately and both future single-student and bulk credential PDF downloads print the newly issued PIN.
2. **Given** a student is deleted from the directory, **When** an administrator downloads the bulk credential PDF or attempts to download that student's slip, **Then** the deleted student does not appear in the bulk PDF and single-slip retrieval fails cleanly.
3. **Given** an unauthenticated user, student account, or read-only supervisor attempts to download either the bulk credential PDF or a single-student credential PDF, **When** access is evaluated, **Then** the request is denied with zero credential exposure, and no credential download UI actions are offered to supervisors.
4. **Given** an administrator downloads either the bulk credential PDF or a single-student credential PDF, **When** the download finishes, **Then** the action is recorded in the administrative audit trail, while standard student directory responses and CSV roster exports continue omitting PINs.

### Edge Cases

- **Empty student directory**: When zero students exist in the directory, requesting the bulk credential PDF still downloads a valid single-page PDF cleanly indicating zero registered students rather than failing with a server error.
- **Large school cohort pagination**: When hundreds or thousands of students are exported into the single bulk credential PDF, rows flow cleanly across multiple pages with repeated table headers, deterministic alphabetical/registration-number ordering, and zero row clipping.
- **Inactive vs active exam session**: Downloading a bulk or single credential PDF for an existing active candidate does not terminate or evict any in-progress exam session (since existing PINs are preserved rather than rotated).
- **Special characters or long candidate names**: Candidate names or comma/quote characters in profile fields render cleanly in PDF tables without breaking layout or overlapping adjacent registration-number/PIN columns.
- **Explicit credential key mismatch or corruption**: If a stored retrievable credential cannot be decoded under the active server key configuration, the system fails cleanly with a clear actionable error telling the administrator to trigger PIN reset rather than silently outputting corrupt text or rotating all pins unexpectedly.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST store student login PINs in a reversible encrypted form strictly protected by a server-side secret **alongside** (never replacing) strong one-way verification hashes used during sign-in; plaintext PIN columns and non-hashed authentication remain strictly prohibited.
- **FR-002**: Administrator and supervisor account credentials MUST remain strictly one-way hashed and MUST NEVER be stored in reversible form or included in any PDF export.
- **FR-003**: System MUST provide a one-click administrator action on the student management page to download a single consolidated PDF file listing all registered students with at minimum each candidate's Full Name, Registration Number, and Login PIN (with clean page headers and pagination).
- **FR-004**: System MUST provide a one-click administrator action for bulk CSV import results to download a batch credential PDF listing only the newly imported students' Names, Registration Numbers, and Login PINs.
- **FR-005**: System MUST update the single-student PDF slip download so authorized administrators receive a printable slip containing Candidate Name, Registration Number, Enrolled Subjects, and the candidate's active Login PIN.
- **FR-006**: Downloading bulk or single credential PDFs MUST NOT change, rotate, or invalidate a student's existing active PIN when a retrievable credential already exists.
- **FR-007**: When an existing student has no retrievable PIN stored yet (for example, legacy invalidated accounts marked as requiring reset), downloading their single or bulk credential PDF MUST automatically generate and persist a random 5-digit PIN once (updating both login hash and encrypted slip store and clearing the reset-required flag) so every student on the exported PDF receives a working credential.
- **FR-008**: Every individual creation, bulk CSV import, and explicit PIN reset MUST update both the one-way authentication hash and the encrypted credential store atomically within the same transaction.
- **FR-009**: Bulk and single credential PDF downloads MUST be restricted exclusively to full administrators (`ROLE_ADMIN`), hidden from read-only supervisor UI, rejected with authorization failure for non-admin roles, and recorded in the administrative audit log.
- **FR-010**: Standard student directory listings, detail views, CSV roster exports, and application/outbox logs MUST continue excluding readable student PINs.

### Key Entities *(include if feature involves data)*

- **Student**: Represents a registered candidate (`name`, `registrationNumber`, `subjects`, `pinResetRequired`) paired with both an irreversible authentication credential and a protected recoverable credential record for authorized admin PDF printing.
- **Credential Roster PDF Document**: Consolidated multi-page printable document listing registered candidates ordered consistently by name/registration number with candidate name, registration number, and login PIN.
- **Student Credential Slip PDF**: Single-candidate printable slip containing candidate identity details, enrolled subjects, and active login PIN.
- **Audit Record**: Traceability entry recording which administrator downloaded a bulk or single student credential PDF and when.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An administrator can download one complete PDF containing every registered student's Name, Registration Number, and valid working PIN in **1 click** from the student management view.
- **SC-002**: An administrator can download an individual student's PDF slip containing their Name, Registration Number, and valid working PIN in **1 click** from that student's row or profile view.
- **SC-003**: **100%** of PINs printed in both bulk and single PDFs authenticate successfully on the student login portal on first attempt, and remain unchanged across repeat PDF downloads when no PIN reset was triggered.
- **SC-004**: Any previously unissued/legacy students (`pinResetRequired = true`) become immediately ready to log in with the PIN shown in the downloaded PDF without manual per-student resets.
- **SC-005**: Non-admin callers (`ROLE_SUPERVISOR`, `ROLE_STUDENT`, unauthenticated users) experience **0%** access to credential PDFs, and **0** PIN values appear in standard directory listings or CSV roster exports.

## Assumptions

- This feature targets single-school or local CBT center deployments governed by StudentPrep Constitution v1.1.0 (Principle II amendment permitting audited `ROLE_ADMIN` retrieval of encrypted student PINs strictly for credential PDF distribution).
- Student PINs remain 5-digit numeric codes generated uniformly at random on provisioning or reset.
- Administrators securely print and physically distribute downloaded PDF rosters and slips; PDF password-protection on the generated file itself is unnecessary for v1 unless requested later, as the endpoint itself requires `ROLE_ADMIN` authentication.
