# Data Model & Schema Changes: Slice 005 (`005-ingestion-stabilization`)

## 1. Flyway Migration: `V24__Harden_Ingestion_Job_Links_And_Question_Provenance.sql`

```sql
-- Link questions back to their originating ingestion job for job-scoped context grouping
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS ingestion_job_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_questions_ingestion_job'
    ) THEN
        ALTER TABLE questions
            ADD CONSTRAINT fk_questions_ingestion_job
            FOREIGN KEY (ingestion_job_id) REFERENCES ingestion_jobs(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_questions_ingestion_job_created
    ON questions (ingestion_job_id, created_at);

-- Track ingestion job updates cleanly and cascade historical job cleanup when an empty subject is deleted
ALTER TABLE ingestion_jobs
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE ingestion_jobs
    DROP CONSTRAINT IF EXISTS ingestion_jobs_subject_id_fkey;

ALTER TABLE ingestion_jobs
    ADD CONSTRAINT ingestion_jobs_subject_id_fkey
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE;
```

## 2. Entity & Repository Updates

### `com.studentprep.questionbank.Question`
- Add `@Column(name = "ingestion_job_id") private UUID ingestionJobId;`
- Note: Uses plain `UUID` value type (no `@ManyToOne IngestionJob` reference) to preserve Spring Modulith boundaries (`questionbank` must not depend on `ingestion`).
- Add `QuestionRepository.findByIngestionJobIdOrderByCreatedAtAsc(UUID ingestionJobId)`.

### `com.studentprep.questionbank.QuestionContextRepository`
- Add `@Modifying void deleteBySubject_Id(UUID subjectId);` so `SubjectController.deleteSubject(UUID id)` can remove orphaned `question_contexts` after confirming zero `questions` remain.

### `com.studentprep.ingestion.job.IngestionJob` & `IngestionJobRepository`
- Add `@Column(name = "updated_at") private LocalDateTime updatedAt;` updated automatically via `@PrePersist` / `@PreUpdate`.
- Add `findByStatusIn(List<IngestionJobStatus> statuses)` to recover `PENDING` / `PROCESSING` jobs stranded by server restarts cleanly on startup.

### `com.studentprep.security.AuditLog` & `AuditLogAspect`
- Keep `audit_logs` table unchanged (`V11`, `endpoint VARCHAR(255)`).
- Enforce safe normalization & length bounding (`<= 255` chars) before saving `AuditLog` entries.

## 3. Validated Request DTOs

### `com.studentprep.questionbank.dto.QuestionUpdateRequest`
Replaces raw `@RequestBody Question` on `PUT /api/v1/admin/questions/{id}`:
- `status` (`@NotNull QuestionStatus`): `ACTIVE` or `DRAFT`.
- `topic` (nullable `String`, `@Size(max = 255)`).
- `content` (`@NotNull Map<String, Object>`): validated to ensure `text` is non-blank before promoting to `ACTIVE` and `options` / `correctOption` preserve valid candidate schema.
