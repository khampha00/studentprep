# StudentPrep API Contract (v1)

This document serves as the strict contract between the frontend and backend teams. All endpoints must adhere to these standards to prevent integration drift.

## 1. Global API Standards

### 1.1 Success Response Wrapper
All successful responses (2xx) MUST be wrapped in a standard JSON object containing a `data` and optional `meta` property (used strictly for pagination).

```json
{
  "data": {
    // Array or Object
  },
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

### 1.2 Error Responses (RFC 7807)
All errors (4xx, 5xx) MUST conform to RFC 7807 Problem Details for HTTP APIs. Spring Boot 3 handles this natively when `spring.mvc.problem-details.enabled=true` is set.

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Exam payload validation failed.",
  "instance": "/api/v1/exams/active/sync",
  "errors": [
    {
      "field": "timeLeft",
      "message": "Must be greater than or equal to 0"
    }
  ]
}
```

### 1.3 Authentication
All endpoints (except `/api/v1/auth/login`) require a valid JWT passed in the Authorization header:
`Authorization: Bearer <access_token>`

---

## 2. Authentication Module (`security` boundary)

### `POST /api/v1/auth/login`
Authenticates a user (Student or Admin) and issues an Access Token.

**Request Body:**
```json
{
  "identifier": "JAMB-2026-X7Y8Z9",
  "pin": "12345"
}
```

**Response (200 OK):**
```json
{
  "data": {
    "accessToken": "eyJhbG...",
    "expiresIn": 900,
    "role": "ROLE_STUDENT"
  }
}
```
*(Note: The `refresh_token` is omitted from the payload as it must be set as an HttpOnly, Secure, SameSite=Strict cookie).*

---

## 3. Ingestion & Question Bank (Admin Facing)

### `POST /api/v1/admin/ingestion/pdf`
Uploads a past-questions PDF for layout analysis, OCR, S3 uploading, and LLM structuring.

**Content-Type:** `multipart/form-data`
*   `file`: The PDF file binary.
*   `subject`: (String) e.g., "MATH"
*   `year`: (String) e.g., "2020"

**Response (202 Accepted):**
```json
{
  "data": {
    "jobId": "uuid-1234",
    "status": "PROCESSING",
    "message": "PDF ingestion started in background."
  }
}
```

### `GET /api/v1/admin/questions`
Fetches questions for Human-in-the-Loop (HITL) review.

**Query Params:** `?status=DRAFT&page=0&size=20`

**Response (200 OK):**
```json
{
  "data": [
    {
      "id": "uuid-q-101",
      "subject": "MATH",
      "topic": "Calculus",
      "status": "DRAFT",
      "content": { 
        "type": "MCQ_LATEX",
        "text": "Evaluate: \\( x^2 \\)",
        "options": { "A": "...", "B": "..." },
        "correctOption": "A"
      }
    }
  ],
  "meta": { "page": 0, "size": 20, "totalElements": 40, "totalPages": 2 }
}
```

### `PUT /api/v1/admin/questions/{id}`
Updates a question (fixing LLM/OCR hallucinations) and approves it for live exams.

**Request Body:**
```json
{
  "status": "ACTIVE",
  "content": { 
     // Complete updated JSONB Payload
  }
}
```

---

## 4. Exam Engine (Student Facing)

### `GET /api/v1/exams/active/payload`
Fetches the massive JSON payload for the active exam. The backend serves this directly from the Redis Cache.

**Response (200 OK):**
```json
{
  "data": {
    "examId": "uuid-exam-2026",
    "shuffleSeed": 4829103,
    "durationMinutes": 120,
    "questions": [
      {
         "id": "q-101",
         "type": "MCQ_LATEX",
         "text": "Evaluate...",
         "options": { "A": "...", "B": "...", "C": "...", "D": "..." }
         // SECURITY WARNING: correctOption MUST BE STRIPPED from this payload
      }
    ]
  }
}
```

### `GET /api/v1/exams/active/session`
Fetches the last known session state from PostgreSQL. Used heavily by the frontend `examSlice` on initialization to handle multi-device failover and rehydration.

**Response (200 OK):**
```json
{
  "data": {
    "sessionId": "uuid-session-123",
    "status": "IN_PROGRESS",
    "timeLeft": 5400,
    "lastSyncedAt": "2026-08-23T10:05:00Z",
    "answers": {
      "q-101": "B",
      "q-102": "A"
    }
  }
}
```

### `POST /api/v1/exams/active/sync`
Background Web Worker endpoint to persist state changes incrementally. Highly concurrent, must be extremely fast.

**Request Body:**
```json
{
  "answers": {
    "q-101": "B",
    "q-103": "D"
  },
  "timeLeft": 5370,
  "tabSwitchCount": 1
}
```

**Response (200 OK):**
```json
{
  "data": {
    "status": "SYNCED",
    "serverTime": "2026-08-23T10:05:30Z"
  }
}
```

### `POST /api/v1/exams/active/submit`
Final exam submission. Triggers the `ExamSubmittedEvent` via the Transactional Outbox for grading.

**Request Body:**
```json
{
  "answers": { "q-101": "B", "q-102": "A", "q-103": "D" },
  "timeLeft": 0,
  "tabSwitchCount": 1
}
```
**Response (200 OK):**
```json
{
  "data": {
    "status": "SUBMITTED",
    "message": "Exam successfully submitted."
  }
}
```
