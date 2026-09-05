-- Insert Admin User (Bcrypt hash for 'admin123')
INSERT INTO users (id, identifier, pin_hash, role) 
VALUES ('11111111-1111-1111-1111-111111111111', 'ADMIN-001', '$2a$10$xbT2SZkQEnHGf9wGFWxLu.K6vSiOzmG6f3AWcqsnT/8J0lcBuECse', 'ROLE_ADMIN');

-- Insert Dummy Questions (JSONB structured as per FSD)

-- 1. Standard MCQ
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('22222222-2222-2222-2222-222222222221', 'BIOLOGY', 'Cell_Biology', 'ACTIVE', '{
  "type": "MCQ",
  "text": "What is the powerhouse of the cell?",
  "options": {
    "A": "Nucleus",
    "B": "Mitochondrion",
    "C": "Ribosome",
    "D": "Endoplasmic Reticulum"
  },
  "correctOption": "B"
}'::jsonb);

-- 2. Math LaTeX MCQ
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('22222222-2222-2222-2222-222222222222', 'MATHEMATICS', 'Calculus', 'ACTIVE', '{
  "type": "MCQ_LATEX",
  "text": "Evaluate the integral: \\( \\int_{0}^{\\infty} e^{-x^2} dx \\)",
  "options": {
    "A": "\\( \\frac{\\sqrt{\\pi}}{2} \\)",
    "B": "\\( \\pi \\)",
    "C": "\\( 1 \\)",
    "D": "\\( 0 \\)"
  },
  "correctOption": "A"
}'::jsonb);

-- 3. Comprehension with Child Questions
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('22222222-2222-2222-2222-222222222223', 'ENGLISH_LANGUAGE', 'Comprehension', 'ACTIVE', '{
  "type": "COMPREHENSION",
  "passage": "The quick brown fox jumps over the lazy dog. This sentence contains every letter of the alphabet.",
  "childQuestions": [
    {
      "id": "q-child-1",
      "text": "What color is the fox?",
      "options": {
        "A": "Red",
        "B": "Brown",
        "C": "Black",
        "D": "White"
      },
      "correctOption": "B",
      "marks": 1
    },
    {
      "id": "q-child-2",
      "text": "What animal did the fox jump over?",
      "options": {
        "A": "Cat",
        "B": "Mouse",
        "C": "Dog",
        "D": "Bear"
      },
      "correctOption": "C",
      "marks": 1
    }
  ]
}'::jsonb);
