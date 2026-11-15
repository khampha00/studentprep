# API & Verification Contracts: Slice 005 (`005-ingestion-stabilization`)

All endpoints adhere strictly to [docs/api_contract.md](file:///c:/Users/User/Desktop/studentprep/docs/api_contract.md) (`{ data, meta }` 2xx envelopes and RFC 7807 `ProblemDetail` errors).

## 1. Non-Blocking PDF Ingestion

### `POST /api/v1/admin/ingestion/pdf` (`ROLE_ADMIN`)
- **Validation**: Non-empty `.pdf` file, `<= 10 MB`, `Content-Type: application/pdf`, `%PDF-` magic header, valid `subjectId`. Invalid inputs return `400 Bad Request` or `415 Unsupported Media Type` (`ProblemDetail`).
- **Response (`202 Accepted`)**:
  ```json
  {
    "data": {
      "jobId": "UUID"
    }
  }
  ```
- **Asynchronous Execution Contract**:
  - Immediately persists `IngestionJob(status = PENDING)` and dispatches Docling OCR parsing + LLM chunk structuring asynchronously on `ingestionTaskExecutor`.
  - On Docling/LLM failure, updates `IngestionJob` (`status = FAILED`, `errorMessage = <sanitized message>`) without leaking API keys or stack traces.
  - On completion, runs job-scoped `postProcessContextGroups(jobId)` respecting `is_follow_up` (`false` clears active passage context; `true` links to active passage) and sets `status = COMPLETED`.

## 2. Subject Deletion & Question Update Validation

### `DELETE /api/v1/admin/subjects/{id}` (`ROLE_ADMIN`)
- **Behavior**:
  - If subject does not exist -> `404 Not Found`.
  - If active or draft `questions` exist for subject -> `409 Conflict` `ProblemDetail` (`detail`: `"Cannot delete subject while questions remain. Delete active and draft questions first."`).
  - If zero `questions` exist (even if historical `ingestion_jobs` or orphaned `question_contexts` exist) -> deletes orphaned contexts, deletes subject (cascading historical ingestion jobs), evicts Redis question cache after DB commit, and returns `200 OK`:
    ```json
    {
      "data": {
        "status": "DELETED"
      }
    }
    ```

### `PUT /api/v1/admin/questions/{id}` (`ROLE_ADMIN`)
- **Request Body (`QuestionUpdateRequest`)**:
  ```json
  {
    "status": "DRAFT | ACTIVE",
    "topic": "Optional string",
    "content": {
      "type": "MCQ",
      "text": "Question prompt",
      "options": { "A": "...", "B": "...", "C": "...", "D": "..." },
      "correctOption": "A",
      "assets": [{ "type": "image", "url": "https://..." }]
    }
  }
  ```
- **Validation**: Missing `status` or `content` returns `400 Bad Request` `ProblemDetail` (`errors` array); promoting to `ACTIVE` requires non-blank prompt and valid `correctOption`.

## 3. Audit Log Filtering Contract

- **Persisted (`audit_logs`)**:
  - All `/api/v1/admin/**` controller methods annotated with `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`.
  - Sensitive read/export endpoints on `@GetMapping`: handlers whose request URI matches `/slip`, `/pdf`, `/script`, `/credentials-pdf`, or explicit sensitive export endpoints.
- **Excluded (`audit_logs`)**:
  - Routine admin `GET` polls (`/api/v1/admin/analytics/dashboard`, `/live-sessions`, `/flagged-sessions`, `/results`, `/subject-registrations`, `/subjects`, `/questions`, `/students`, `/ingestion/jobs/*`).
- **Safety**: `endpoint` strings exceeding `255` characters are safely truncated to `255` chars.

## 4. Admin Results Pagination (`GET /api/v1/admin/analytics/results?page=0&size=20`)

- `frontend/src/pages/admin/ResultsPage.tsx` passes `?page=${page}&size=20` and renders `meta.totalElements`, `meta.totalPages`, `meta.number` with Next / Previous pagination controls matching `StudentsPage.tsx`.

## 5. CI Playwright Gate (`.github/workflows/ci.yml`)

- `frontend-e2e-tests` runs:
  - `npx playwright test tests/auth-hardening.spec.ts tests/exam-kiosk-integrity.spec.ts tests/ingestion-and-audit-stabilization.spec.ts --reporter=list` across Chromium and WebKit.
