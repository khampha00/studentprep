# Data Model & Migration Design: Slice 003 (`003-exam-kiosk-integrity`)

**Flyway Migration Version**: `V22__Exam_Snapshot_Integrity_Moderation_And_Seed_Fix.sql` (following `V21__Convert_Student_Registration_Numbers_Format.sql`)

## 1. Database Schema Changes (`V22`)

### 1.1 `exam_sessions`
Existing columns (`V1`, `V8`, `V13`, `V14`): `id`, `user_id`, `start_time`, `end_time`, `expected_end_time`, `status VARCHAR(50)`, `state_payload JSONB`, `shuffle_seed INTEGER`, `version BIGINT`.

Add / enforce:
- `snapshot_payload JSONB` — Immutable snapshot captured at session start (`{ "questions": [...], "contexts": {...} }`) including original `correctOption` for grading integrity while stripped cleanly in API responses.
- `superseded BOOLEAN NOT NULL DEFAULT FALSE` — Marks sessions archived by an admin `grant-retake` reset so the candidate can sit a fresh exam without losing historical audit data.
- Backfill `expected_end_time = start_time + INTERVAL '120 minutes'` where `expected_end_time IS NULL AND start_time IS NOT NULL`.
- Partial unique index refresh (drop `idx_unique_active_session` if non-filtered by `superseded`, recreate cleanly):
  - `CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_session ON exam_sessions(user_id) WHERE status = 'IN_PROGRESS' AND superseded = FALSE;`
- Supporting query index:
  - `CREATE INDEX IF NOT EXISTS idx_exam_sessions_user_superseded_status ON exam_sessions(user_id, superseded, status);`

### 1.2 `exam_results`
Existing columns (`V10`): `id`, `student_id`, `exam_session_id`, `total_score`, `max_score`, `topic_breakdown JSONB`, `graded_at`.

Add / enforce:
- Deduplicate any accidental historical duplicate `exam_session_id` rows first (keep earliest/highest-id row cleanly before creating constraint).
- `ALTER TABLE exam_results ADD CONSTRAINT uq_exam_results_session_id UNIQUE (exam_session_id);`
- `moderation_status VARCHAR(30) NOT NULL DEFAULT 'ELIGIBLE'` — Check constraint: `'ELIGIBLE'`, `'PENDING_REVIEW'`, `'CLEARED'`, `'VOIDED'`.
- `moderated_by VARCHAR(100)` & `moderated_at TIMESTAMP WITH TIME ZONE` & `moderation_note TEXT`.
- `CREATE INDEX IF NOT EXISTS idx_exam_results_moderation_status ON exam_results(moderation_status);`

### 1.3 Seed Question Remediation (`V2` / `V4`)
1. `V2` Legacy Comprehension `22222222-2222-2222-2222-222222222223`:
   - Look up `ENGLISH_LANGUAGE` subject ID from `subjects` (created in `V5`).
   - Upsert a `question_contexts` row with id `22222222-2222-2222-2222-222222222220` holding `"The quick brown fox jumps over the lazy dog. This sentence contains every letter of the alphabet."`.
   - Update `22222222-2222-2222-2222-222222222223` into standard Question 1 (`"What color is the fox?"`, `type: 'MCQ'`, `context_id`, flat `options` + `correctOption: 'B'`, removing legacy `childQuestions`).
   - Insert Question 2 (`22222222-2222-2222-2222-222222222224`, `"What animal did the fox jump over?"`, `type: 'MCQ'`, `context_id`, flat `options` + `correctOption: 'C'`, linked to same `ENGLISH_LANGUAGE` subject).
2. `V4` Financial Accounting `33333333-3333-3333-3333-333333333333`:
   - Repair corrupted `options` (`"$20,000"`, `"$30,000"`, `"$60,000"`, `"$90,000"`, matching `correctOption: "A"`).
3. `V4` Offline Diagram Assets `33333333-3333-3333-3333-333333333331` & `33333333-3333-3333-3333-333333333332`:
   - Replace external `https://placehold.co/...` asset URLs with offline-bundled local paths (`/assets/questions/animal-cell-diagram.svg`, `/assets/questions/linear-graph.svg`) and normalize asset type to lowercase `"image"`.

## 2. Backend Domain Entities & Enums

### 2.1 `com.studentprep.exam.ExamSession`
- `@Column(name = "expected_end_time") private Instant expectedEndTime;`
- `@Type(JsonType.class) @Column(name = "snapshot_payload", columnDefinition = "jsonb") private Map<String, Object> snapshotPayload;`
- `@Column(name = "superseded", nullable = false) private boolean superseded = false;`

### 2.2 `com.studentprep.exam.ExamSubmittedEvent`
- Extended cleanly to carry session outcome without cross-module DB lookups:
  `public record ExamSubmittedEvent(UUID sessionId, UUID userId, Map<String, Object> finalState, ExamSessionStatus terminalStatus, Map<String, Object> snapshotPayload) {}`
  *(Keeping backwards compatibility or updating single listener `ExamGraderListener` consistently.)*

### 2.3 `com.studentprep.exam.ExamSessionFlaggedEvent`
- New domain event published after committing `FLAGGED_TAB_SWITCH`:
  `public record ExamSessionFlaggedEvent(UUID sessionId, UUID userId, String reason) {}`
- Consumed in `com.studentprep.security` to revoke `SessionRedisKeys.sessionKey(canonicalIdentifier)`.

### 2.4 `com.studentprep.analytics.ExamResult` & `ExamResultModerationStatus`
- `public enum ExamResultModerationStatus { ELIGIBLE, PENDING_REVIEW, CLEARED, VOIDED }`
- On `ExamResult`:
  - `@Column(name = "exam_session_id", nullable = false, unique = true) private UUID examSessionId;`
  - `@Enumerated(EnumType.STRING) @Column(name = "moderation_status", nullable = false) private ExamResultModerationStatus moderationStatus = ExamResultModerationStatus.ELIGIBLE;`
  - `private String moderatedBy;`
  - `private Instant moderatedAt;`
  - `private String moderationNote;`

## 3. Client IndexedDB Schema (`StudentPrepDB` v2)

`frontend/src/store/db.ts`:
```ts
export interface LocalExamState {
  id: string; // sessionId (primary key)
  ownerIdentifier: string; // Canonical uppercase student reg no from JWT sub
  shuffleSeed: number | null;
  questions: ExamQuestion[]; // Frozen session snapshot cached offline
  contexts: Record<string, string>;
  student: { name: string; registrationNumber: string } | null;
  expectedEndTimeMs: number; // Authoritative server deadline mapped to epoch ms
  answers: Record<string, string>;
  flagged?: Record<string, boolean>;
  lastUpdated: number;
  timeLeft: number;
  isSynced: boolean;
  syncPendingFinal?: boolean;
  terminationReason?: string;
  tabSwitchCount?: number;
  isExamTerminated?: boolean;
  isFinal?: boolean;
}
```
IndexedDB stores:
- `version(2).stores({ examStates: 'id, ownerIdentifier, isSynced, lastUpdated' })` with upgrade hook migrating any legacy v1 rows out (clearing unscoped v1 cache on upgrade guarantees zero leftover stale kiosk state!).
