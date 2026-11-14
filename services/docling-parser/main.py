"""Hardened FastAPI PDF-to-markdown parser sidecar for StudentPrep."""

import gc
import hmac
import logging
import os
import tempfile
from typing import Any

import pymupdf
from fastapi import Depends, FastAPI, File, HTTPException, Security, UploadFile, status
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from starlette.types import ASGIApp, Receive, Scope, Send

logger = logging.getLogger("docling_parser")

DEFAULT_MAX_PDF_BYTES = 10 * 1024 * 1024
STREAM_CHUNK_SIZE = 64 * 1024
MULTIPART_HEADER_OVERHEAD_BYTES = 64 * 1024
PDF_MAGIC_HEADER = b"%PDF-"
ALLOWED_PDF_CONTENT_TYPES = frozenset(
    {"", "application/pdf", "application/x-pdf", "application/octet-stream"}
)

api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


def _get_configured_api_key() -> str:
    return os.environ.get("DOCLING_API_KEY", "").strip()


def _get_max_pdf_bytes() -> int:
    raw = os.environ.get("MAX_PDF_BYTES", "").strip()
    if not raw:
        return DEFAULT_MAX_PDF_BYTES
    try:
        parsed = int(raw)
        return parsed if parsed > 0 else DEFAULT_MAX_PDF_BYTES
    except ValueError:
        return DEFAULT_MAX_PDF_BYTES


def verify_api_key(x_api_key: str | None) -> None:
    """Enforce fail-closed constant-time X-API-Key validation."""
    expected_key = _get_configured_api_key()
    if not expected_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service authentication is not configured.",
        )
    if not x_api_key or not hmac.compare_digest(
        x_api_key.encode("utf-8"),
        expected_key.encode("utf-8"),
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key.",
        )


def require_api_key(x_api_key: str | None = Security(api_key_header)) -> None:
    """FastAPI security dependency enforcing X-API-Key on protected endpoints."""
    verify_api_key(x_api_key)


class ParseSecurityMiddleware:
    """Reject unauthenticated or massively oversized /parse requests before multipart buffering."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            path = scope.get("path", "").rstrip("/") or "/"
            method = scope.get("method", "").upper()
            if path == "/parse" and method == "POST":
                headers = {
                    k.decode("latin-1").lower(): v.decode("latin-1")
                    for k, v in scope.get("headers", [])
                }
                try:
                    verify_api_key(headers.get("x-api-key"))
                except HTTPException as exc:
                    response = JSONResponse(
                        status_code=exc.status_code,
                        content={"detail": exc.detail},
                    )
                    await response(scope, receive, send)
                    return

                content_length_raw = headers.get("content-length")
                if content_length_raw is not None:
                    try:
                        content_length = int(content_length_raw)
                    except ValueError:
                        response = JSONResponse(
                            status_code=status.HTTP_400_BAD_REQUEST,
                            content={"detail": "Invalid Content-Length header."},
                        )
                        await response(scope, receive, send)
                        return

                    if content_length > _get_max_pdf_bytes() + MULTIPART_HEADER_OVERHEAD_BYTES:
                        response = JSONResponse(
                            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                            content={"detail": "Uploaded PDF exceeds maximum allowed size."},
                        )
                        await response(scope, receive, send)
                        return

        await self.app(scope, receive, send)


def _validate_upload_metadata(file: UploadFile) -> None:
    """Validate upload filename presence/extension and MIME content type."""
    filename = file.filename
    if not filename or not filename.strip().lower().endswith(".pdf"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only PDF files are supported.",
        )

    content_type = (file.content_type or "").split(";")[0].strip().lower()
    if content_type not in ALLOWED_PDF_CONTENT_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Unsupported media type. Only PDF files are allowed.",
        )


def _extract_markdown(pdf_path: str) -> str:
    """Open the temporary PDF, extract page text, and deterministically close the handle."""
    doc = None
    try:
        doc = pymupdf.open(pdf_path)
        pages = [page.get_text() for page in doc]
        return "".join(f"{text}\n\n" for text in pages)
    finally:
        if doc is not None:
            try:
                doc.close()
            except Exception:
                logger.warning("Failed to close PyMuPDF document cleanly.")


def _remove_temp_file(temp_pdf_path: str | None) -> None:
    """Best-effort deterministic temp file unlink with GC fallback for Windows handle release."""
    if not temp_pdf_path or not os.path.exists(temp_pdf_path):
        return
    try:
        os.unlink(temp_pdf_path)
    except OSError:
        gc.collect()
        try:
            os.unlink(temp_pdf_path)
        except OSError:
            logger.warning("Failed to remove temporary PDF file.")


app = FastAPI(title="Docling Parser Service")
app.add_middleware(ParseSecurityMiddleware)


@app.get("/health")
async def health() -> dict[str, str]:
    """Unauthenticated container liveness probe."""
    return {"status": "ok"}


@app.post("/parse", dependencies=[Depends(require_api_key)])
async def parse_pdf(file: UploadFile = File(...)) -> dict[str, Any]:
    """Stream-validate a PDF upload and extract page text into markdown."""
    _validate_upload_metadata(file)

    max_bytes = _get_max_pdf_bytes()
    chunk_size = min(STREAM_CHUNK_SIZE, max_bytes + 1)
    temp_pdf_path: str | None = None

    try:
        total_bytes = 0
        header_buf = bytearray()

        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as temp_pdf:
            temp_pdf_path = temp_pdf.name
            while True:
                chunk = await file.read(chunk_size)
                if not chunk:
                    break

                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    raise HTTPException(
                        status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                        detail="Uploaded PDF exceeds maximum allowed size.",
                    )

                if len(header_buf) < len(PDF_MAGIC_HEADER):
                    needed = len(PDF_MAGIC_HEADER) - len(header_buf)
                    header_buf.extend(chunk[:needed])
                    if (
                        len(header_buf) == len(PDF_MAGIC_HEADER)
                        and bytes(header_buf) != PDF_MAGIC_HEADER
                    ):
                        raise HTTPException(
                            status_code=status.HTTP_400_BAD_REQUEST,
                            detail="Invalid file format: missing PDF signature.",
                        )

                temp_pdf.write(chunk)

        if total_bytes < len(PDF_MAGIC_HEADER) or bytes(header_buf) != PDF_MAGIC_HEADER:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid file format: missing PDF signature.",
            )

        # Capture any parse failure as an unchained HTTPException so PyMuPDF traceback frames
        # do not keep the file descriptor alive when finally runs os.unlink().
        pending_error: HTTPException | None = None
        markdown_text = ""
        try:
            markdown_text = _extract_markdown(temp_pdf_path)
        except (pymupdf.FileDataError, pymupdf.EmptyFileError):
            logger.warning("Rejected invalid or corrupted PDF upload.")
            pending_error = HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Invalid or corrupted PDF document.",
            )
        except Exception:
            logger.exception("Unexpected failure while parsing PDF upload.")
            pending_error = HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to parse PDF document.",
            )

        if pending_error is not None:
            raise pending_error

        return {"markdown": markdown_text}

    finally:
        _remove_temp_file(temp_pdf_path)
        try:
            await file.close()
        except Exception:
            pass
