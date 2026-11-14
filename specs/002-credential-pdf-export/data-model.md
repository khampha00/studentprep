# Data Model & Schema Design: Printable Student Credential PDF Export (Slice 002)

## 1. Relational Schema Migration (`V19__Add_Student_Pin_Ciphertext.sql`)

```sql
-- Idempotently add reversible AEAD-encrypted credential storage alongside Argon2id pin_hash
-- for audited ROLE_ADMIN PDF slip & roster re-printing in single-school deployments.
ALTER TABLE students
    ADD COLUMN IF NOT EXISTS pin_ciphertext TEXT;
```

### Column Notes
- **`students.pin_hash`** (`VARCHAR(255) NOT NULL`): Retained exclusively as the Argon2id/BCrypt credential synced to `users.pin_hash` for authentication.
- **`students.pin_ciphertext`** (`TEXT NULL`): Stores `v1:<base64-12-byte-iv>:<base64-ciphertext-with-16-byte-gcm-tag>` encrypted using `AES/GCM/NoPadding` under SHA-256(`security.credential-vault.key`).
  - `NULL` indicates a legacy or pre-Slice-002 student record awaiting initial encrypted PIN issuance.
  - Non-null rows preserve the exact candidate PIN across repeat single or bulk PDF downloads until an explicit Admin PIN Reset (`POST /api/v1/admin/students/{id}/reset-pin`) overwrites both `pin_hash` and `pin_ciphertext`.

## 2. Domain Entity & Sync Events (`student` module)

### `Student` (`com.studentprep.student.Student`)
- Existing fields preserved: `id`, `name`, `registrationNumber`, `pinHash` (`@JsonIgnore`), `pinResetRequired`, `createdAt`, `subjects`.
- New field:
  ```java
  @JsonIgnore
  @Column(name = "pin_ciphertext", columnDefinition = "TEXT")
  private String pinCiphertext;
  ```
- **Invariant**: `pinCiphertext` is annotated `@JsonIgnore` and MUST NEVER be mapped into `StudentResponse`, CSV exports (`CsvStudentImporter.exportStudentsCsv`), or `RegistrationSlipGenerator.getRegistrationSlip(UUID)` JSON metadata.

### Existing Outbox Events Preserved
- `StudentCreatedEvent(studentId, registrationNumber, pinHash, pinResetRequired)`
- `StudentPinResetEvent(studentId, registrationNumber, newPinHash, pinResetRequired)`
- Neither event payload includes plaintext PINs or `pinCiphertext`, preserving clean outbox `event_publication` logs.
- When lazy initial provisioning runs on an existing student lacking `pinCiphertext` or flagged `pinResetRequired = true`, `StudentManagementService` updates both `pinHash` and `pinCiphertext` in PostgreSQL (`students`) and emits `StudentPinResetEvent(..., false)` so `users.pin_hash` and `users.pin_reset_required` update via `UserSyncListener`.

## 3. Repository Queries (`StudentRepository`)
- `List<Student> findAllByOrderByNameAscRegistrationNumberAsc()` — deterministic alphabetical/registration-number order for school-wide PDF printing.
- `List<Student> findByRegistrationNumberInOrderByNameAscRegistrationNumberAsc(Collection<String> registrationNumbers)` — authoritative database lookup for newly imported CSV batches (`POST /api/v1/admin/students/credentials/pdf`).

## 4. Credential Lifecycle State Transitions

| Initial Student State | Action | Resulting `pin_hash` | Resulting `pin_ciphertext` | `pin_reset_required` | PDF Output |
| :--- | :--- | :--- | :--- | :--- | :--- |
| New create / CSV import / Reset PIN | Onboarding or Reset | Fresh Argon2id hash | Fresh `v1:...` AES-GCM | `false` | Prints newly generated 5-digit PIN |
| Active (`pin_reset_required = false`, valid `pin_ciphertext`) | Single or Bulk PDF download | **Unchanged** | **Unchanged** | `false` | Decodes & prints **same** active PIN |
| Legacy/Unissued (`pin_reset_required = true` or `pin_ciphertext IS NULL`) | Single or Bulk PDF download | New random 5-digit Argon2id hash | Fresh `v1:...` AES-GCM + sync event | `false` | Issues & prints newly active 5-digit PIN |
| Any `pin_ciphertext` with wrong key / corrupt tag | Single or Bulk PDF download | **Unchanged** | **Unchanged** | Unchanged | Fails cleanly with RFC 7807 `500` instructing admin to reset PIN or verify key |
| Non-prod fixture (`JAMB-2026-DEMO01`) seeded fresh | Backend boot (`!prod`) | Argon2id(`73914`) | `v1:...` encrypted `73914` | `false` | Prints `73914` without mutation |
