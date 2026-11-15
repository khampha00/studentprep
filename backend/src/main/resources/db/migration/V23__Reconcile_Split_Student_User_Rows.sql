-- Reconcile legacy split users rows where V21 left an old JAMB-* identifier under students.id
-- and a separate orphan ROLE_STUDENT row holding the normalized registration number.

DELETE FROM users u
WHERE u.role = 'ROLE_STUDENT'
  AND NOT EXISTS (
      SELECT 1 FROM students s WHERE s.id = u.id
  )
  AND EXISTS (
      SELECT 1 FROM students s
      WHERE UPPER(TRIM(s.registration_number)) = UPPER(TRIM(u.identifier))
  );

UPDATE users u
SET identifier = s.registration_number,
    pin_hash = s.pin_hash,
    pin_reset_required = s.pin_reset_required,
    updated_at = NOW()
FROM students s
WHERE u.id = s.id
  AND (
      u.identifier IS DISTINCT FROM s.registration_number
      OR u.pin_hash IS DISTINCT FROM s.pin_hash
      OR u.pin_reset_required IS DISTINCT FROM s.pin_reset_required
  );

INSERT INTO users (id, identifier, pin_hash, role, pin_reset_required, created_at, updated_at)
SELECT s.id, s.registration_number, s.pin_hash, 'ROLE_STUDENT', s.pin_reset_required, COALESCE(s.created_at, NOW()), NOW()
FROM students s
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = s.id)
  AND NOT EXISTS (
      SELECT 1 FROM users u WHERE UPPER(TRIM(u.identifier)) = UPPER(TRIM(s.registration_number))
  );
