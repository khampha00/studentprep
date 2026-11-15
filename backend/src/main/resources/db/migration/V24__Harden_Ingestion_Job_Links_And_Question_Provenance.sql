-- V24__Harden_Ingestion_Job_Links_And_Question_Provenance.sql
-- Link extracted questions to their originating ingestion job (preserving cross-module isolation)
-- and cascade historical ingestion_jobs cleanup when an unused subject is deleted.

ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS ingestion_job_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 'questions'
          AND c.conname = 'fk_questions_ingestion_job'
    ) THEN
        ALTER TABLE questions
            ADD CONSTRAINT fk_questions_ingestion_job
            FOREIGN KEY (ingestion_job_id) REFERENCES ingestion_jobs(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_questions_ingestion_job_created
    ON questions (ingestion_job_id, created_at);

ALTER TABLE ingestion_jobs
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

DO $$
DECLARE
    fk_name text;
BEGIN
    FOR fk_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey)
        WHERE n.nspname = current_schema()
          AND t.relname = 'ingestion_jobs'
          AND c.contype = 'f'
          AND a.attname = 'subject_id'
    LOOP
        EXECUTE format('ALTER TABLE ingestion_jobs DROP CONSTRAINT IF EXISTS %I', fk_name);
    END LOOP;

    ALTER TABLE ingestion_jobs
        ADD CONSTRAINT fk_ingestion_jobs_subject
        FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE;
END $$;
