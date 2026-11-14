DO $$
DECLARE
    rec RECORD;
    candidate VARCHAR(50);
    digits_part TEXT;
    letters_part TEXT;
    old_reg TEXT;
    updated_users INTEGER;
BEGIN
    FOR rec IN
        SELECT id, registration_number
        FROM students
        WHERE registration_number !~ '^[0-9]{6}[A-Z]{2}$'
        ORDER BY created_at NULLS FIRST, id
    LOOP
        old_reg := rec.registration_number;

        IF UPPER(TRIM(old_reg)) = 'JAMB-2026-DEMO01'
           AND NOT EXISTS (SELECT 1 FROM students WHERE UPPER(TRIM(registration_number)) = '123456JF')
           AND NOT EXISTS (SELECT 1 FROM users WHERE UPPER(TRIM(identifier)) = '123456JF') THEN
            candidate := '123456JF';
        ELSE
            LOOP
                digits_part := LPAD(LEAST(999999, FLOOR(random() * 1000000)::INT)::TEXT, 6, '0');
                letters_part := CHR(65 + LEAST(25, FLOOR(random() * 26)::INT))
                             || CHR(65 + LEAST(25, FLOOR(random() * 26)::INT));
                candidate := digits_part || letters_part;

                EXIT WHEN NOT EXISTS (
                    SELECT 1 FROM students WHERE UPPER(TRIM(registration_number)) = candidate
                ) AND NOT EXISTS (
                    SELECT 1 FROM users WHERE UPPER(TRIM(identifier)) = candidate
                );
            END LOOP;
        END IF;

        UPDATE students
        SET registration_number = candidate
        WHERE id = rec.id;

        UPDATE users
        SET identifier = candidate,
            updated_at = NOW()
        WHERE id = rec.id;

        GET DIAGNOSTICS updated_users = ROW_COUNT;

        IF updated_users = 0 THEN
            UPDATE users
            SET identifier = candidate,
                updated_at = NOW()
            WHERE role = 'ROLE_STUDENT'
              AND UPPER(TRIM(identifier)) = UPPER(TRIM(old_reg));
        END IF;
    END LOOP;
END $$;
