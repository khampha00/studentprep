# Functional Specification Document (FSD): JAMB-Style CBT Mock Exam Platform

## 1. System Architecture Overview

**Tech Stack:**
*   **Backend:** Java 21, Spring Boot 3.2+, Spring Modulith
*   **Database:** PostgreSQL 16 (Relational + JSONB)
*   **Cache:** Redis (Session caching, Exam Payload)
*   **Frontend:** React 18, Redux Toolkit (RTK), IndexedDB (Dexie.js)
*   **Scale Target:** ~300 concurrent users. Priority: High reliability, fault tolerance, strict data integrity.

The backend is strictly architected as a **Modular Monolith** using Spring Modulith. Cross-module communication is asynchronous using Spring's `ApplicationEventPublisher` and the **Transactional Outbox** pattern to guarantee at-least-once delivery without distributed transactions.

---

## 2. Domain Modules (Spring Modulith Boundaries)

The application is partitioned into the following isolated domain modules. Inter-module dependencies are strictly verified by Spring Modulith (`@Modulithic` tests).

### 2.1 `student` Module
*   **Responsibility:** Student identity, bulk registration, credential generation (Registration Numbers).
*   **Exposes:** `StudentAPI` (read-only queries).
*   **Events Published:** `StudentRegisteredEvent`.
*   **Events Consumed:** None.

### 2.2 `questionbank` Module
*   **Responsibility:** Question taxonomy, subject syllabus, question storage (JSONB), and HITL approval states.
*   **Events Published:** `QuestionApprovedEvent`, `QuestionBankUpdatedEvent`.
*   **Events Consumed:** `ExtractedQuestionsReadyEvent` (from Ingestion).

### 2.3 `ingestion` Module
*   **Responsibility:** PDF ingestion, OCR, LLM structuring.
*   **Events Published:** `ExtractedQuestionsReadyEvent`.
*   **Events Consumed:** None.

### 2.4 `exam` Module
*   **Responsibility:** Exam session lifecycle, state synchronization, timer authority, auto-submission.
*   **Events Published:** `ExamStartedEvent`, `ExamSubmittedEvent`.
*   **Events Consumed:** `StudentRegisteredEvent`.

### 2.5 `analytics` Module
*   **Responsibility:** Grading, ranking, granular topic-level performance, audit logs.
*   **Events Published:** `ResultGeneratedEvent`.
*   **Events Consumed:** `ExamSubmittedEvent`.

**Inter-Module Communication via Transactional Outbox:**
Modules do not call each other's modifying methods directly. When the `exam` module completes an exam, it saves the `ExamSession` state and publishes an `ExamSubmittedEvent`. Both are committed in a single local transaction. A background Spring `@Scheduled` task sweeps the outbox table and dispatches the event to the `analytics` module for grading. 
*   **Concurrency Control:** To prevent multiple instances of the application from dispatching the exact same event concurrently during High Availability deployments, the Outbox sweeper utilizes a distributed lock via **ShedLock** (backed by Redis or PostgreSQL). Additionally, all event consumers are designed to be strictly idempotent.

---

## 3. Database Schema & Data Model (PostgreSQL JSONB)

To handle polymorphic, highly nested question structures, we use PostgreSQL JSONB, balancing relational integrity with NoSQL flexibility.

### 3.1 Core Relational Schema (Simplified)
```sql
CREATE TABLE questions (
    id UUID PRIMARY KEY,
    subject VARCHAR(50) NOT NULL, -- e.g., ENGLISH, MATH
    topic VARCHAR(100),
    status VARCHAR(20) DEFAULT 'DRAFT', -- DRAFT, PENDING_REVIEW, ACTIVE
    content JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE exam_sessions (
    id UUID PRIMARY KEY,
    student_id UUID REFERENCES students(id),
    start_time TIMESTAMP WITH TIME ZONE,
    end_time TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20), -- IN_PROGRESS, SUBMITTED, AUTO_SUBMITTED
    state_payload JSONB -- Snapshot of current answers
);
```

### 3.2 Polymorphic Question Types (JSONB Structures)

**Type 1: English (Passage with Child Questions)**
```json
{
  "type": "COMPREHENSION",
  "passage": "Read the following passage carefully and answer the questions that follow...",
  "childQuestions": [
    {
      "id": "q-101",
      "text": "The phrase 'thundering herd' in the passage implies:",
      "options": {
        "A": "A stampede of cattle",
        "B": "Concurrent cache misses causing database overload",
        "C": "A loud noise",
        "D": "Network latency"
      },
      "correctOption": "B",
      "marks": 1
    }
  ]
}
```

**Type 2: Mathematics/Chemistry (LaTeX Rendering)**
```json
{
  "type": "MCQ_LATEX",
  "text": "Evaluate the integral: \\( \\int_{0}^{\\infty} e^{-x^2} dx \\)",
  "options": {
    "A": "\\( \\frac{\\sqrt{\\pi}}{2} \\)",
    "B": "\\( \\pi \\)",
    "C": "\\( 1 \\)",
    "D": "\\( 0 \\)"
  },
  "correctOption": "A",
  "topic": "Calculus"
}
```

**Type 3: Biology (Linked Diagrams)**
```json
{
  "type": "MCQ_DIAGRAM",
  "text": "Identify the organelle labeled 'X' in the diagram below.",
  "assets": [
    { "type": "IMAGE", "url": "https://cdn.mockexam.com/assets/cell-diagram-001.png", "alt": "Animal Cell" }
  ],
  "options": {
    "A": "Mitochondrion",
    "B": "Nucleus",
    "C": "Golgi Apparatus",
    "D": "Endoplasmic Reticulum"
  },
  "correctOption": "A"
}
```

---

## 4. Exam Engine & Concurrency Strategy

### 4.1 Thundering Herd Mitigation (Redis Cache)
In a JAMB-style CBT, 300 students may click "Start Exam" at the exact same millisecond. Hitting Postgres 300 times to assemble identical 400-question payloads will spike CPU and block connections.
*   **Implementation:** An admin "Publishes" the exam 5 minutes before start time. This triggers the `exam` module to assemble the exam JSON payload and push it to Redis (`KEY: exam:{examId}:payload`).
*   **Retrieval:** When students start, the API retrieves the payload directly from Redis via an O(1) fetch.
*   **Fallback:** If Redis is down, only one thread fetches from Postgres (via `Redisson` distributed lock) and repopulates the cache.

### 4.2 Timer Synchronization & State Management
*   **Server-Side Authority:** The client timer is purely cosmetic. The server records `start_time` and `expected_end_time` in Postgres.
*   **API Validation:** Any submission received after `expected_end_time + 10 seconds` (grace period for network latency) is flagged as `LATE` and potentially discarded.

### 4.3 Anti-Cheating & Offline Resilience (React + IndexedDB)
*   **Offline Mode:** As the student clicks answers, Redux Toolkit updates the state and persists it to **IndexedDB** using `Dexie.js`.
*   **Background Syncing:** A Web Worker attempts to POST state changes to `/api/exam/sync` every 30 seconds. If offline, the mutations queue in IndexedDB. When `navigator.onLine` fires, the queue drains to the server.
*   **Anti-Cheating:** React listens to `visibilitychange` and `window.onblur`. If the tab loses focus, a counter increments. At 3 violations, the frontend forces an auto-submission, and the server marks the `ExamSession` with `FLAGGED_TAB_SWITCH`.

### 4.4 Question & Option Randomization
To prevent students from copying off their neighbors' screens, questions and options must be ordered differently for each student. However, we cannot randomize this on the database/backend without breaking our Redis caching strategy (which relies on a single shared payload). 

*   **Seed-Based Client-Side Shuffling:** The server delivers the exact same base exam payload from Redis to all 300 students. Along with the payload, the backend generates a unique integer `shuffle_seed` for each `ExamSession`.
*   **Deterministic Rendering:** The React frontend uses a Seeded Pseudo-Random Number Generator (PRNG) to shuffle the payload during the initial load. This ensures that:
    1. The order of standalone questions is randomized.
    2. The order of options (A, B, C, D) within every question is randomized.
    3. **Grouping Constraint:** Child questions tied to an English reading passage remain grouped under their parent passage, preventing logical fragmentation.
*   **State Submission:** The frontend stores and syncs answers using the absolute `question_id` and the original `option_id` (not the rendered index), meaning the backend grading engine doesn't need to know how the client shuffled the UI.

---

## 5. PDF Ingestion Pipeline & HITL Workflow

Importing past questions (which are notoriously badly formatted) requires a robust ingestion pipeline.

### 5.1 Multi-Stage Pipeline
1.  **Upload:** Admin uploads a PDF (e.g., "JAMB_Math_2020.pdf").
2.  **Layout Analysis & OCR:** Send PDF to **Docling** or **Mathpix** API to extract raw text, preserving reading order and converting equations to LaTeX blocks.
3.  **Asset Extraction (S3/MinIO):** Bounding boxes identified as diagrams by the OCR engine are cropped, converted to Base64, and uploaded to an Object Storage bucket (S3 or MinIO). The backend generates a public CDN URL for each binary asset.
4.  **LLM Structuring:** Pass the raw Markdown/LaTeX and the newly generated CDN URLs to an LLM (e.g., GPT-4o or Gemini 1.5 Pro) with a strict JSON schema prompt to map the content into our JSONB structures.
5.  **Ingestion Outbox:** The LLM output is saved to the `questions` table with `status = 'DRAFT'`.

### 5.2 Human-in-the-Loop (HITL) Admin Review
*   **Review UI:** The admin dashboard presents the `DRAFT` questions side-by-side with the original PDF snippet (using PDF.js).
*   **WYSIWYG Editing:** The UI utilizes **MathQuill** or **KaTeX** for rendering and editing LaTeX, allowing admins to fix LLM hallucinations or OCR errors.
*   **Approval:** Upon clicking "Approve", the status changes to `ACTIVE`, and a `QuestionApprovedEvent` is fired.

---

## 6. Admin Capabilities & Analytics

### 6.1 Student Management
*   **Bulk Generation:** Admins can upload a CSV (Name, State). The `student` module generates an 8-character alphanumeric JAMB Registration Number for each and hashes a default PIN (using Argon2id).
*   **Printing:** Generate a PDF slip containing the Registration Number, Center, and Exam Time.

### 6.2 Analytics
*   **Granular Grading:** The `analytics` module unpacks the `ExamSubmittedEvent`. It joins the submitted answers with the `ACTIVE` question definitions in Postgres.
*   **Topic-Level Performance:** It calculates scores not just by Subject, but by Topic (e.g., Math -> 60%, Calculus -> 20%, Algebra -> 90%).
*   **Leaderboards:** Pre-calculated via a materialized view or Redis Sorted Sets.

---

## 7. Security & Production Standards

### 7.1 JWT & Role-Based Access Control (RBAC)
*   **Stateless JWT:** Authentication relies on short-lived JWTs (15 mins) and HTTP-only refresh tokens.
*   **Roles:** `ROLE_STUDENT` (can only access their own active session), `ROLE_ADMIN` (CRUD capabilities), `ROLE_SUPERVISOR` (can only view live metrics, cannot edit questions).

### 7.2 Audit Logging & Global Exception Handling
*   **Exception Handling:** `@RestControllerAdvice` intercepts all `EntityNotFoundException`, `AccessDeniedException`, and custom domain exceptions (e.g., `ExamAlreadySubmittedException`), converting them to RFC 7807 Problem Details for HTTP APIs format.
*   **Audit Logging:** Every admin action (e.g., "Approved Question Q-101", "Generated 500 Reg Numbers") is intercepted via Spring AOP and written to an `audit_logs` table with the Admin UUID, timestamp, and payload.

---

## 8. API Endpoints

### Student Facing (High Throughput)
*   `POST /api/v1/auth/login` -> Returns JWT.
*   `GET /api/v1/exams/active/payload` -> Fetches from Redis.
*   `POST /api/v1/exams/active/sync` -> Incremental state sync (Idempotent).
*   `POST /api/v1/exams/active/submit` -> Final submission.

### Admin Facing (Complex Logic)
*   `POST /api/v1/admin/ingest/pdf` -> Initiates ingestion pipeline.
*   `GET /api/v1/admin/questions?status=DRAFT` -> HITL queue.
*   `PUT /api/v1/admin/questions/{id}` -> Update and approve.
*   `GET /api/v1/admin/analytics/exam/{examId}` -> Aggregated stats.

---

## 9. Frontend State Management Strategy (React + RTK)

1.  **`examSlice` (RTK):** Holds `currentQuestionIndex`, `answers` (Record<QuestionId, OptionId>), and `timeLeft`.
2.  **Persistence Middleware:** Every dispatch to `examSlice` triggers a background save to IndexedDB.
3.  **Sync Thunk:** `createAsyncThunk` runs on an interval to POST the `answers` diff to the backend.
4.  **Rehydration & Multi-Device Failover:** On page load, the app checks IndexedDB. However, to prevent a single point of failure (e.g., a student's laptop battery dies and they are moved to a new desktop), the app MUST perform a blocking fetch to `/api/v1/exams/active/session` to pull the last known `state_payload` from PostgreSQL (populated by the 30s background syncs). It compares the server state with the local IndexedDB state and rehydrates Redux using the most recent data, guaranteeing seamless device swapping.

---

## 10. Edge Case Handling

1.  **Total Client Disconnect:** If a student's network fails at minute 10 and never reconnects, the frontend auto-submits to IndexedDB at minute 120. When they connect their device to the internet the next day, the Service Worker pushes the final state.
2.  **Server Timeout / Node Crash:** Spring Modulith Outbox guarantees that if the app crashes right after a student clicks "Submit" but before grading starts, the `ExamSubmittedEvent` remains in the outbox table. Upon restart, grading resumes exactly where it left off.
3.  **Malicious Submission:** If a student attempts to modify the frontend timer or payload to answer 400 questions in 1 second, the backend validates `submitted_time <= expected_end_time + grace_period`. Rate limiting per session prevents automated answer spamming.
