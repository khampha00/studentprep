# StudentPrep Seed Data Directives

This document explicitly directs the `schema-engineer` on how to populate the database upon initial startup. A blank database means the UI cannot be tested, the Admin dashboard cannot be accessed, and the Exam Engine will crash when generating payloads.

The `schema-engineer` must use Flyway to manage database migrations and ensure the following seed data is injected during the `V2__Seed_Data.sql` migration step (following the `V1__Init_Schema.sql` table creations).

## 1. The Super Admin Account

To test the ingestion pipeline and the HITL UI, developers must be able to log in immediately.

The `V2` script must insert a super-admin record into the `users` (or `students`) table:
*   **Identifier (Username):** `ADMIN-001`
*   **Role:** `ROLE_ADMIN`
*   **PIN (Password):** The script must insert the securely hashed value of the string `admin123`. The `security-engineer` will dictate the hashing algorithm (Argon2id or Bcrypt), but the `schema-engineer` is responsible for placing the pre-computed hash into the raw SQL script.

## 2. Taxonomy (Subjects & Topics)

The Question Bank relies on a structured taxonomy. The `V2` script must insert the foundational JAMB subjects and their core topics. For example:

*   **Subject:** `ENGLISH_LANGUAGE`
    *   Topics: `Comprehension`, `Lexis_and_Structure`, `Oral_English`
*   **Subject:** `MATHEMATICS`
    *   Topics: `Algebra`, `Calculus`, `Statistics`, `Geometry`
*   **Subject:** `BIOLOGY`
    *   Topics: `Cell_Biology`, `Genetics`, `Ecology`
*   **Subject:** `CHEMISTRY`
    *   Topics: `Organic_Chemistry`, `Physical_Chemistry`, `Inorganic_Chemistry`

## 3. Dummy Question Payload

To allow the `frontend-ui-developer` to begin building the Exam Engine interface immediately (without waiting for the complex PDF ingestion pipeline to be finished), the `V2` script must inject at least **three dummy questions** into the `questions` table with `status = 'ACTIVE'`:

1.  A standard multiple-choice question.
2.  A Mathematics question containing a LaTeX string (to test the KaTeX rendering).
3.  A Comprehension question with a parent passage and two child questions (to test the UI grouping logic).

These dummy questions must adhere strictly to the JSONB structures defined in the FSD.
