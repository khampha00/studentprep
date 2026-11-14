-- V18__Harden_Credentials_And_Pin_Reset.sql
-- Idempotently restore pin_hash columns, add pin_reset_required flags,
-- invalidate legacy unhashed credentials (including V16 '12345' resets),
-- and mark unhashed ADMIN-001 rows for startup bootstrap.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'pin'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'pin_hash'
    ) THEN
        ALTER TABLE users RENAME COLUMN pin TO pin_hash;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'students'
          AND column_name = 'pin'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'students'
          AND column_name = 'pin_hash'
    ) THEN
        ALTER TABLE students RENAME COLUMN pin TO pin_hash;
    END IF;
END $$;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pin_reset_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE students
    ADD COLUMN IF NOT EXISTS pin_reset_required BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE students
SET pin_hash = '!LEGACY_INVALIDATED!',
    pin_reset_required = TRUE
WHERE pin_hash NOT LIKE '$argon2id$%'
  AND pin_hash NOT LIKE '$2a$%'
  AND pin_hash NOT LIKE '$2b$%'
  AND pin_hash NOT LIKE '$2y$%';

UPDATE users
SET pin_hash = '!LEGACY_INVALIDATED!',
    pin_reset_required = TRUE
WHERE role = 'ROLE_STUDENT'
  AND pin_hash NOT LIKE '$argon2id$%'
  AND pin_hash NOT LIKE '$2a$%'
  AND pin_hash NOT LIKE '$2b$%'
  AND pin_hash NOT LIKE '$2y$%';

UPDATE users
SET pin_hash = '!ADMIN_BOOTSTRAP_REQUIRED!',
    pin_reset_required = FALSE
WHERE UPPER(TRIM(identifier)) = 'ADMIN-001'
  AND pin_hash NOT LIKE '$argon2id$%'
  AND pin_hash NOT LIKE '$2a$%'
  AND pin_hash NOT LIKE '$2b$%'
  AND pin_hash NOT LIKE '$2y$%';

UPDATE users
SET pin_hash = '!LEGACY_INVALIDATED!',
    pin_reset_required = TRUE
WHERE UPPER(TRIM(identifier)) <> 'ADMIN-001'
  AND role <> 'ROLE_STUDENT'
  AND pin_hash NOT LIKE '$argon2id$%'
  AND pin_hash NOT LIKE '$2a$%'
  AND pin_hash NOT LIKE '$2b$%'
  AND pin_hash NOT LIKE '$2y$%';
