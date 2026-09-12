-- V8: Add missing indexes, unique constraint for active sessions, and version column for optimistic locking
-- Addresses audit findings: C3 (concurrent sessions), M1 (missing indexes), H4 (optimistic locking)

-- =====================================================
-- 1. Missing Indexes on Foreign Keys (M1)
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_exam_sessions_user_id ON exam_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_questions_subject_id ON questions(subject_id);
CREATE INDEX IF NOT EXISTS idx_questions_context_id ON questions(context_id);
CREATE INDEX IF NOT EXISTS idx_question_contexts_subject_id ON question_contexts(subject_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_subject_id ON ingestion_jobs(subject_id);

-- Index on questions.status for the frequently queried findByStatus
CREATE INDEX IF NOT EXISTS idx_questions_status ON questions(status);

-- =====================================================
-- 2. Unique Active Session Constraint (C3)
-- Prevents a student from having more than one IN_PROGRESS exam session
-- =====================================================
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_session
    ON exam_sessions(user_id)
    WHERE status = 'IN_PROGRESS';

-- =====================================================
-- 3. Optimistic Locking Column (H4)
-- Adds a version column to exam_sessions for JPA @Version support
-- =====================================================
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
