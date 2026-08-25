-- 1. Biology with Diagram
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('33333333-3333-3333-3333-333333333331', 'BIOLOGY', 'Anatomy', 'ACTIVE', '{
  "type": "MCQ_DIAGRAM",
  "text": "Identify the organelle labeled ''X'' in the diagram below.",
  "assets": [
    { "type": "IMAGE", "url": "https://placehold.co/600x400/008751/FFF?text=Animal+Cell+Diagram", "alt": "Animal Cell Diagram" }
  ],
  "options": {
    "A": "Mitochondrion",
    "B": "Nucleus",
    "C": "Golgi Apparatus",
    "D": "Endoplasmic Reticulum"
  },
  "correctOption": "B"
}'::jsonb);

-- 2. Mathematics with Graph
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('33333333-3333-3333-3333-333333333332', 'MATHEMATICS', 'Coordinate Geometry', 'ACTIVE', '{
  "type": "MCQ_DIAGRAM",
  "text": "What is the equation of the line shown in the graph below?",
  "assets": [
    { "type": "IMAGE", "url": "https://placehold.co/600x400/008751/FFF?text=Linear+Graph", "alt": "Linear Graph" }
  ],
  "options": {
    "A": "\\( y = 2x + 1 \\)",
    "B": "\\( y = x - 2 \\)",
    "C": "\\( y = -x + 3 \\)",
    "D": "\\( y = \\frac{1}{2}x + 2 \\)"
  },
  "correctOption": "A"
}'::jsonb);

-- 3. Financial Accounting with Table
INSERT INTO questions (id, subject, topic, status, content) 
VALUES ('33333333-3333-3333-3333-333333333333', 'FINANCIAL_ACCOUNTING', 'Balance Sheet', 'ACTIVE', '{
  "type": "MCQ_TABLE",
  "text": "Using the following financial data, calculate the Working Capital:\n\n| Item | Amount ($) |\n|---|---|\n| Current Assets | 50,000 |\n| Fixed Assets | 120,000 |\n| Current Liabilities | 30,000 |\n| Long-term Debt | 60,000 |\n\nWhat is the Working Capital?",
  "options": {
    "A": ",000",
    "B": ",000",
    "C": ",000",
    "D": ",000"
  },
  "correctOption": "A"
}'::jsonb);
