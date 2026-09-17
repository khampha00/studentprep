ALTER TABLE exam_sessions DROP CONSTRAINT IF EXISTS exam_sessions_user_id_fkey;
ALTER TABLE exam_sessions ADD CONSTRAINT exam_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES students(id) ON DELETE CASCADE;

