ALTER TABLE users RENAME COLUMN pin_hash TO pin;
ALTER TABLE students RENAME COLUMN pin_hash TO pin;

UPDATE users SET pin = '12345';
UPDATE students SET pin = '12345';
