-- V25: Add JSONB assets column on question_contexts for shared context diagrams/images
ALTER TABLE question_contexts
    ADD COLUMN IF NOT EXISTS assets JSONB NOT NULL DEFAULT '[]'::jsonb;
