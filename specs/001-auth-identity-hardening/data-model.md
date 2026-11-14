# Data Model: Slice 001 — Authentication, Identity Integrity & Secrets Hardening

**Feature**: `specs/001-auth-identity-hardening/spec.md` | **Date**: 2026-11-14

## 1. Persistent Entities (PostgreSQL + Flyway `V18`)

### 1.1 `users` (`com.studentprep.student.User`)
Synchronized authentication principal table for staff (`ROLE_ADMIN`, `ROLE_SUPERVISOR`) and mirrored candidates (`ROLE_STUDENT`).

| Column | Java Field | Type / Constraints | Notes / Changes in Slice 001 |
|--------|-----------|--------------------|-------------------------------|
| `id` | `id` | `UUID PRIMARY KEY` | **Assigned manually** via `Persistable<UUID>` when mirroring `Student.id` (staff generated via `UUID.randomUUID()`); decouples `User` from `BaseEntity` `@GeneratedValue` so `User.id == Student.id` stays exact. |
| `identifier` | `identifier` | `VARCHAR(50) UNIQUE NOT NULL` | Registration number (students) or staff username (`ADMIN-001`); trimmed & matched case-insensitively. |
| `pin_hash` | `pinHash` | `VARCHAR(255) NOT NULL` | Renamed from `pin` back to `pin_hash` in `V18`. Always Argon2id (`$argon2id$...`), legacy BCrypt (`$2*`), or explicit non-authenticating lock marker (`!LEGACY_INVALIDATED!`, `!ADMIN_BOOTSTRAP_REQUIRED!`). Never exposed in DTOs/logs. |
| `role` | `role` | `VARCHAR(20) NOT NULL` | `ROLE_STUDENT`, `ROLE_ADMIN`, `ROLE_SUPERVISOR`. |
| `pin_reset_required` | `pinResetRequired` | `BOOLEAN NOT NULL DEFAULT FALSE` | Added in `V18`. Set `TRUE` on legacy invalidated student rows; cleared to `FALSE` on admin `resetPin` or new creation. |
| `created_at` | `createdAt` | `TIMESTAMPTZ NOT NULL` | JPA `@CreatedDate`. |
| `updated_at` | `updatedAt` | `TIMESTAMPTZ` | JPA `@LastModifiedDate`. |

### 1.2 `students` (`com.studentprep.student.Student`)
Authoritative candidate profile and subject enrollment aggregate.

| Column | Java Field | Type / Constraints | Notes / Changes in Slice 001 |
|--------|-----------|--------------------|-------------------------------|
| `id` | `id` | `UUID PRIMARY KEY` | Hibernate `@GeneratedValue(strategy = GenerationType.UUID)` (`StudentManagementService` stops pre-calling `setId(UUID.randomUUID())` before save). Referenced by `exam_sessions(user_id)` and `exam_results(student_id)`. |
| `name` | `name` | `VARCHAR(255) NOT NULL` | Candidate full name. |
| `exam_center` | `examCenter` | `VARCHAR(255)` | Assigned CBT center name. |
| `registration_number` | `registrationNumber` | `VARCHAR(50) UNIQUE NOT NULL` | `JAMB-2026-<6-char SecureRandom alphanumeric>`. |
| `pin_hash` | `pinHash` | `VARCHAR(255) NOT NULL` | Renamed from `pin` back to `pin_hash` in `V18`; serialized field annotated `@JsonIgnore` as defense-in-depth. |
| `pin_reset_required` | `pinResetRequired` | `BOOLEAN NOT NULL DEFAULT FALSE` | Added in `V18`. `TRUE` for legacy accounts requiring admin one-time reset before exam login. |
| `created_at` | `createdAt` | `TIMESTAMPTZ` | `@CreationTimestamp`. |
| `subjects` | `subjects` | `@ManyToMany` -> `student_subjects` | Enrolled JAMB subjects (`Subject`). |

## 2. Domain Events (Spring Modulith Outbox — `event_publication`)

All event payloads MUST carry hashed credentials only—never plaintext PINs:

- `StudentCreatedEvent(UUID studentId, String registrationNumber, String pinHash, boolean pinResetRequired)`
- `StudentUpdatedEvent(UUID studentId, String registrationNumber)` *(updates identifier without mutating credentials unless reset)*
- `StudentPinResetEvent(UUID studentId, String registrationNumber, String newPinHash)`
- `StudentDeletedEvent(UUID studentId, String registrationNumber)`

## 3. Redis Keyspaces & Session Lock State Machine

Centralized in `com.studentprep.common.SessionRedisKeys` so both `security` and `student` modules read/format keys consistently without introducing cyclic Spring Modulith dependencies.

| Redis Key Pattern | Value | TTL | Purpose & Lifecycle |
|-------------------|-------|-----|---------------------|
| `session:<CANONICAL_ID>` | `<jti UUID>` | `7 days` | Single active workstation session binding per user (`CANONICAL_ID` = `identifier.toUpperCase(Locale.ROOT)`). Checked by `JwtAuthenticationFilter` (`token_type=access`), `/auth/refresh` (`token_type=refresh`), and `/auth/logout` (`jti` ownership check). |
| `exam_unlock:<CANONICAL_ID>` | `"1"` | `15 minutes` | Single-use admin recovery clearance written by `POST /api/v1/admin/students/{id}/unlock-session`. Consumed atomically during next valid login by a replacement workstation when `IN_PROGRESS` exam is active. |
| `rate_limit:login:id:<CANONICAL_ID>` | `<failCount>` | `15 minutes` | Per-account failed-login counter (max 5 failed attempts; reset on successful authentication). |
| `rate_limit:login:ip:<CLIENT_IP>` | `<reqCount>` | `1 minute` | Per-IP login burst ceiling (`60`/min default, lab-NAT-safe). |
| `rate_limit:refresh:ip:<CLIENT_IP>` | `<reqCount>` | `1 minute` | Per-IP renewal burst ceiling (`30`/min default). |
| `rate_limit:sync:<CANONICAL_ID>` | `<reqCount>` | `1 minute` | Preserved active-exam sync cap (`10`/min). |

### State Transitions: Candidate Session & Active Exam Lock

```text
[Logged Out / No Redis Key]
   |-- POST /auth/login (valid PIN, !pinResetRequired, !flagged, !IN_PROGRESS)
   v
[Bound Workstation A] (Redis session:<ID> = jti_1, Cookie refreshToken = refresh(jti_1))
   |-- POST /auth/refresh (valid refresh cookie matching jti_1) --> New access JWT (jti_1 retained)
   |-- POST /exams/start --> Postgres exam_sessions.status = IN_PROGRESS
   v
[Active Exam on Workstation A]
   |-- Workstation A refresh or re-login with cookie(jti_1) [isSameDevice=true]
   |     --> Rotates session:<ID> = jti_2, issues new access + refresh(jti_2); Exam Resumes
   |-- Workstation B login attempt [isSameDevice=false, no exam_unlock]
   |     --> HTTP 409 Conflict (blocked; Workstation A stays untouched)
   |-- Workstation A evicted call to POST /auth/logout [mismatched/missing jti]
   |     --> Deletes local cookie only; Redis session:<ID> UNTOUCHED
   |-- Admin POST /admin/students/{id}/unlock-session
   |     --> Deletes session:<ID> (evicts PC A), writes exam_unlock:<ID> (15m), logs AuditLog
   v
[Unlocked for Recovery]
   |-- Next valid login on Workstation B --> Consumes exam_unlock:<ID>, sets session:<ID> = jti_3
```

## 4. JWT Claims Schema

| Claim | Access JWT (`token_type=access`) | Refresh JWT (`token_type=refresh`) | Validation Rule |
|-------|----------------------------------|-------------------------------------|-----------------|
| `jti` (`id`) | UUID per login | Same UUID per login | Must equal Redis `session:<CANONICAL_ID>` |
| `sub` | Canonical user identifier | Canonical user identifier | Matched case-insensitively against DB |
| `role` | `ROLE_STUDENT` / `ROLE_ADMIN` / `ROLE_SUPERVISOR` | Same role | Reconstructs `GrantedAuthority` |
| `token_type` | `"access"` | `"refresh"` | Enforced strictly: filter accepts `"access"` only; `/refresh` and `isSameDevice` accept `"refresh"` only |
| `exp` | `now + 900s` (`JWT_EXPIRATION_MS`) | `now + 7d` | Expired access JWT returns `401 Unauthorized` via `AuthenticationEntryPoint` |
