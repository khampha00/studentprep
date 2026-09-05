CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL REFERENCES subjects(id),
    status VARCHAR(50) NOT NULL,
    total_chunks INT DEFAULT 0,
    processed_chunks INT DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
