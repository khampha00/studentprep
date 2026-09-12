CREATE TABLE question_contexts (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    passage TEXT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_contexts_subject FOREIGN KEY (subject_id) REFERENCES subjects (id)
);

ALTER TABLE questions ADD COLUMN context_id UUID;
ALTER TABLE questions ADD CONSTRAINT fk_questions_context FOREIGN KEY (context_id) REFERENCES question_contexts (id);
