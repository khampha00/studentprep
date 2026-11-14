-- Idempotently add reversible AEAD-encrypted credential storage alongside Argon2id pin_hash
-- for audited ROLE_ADMIN PDF slip & roster re-printing in single-school deployments.
ALTER TABLE students
    ADD COLUMN IF NOT EXISTS pin_ciphertext TEXT;
