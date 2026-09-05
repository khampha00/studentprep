CREATE TABLE subjects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO subjects (name)
SELECT DISTINCT subject FROM questions WHERE subject IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE questions ADD COLUMN subject_id UUID REFERENCES subjects(id);

UPDATE questions q
SET subject_id = s.id
FROM subjects s
WHERE q.subject = s.name;

ALTER TABLE questions ALTER COLUMN subject_id SET NOT NULL;

ALTER TABLE questions DROP COLUMN subject;
