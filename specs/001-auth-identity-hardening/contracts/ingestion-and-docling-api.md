# API Contract: Ingestion Asset Upload & Docling Parser Sidecar (Slice 001)

## 1. Admin Diagram / Asset Upload (`POST /api/v1/admin/ingestion/assets`)

- **Authorization**: `ROLE_ADMIN` only (`ROLE_SUPERVISOR` forbidden -> `403 Forbidden`).
- **Content-Type**: `multipart/form-data` (`file`).
- **Validation Rules**:
  - Must be non-empty and `<= 5,242,880` bytes (5 MB).
  - Declared `Content-Type` AND inspected header magic bytes must match one of:
    - `image/png` (`89 50 4E 47 0D 0A 1A 0A`) -> stored extension `.png`
    - `image/jpeg` (`FF D8 FF`) -> stored extension `.jpg`
    - `image/gif` (`47 49 46 38 37 61` or `47 49 46 38 39 61`) -> stored extension `.gif`
    - `image/webp` (`RIFF....WEBP`) -> stored extension `.webp`
  - **Explicitly blocked**: `image/svg+xml`, `text/html`, `application/xhtml+xml`, `application/javascript`, `text/plain`, or mismatched MIME/magic bytes.
- **Storage Key**: `diagrams/<UUID>.<verified-ext>` (raw `OriginalFilename` ignored).
- **Responses**:
  - `200 OK`: `{ "data": { "url": "<s3.public-base-url>/<bucket>/diagrams/<uuid>.<ext>" } }`
  - `400 Bad Request` / `415 Unsupported Media Type`: RFC 7807 `ProblemDetail` (`detail` explaining validation error, zero object written to MinIO/S3).

---

## 2. PDF Ingestion Error Hygiene (`POST /api/v1/admin/ingestion/pdf` & Async Worker)

- **Authorization**: `ROLE_ADMIN` only.
- **Validation**: Non-empty `.pdf`, `application/pdf`, `%PDF-` magic header, `<= 10 MB`, `subjectId` must exist (`404 Not Found` ProblemDetail if missing).
- **Gemini Request Hygiene**: `LlmStructuringService` sends API key via `x-goog-api-key` header (never `?key=` URL query parameter).
- **Error Sanitization**: Any failed upstream LLM/HTTP message persisted to `ingestion_jobs.error_message` or logged via SLF4J MUST strip API keys, `key=...`, `x-goog-api-key`, `x-api-key`, and `Authorization` values.

---

## 3. Internal Docling Parser Sidecar (`POST http://<docling>:8000/parse`)

- **Authentication**: Requires `X-API-Key: <DOCLING_API_KEY>` verified with constant-time comparison (`hmac.compare_digest`).
  - Missing or invalid key -> `401 Unauthorized` (`{"detail": "Invalid or missing API key."}`).
  - Misconfigured server (`DOCLING_API_KEY` empty/unset) -> fail-closed `503 Service Unavailable`.
- **Upload Constraints**:
  - `file.filename` safely handled when `None`; must end with `.pdf`.
  - Streamed read capped at `MAX_PDF_BYTES` (default `10 * 1024 * 1024`) -> `413 Payload Too Large` on excess.
  - First 5 bytes must match `%PDF-` -> `400 Bad Request` otherwise.
- **Resource & Error Hygiene**:
  - Temporary file closed/unlinked inside `finally:` block across every success and failure path.
  - `pymupdf` document closed cleanly after extraction.
  - Internal tracebacks never returned in HTTP `500` `detail`.
- **Health Endpoint**: `GET /health` -> `200 OK` `{ "status": "ok" }` (no auth required for container healthchecks).
