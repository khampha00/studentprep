-- =====================================================================
-- V22: Exam Snapshot Integrity, Proctoring Moderation & Seed Question Fix
-- Slice: 003-exam-kiosk-integrity
-- =====================================================================

-- 1. Exam Sessions Schema Updates
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS snapshot_payload JSONB;
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS superseded BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill expected_end_time where null and start_time is set (standard 120m duration)
UPDATE exam_sessions
SET expected_end_time = start_time + INTERVAL '120 minutes'
WHERE expected_end_time IS NULL AND start_time IS NOT NULL;

-- Rebuild partial unique active session index to exclude superseded sessions
DROP INDEX IF EXISTS idx_unique_active_session;
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_active_session
    ON exam_sessions(user_id)
    WHERE status = 'IN_PROGRESS' AND superseded = FALSE;

CREATE INDEX IF NOT EXISTS idx_exam_sessions_user_superseded_status
    ON exam_sessions(user_id, superseded, status);

-- 2. Exam Results Schema Updates
-- Deduplicate any historical duplicate exam_session_id rows before adding constraint (keeping highest id)
DELETE FROM exam_results a USING exam_results b
WHERE a.exam_session_id = b.exam_session_id
  AND a.exam_session_id IS NOT NULL
  AND a.id > b.id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_exam_results_session_id'
    ) THEN
        ALTER TABLE exam_results ADD CONSTRAINT uq_exam_results_session_id UNIQUE (exam_session_id);
    END IF;
END $$;

ALTER TABLE exam_results ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(30) NOT NULL DEFAULT 'ELIGIBLE';
ALTER TABLE exam_results ADD COLUMN IF NOT EXISTS moderated_by VARCHAR(100);
ALTER TABLE exam_results ADD COLUMN IF NOT EXISTS moderated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE exam_results ADD COLUMN IF NOT EXISTS moderation_note TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_exam_results_moderation_status'
    ) THEN
        ALTER TABLE exam_results ADD CONSTRAINT chk_exam_results_moderation_status
            CHECK (moderation_status IN ('ELIGIBLE', 'PENDING_REVIEW', 'CLEARED', 'VOIDED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_exam_results_moderation_status ON exam_results(moderation_status);

-- 3. Seed Question Remediation

-- 3.1 Convert V2 Legacy Comprehension into linked question_contexts + 2 flat MCQ questions
DO $$
DECLARE
    v_english_id UUID;
    v_context_id UUID := '22222222-2222-2222-2222-222222222220';
BEGIN
    SELECT id INTO v_english_id FROM subjects WHERE name = 'ENGLISH_LANGUAGE';
    IF v_english_id IS NULL THEN
        INSERT INTO subjects (id, name) VALUES (uuid_generate_v4(), 'ENGLISH_LANGUAGE') RETURNING id INTO v_english_id;
    END IF;

    -- Upsert passage context
    INSERT INTO question_contexts (id, subject_id, passage, created_at, updated_at)
    VALUES (v_context_id, v_english_id, 'The quick brown fox jumps over the lazy dog. This sentence contains every letter of the alphabet.', NOW(), NOW())
    ON CONFLICT (id) DO UPDATE
    SET subject_id = EXCLUDED.subject_id,
        passage = EXCLUDED.passage,
        updated_at = NOW();

    -- Question 1: What color is the fox?
    UPDATE questions
    SET context_id = v_context_id,
        subject_id = v_english_id,
        topic = 'Comprehension',
        status = 'ACTIVE',
        content = '{
          "type": "MCQ",
          "text": "What color is the fox?",
          "options": {
            "A": "Red",
            "B": "Brown",
            "C": "Black",
            "D": "White"
          },
          "correctOption": "B"
        }'::jsonb,
        updated_at = NOW()
    WHERE id = '22222222-2222-2222-2222-222222222223';

    -- Question 2: What animal did the fox jump over?
    INSERT INTO questions (id, subject_id, context_id, topic, status, content, created_at, updated_at)
    VALUES ('22222222-2222-2222-2222-222222222224', v_english_id, v_context_id, 'Comprehension', 'ACTIVE', '{
      "type": "MCQ",
      "text": "What animal did the fox jump over?",
      "options": {
        "A": "Cat",
        "B": "Mouse",
        "C": "Dog",
        "D": "Bear"
      },
      "correctOption": "C"
    }'::jsonb, NOW(), NOW())
    ON CONFLICT (id) DO UPDATE
    SET subject_id = EXCLUDED.subject_id,
        context_id = EXCLUDED.context_id,
        topic = EXCLUDED.topic,
        status = EXCLUDED.status,
        content = EXCLUDED.content,
        updated_at = NOW();
END $$;

-- 3.2 Repair V4 Financial Accounting Options (restore $ prefixes)
UPDATE questions
SET content = '{
  "type": "MCQ_TABLE",
  "text": "Using the following financial data, calculate the Working Capital:\n\n| Item | Amount ($) |\n|---|---|\n| Current Assets | 50,000 |\n| Fixed Assets | 120,000 |\n| Current Liabilities | 30,000 |\n| Long-term Debt | 60,000 |\n\nWhat is the Working Capital?",
  "options": {
    "A": "$20,000",
    "B": "$30,000",
    "C": "$60,000",
    "D": "$90,000"
  },
  "correctOption": "A"
}'::jsonb,
updated_at = NOW()
WHERE id = '33333333-3333-3333-3333-333333333333';

-- 3.3 Rewrite V4 Diagram Questions to self-hosted offline SVGs with lowercase "image" type
UPDATE questions
SET content = '{
  "type": "MCQ_DIAGRAM",
  "text": "Identify the organelle labeled ''X'' in the diagram below.",
  "assets": [
    { "type": "image", "url": "/assets/questions/animal-cell-diagram.svg", "alt": "Animal Cell Diagram" }
  ],
  "options": {
    "A": "Mitochondrion",
    "B": "Nucleus",
    "C": "Golgi Apparatus",
    "D": "Endoplasmic Reticulum"
  },
  "correctOption": "B"
}'::jsonb,
updated_at = NOW()
WHERE id = '33333333-3333-3333-3333-333333333331';

UPDATE questions
SET content = '{
  "type": "MCQ_DIAGRAM",
  "text": "What is the equation of the line shown in the graph below?",
  "assets": [
    { "type": "image", "url": "/assets/questions/linear-graph.svg", "alt": "Linear Graph" }
  ],
  "options": {
    "A": "\\( y = 2x + 1 \\)",
    "B": "\\( y = x - 2 \\)",
    "C": "\\( y = -x + 3 \\)",
    "D": "\\( y = \\frac{1}{2}x + 2 \\)"
  },
  "correctOption": "A"
}'::jsonb,
updated_at = NOW()
WHERE id = '33333333-3333-3333-3333-333333333332';
