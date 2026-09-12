CREATE TABLE exam_results (
    id UUID PRIMARY KEY,
    student_id UUID REFERENCES students(id) ON DELETE CASCADE,
    exam_session_id UUID REFERENCES exam_sessions(id) ON DELETE CASCADE,
    total_score INTEGER NOT NULL,
    max_score INTEGER NOT NULL,
    topic_breakdown JSONB,
    graded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- For ShedLock
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
