import asyncio
import io
import tempfile
from pathlib import Path

import pymupdf
import pytest
from fastapi import HTTPException, UploadFile
from fastapi.testclient import TestClient

import main

TEST_API_KEY = "test-docling-secret-key-001"
ONE_MB = 1024 * 1024
DEFAULT_MAX_PDF_BYTES = 10 * ONE_MB


def _build_minimal_pdf(text: str = "StudentPrep JAMB Physics Question 1") -> bytes:
    doc = pymupdf.open()
    try:
        page = doc.new_page()
        page.insert_text((72, 72), text)
        return doc.tobytes()
    finally:
        doc.close()


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> TestClient:
    monkeypatch.setenv("DOCLING_API_KEY", TEST_API_KEY)
    monkeypatch.delenv("MAX_PDF_BYTES", raising=False)
    monkeypatch.setattr(tempfile, "tempdir", str(tmp_path))
    return TestClient(main.app)


def test_health_endpoint_ok_without_auth_and_no_secret_leak(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DOCLING_API_KEY", "super-secret-value-xyz")
    client = TestClient(main.app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert "super-secret-value-xyz" not in response.text


def test_parse_fails_closed_503_when_api_key_unset_or_blank(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pdf_bytes = _build_minimal_pdf()

    for configured in (None, "", "   "):
        if configured is None:
            monkeypatch.delenv("DOCLING_API_KEY", raising=False)
        else:
            monkeypatch.setenv("DOCLING_API_KEY", configured)

        client = TestClient(main.app)
        response = client.post(
            "/parse",
            headers={"X-API-Key": "anything"},
            files={"file": ("sample.pdf", pdf_bytes, "application/pdf")},
        )
        assert response.status_code == 503
        assert "Traceback" not in response.text

        response_no_header = client.post(
            "/parse",
            files={"file": ("sample.pdf", pdf_bytes, "application/pdf")},
        )
        assert response_no_header.status_code == 503


def test_parse_rejects_missing_or_invalid_api_key_with_401(client: TestClient) -> None:
    pdf_bytes = _build_minimal_pdf()

    missing_res = client.post(
        "/parse",
        files={"file": ("sample.pdf", pdf_bytes, "application/pdf")},
    )
    assert missing_res.status_code == 401
    assert missing_res.json()["detail"] == "Invalid or missing API key."
    assert TEST_API_KEY not in missing_res.text

    invalid_res = client.post(
        "/parse",
        headers={"X-API-Key": "wrong-key"},
        files={"file": ("sample.pdf", pdf_bytes, "application/pdf")},
    )
    assert invalid_res.status_code == 401
    assert invalid_res.json()["detail"] == "Invalid or missing API key."
    assert TEST_API_KEY not in invalid_res.text

    no_body_unauth = client.post("/parse")
    assert no_body_unauth.status_code == 401
    assert no_body_unauth.json()["detail"] == "Invalid or missing API key."


def test_parse_rejects_none_or_empty_filename_with_400(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("DOCLING_API_KEY", TEST_API_KEY)
    pdf_bytes = _build_minimal_pdf()

    for bad_filename in (None, "", "   "):
        upload = UploadFile(filename=bad_filename, file=io.BytesIO(pdf_bytes))
        with pytest.raises(HTTPException) as exc_info:
            asyncio.run(main.parse_pdf(file=upload))
        assert exc_info.value.status_code == 400
        assert "Traceback" not in str(exc_info.value.detail)

    http_empty_filename = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("", pdf_bytes, "application/pdf")},
    )
    assert http_empty_filename.status_code in (400, 422)
    assert "Traceback" not in http_empty_filename.text


def test_parse_rejects_non_pdf_extension_spoofed_mime_and_invalid_magic_bytes(
    client: TestClient, tmp_path: Path
) -> None:
    headers = {"X-API-Key": TEST_API_KEY}
    valid_pdf = _build_minimal_pdf()

    wrong_ext = client.post(
        "/parse",
        headers=headers,
        files={"file": ("notes.txt", valid_pdf, "text/plain")},
    )
    assert wrong_ext.status_code == 400

    spoofed_mime_html = client.post(
        "/parse",
        headers=headers,
        files={
            "file": (
                "malicious.pdf",
                b"<html><script>alert(1)</script></html>",
                "application/pdf",
            )
        },
    )
    assert spoofed_mime_html.status_code == 400

    empty_pdf = client.post(
        "/parse",
        headers=headers,
        files={"file": ("empty.pdf", b"", "application/pdf")},
    )
    assert empty_pdf.status_code == 400

    mismatched_content_type = client.post(
        "/parse",
        headers=headers,
        files={"file": ("valid_bytes.pdf", valid_pdf, "text/html")},
    )
    assert mismatched_content_type.status_code in (400, 415)

    assert list(tmp_path.iterdir()) == []


def test_parse_rejects_corrupt_pdf_with_sanitized_client_error_and_cleans_temp(
    client: TestClient, tmp_path: Path
) -> None:
    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={
            "file": (
                "corrupt.pdf",
                b"%PDF-1.4\n%%EOF\ngarbage-not-a-valid-xref-table",
                "application/pdf",
            )
        },
    )

    assert response.status_code in (400, 422)
    detail = response.json()["detail"]
    assert "Traceback" not in detail
    assert str(tmp_path) not in detail
    assert list(tmp_path.iterdir()) == []


def test_parse_rejects_oversized_pdf_above_10mb_default_with_413(
    client: TestClient, tmp_path: Path
) -> None:
    oversized = b"%PDF-1.4\n" + (b"A" * DEFAULT_MAX_PDF_BYTES)

    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("large.pdf", oversized, "application/pdf")},
    )

    assert response.status_code == 413
    assert "Traceback" not in response.text
    assert list(tmp_path.iterdir()) == []


def test_parse_streams_with_bounded_limit_before_buffering_unbounded(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("MAX_PDF_BYTES", "1024")
    max_written_bytes = 0
    real_named_temp_file = tempfile.NamedTemporaryFile

    def tracking_named_temp_file(*args, **kwargs):
        handle = real_named_temp_file(*args, **kwargs)
        orig_write = handle.write

        def counting_write(data):
            nonlocal max_written_bytes
            max_written_bytes += len(data)
            return orig_write(data)

        handle.write = counting_write
        return handle

    monkeypatch.setattr(tempfile, "NamedTemporaryFile", tracking_named_temp_file)
    oversized_stream = b"%PDF-1.4\n" + (b"B" * (32 * 1024))

    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("stream.pdf", oversized_stream, "application/pdf")},
    )

    assert response.status_code == 413
    assert max_written_bytes <= 1024
    assert list(tmp_path.iterdir()) == []


def test_parse_middleware_checks_auth_first_and_rejects_huge_content_length_without_temp_files(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("MAX_PDF_BYTES", "1024")
    called = {"temp_created": False}
    real_named_temp_file = tempfile.NamedTemporaryFile

    def fail_if_called(*args, **kwargs):
        called["temp_created"] = True
        return real_named_temp_file(*args, **kwargs)

    monkeypatch.setattr(tempfile, "NamedTemporaryFile", fail_if_called)
    huge_payload = b"%PDF-1.4\n" + (b"C" * (128 * 1024))

    unauth_res = client.post(
        "/parse",
        files={"file": ("huge.pdf", huge_payload, "application/pdf")},
    )
    assert unauth_res.status_code == 401
    assert called["temp_created"] is False

    auth_res = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("huge.pdf", huge_payload, "application/pdf")},
    )
    assert auth_res.status_code == 413
    assert called["temp_created"] is False
    assert list(tmp_path.iterdir()) == []


def test_parse_unlinks_temp_file_and_sanitizes_500_on_unexpected_parser_failure(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    pdf_bytes = _build_minimal_pdf()

    def exploding_open(_path: str):
        raise RuntimeError("Traceback secret-internal-token-do-not-leak")

    monkeypatch.setattr(pymupdf, "open", exploding_open)

    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("valid.pdf", pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 500
    body = response.json()
    assert "Traceback" not in body["detail"]
    assert "secret-internal-token-do-not-leak" not in body["detail"]
    assert str(tmp_path) not in body["detail"]
    assert list(tmp_path.iterdir()) == []


def test_parse_closes_doc_and_unlinks_temp_when_page_extraction_raises(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    pdf_bytes = _build_minimal_pdf("Page text")
    closed_flag = {"closed": False}

    class FailingPageDoc:
        def __iter__(self):
            raise RuntimeError("Unexpected page extraction crash")

        def close(self):
            closed_flag["closed"] = True

    monkeypatch.setattr(pymupdf, "open", lambda _path: FailingPageDoc())

    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("valid.pdf", pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 500
    assert "Unexpected page extraction crash" not in response.text
    assert closed_flag["closed"] is True
    assert list(tmp_path.iterdir()) == []


def test_parse_valid_minimal_pdf_succeeds_closes_doc_and_cleans_temp(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    pdf_bytes = _build_minimal_pdf("Evaluate integral of x squared")
    closed_flag = {"closed": False}
    real_open = pymupdf.open

    class TrackingDoc:
        def __init__(self, inner):
            self._inner = inner

        def __iter__(self):
            return iter(self._inner)

        def close(self):
            closed_flag["closed"] = True
            return self._inner.close()

    monkeypatch.setattr(pymupdf, "open", lambda path: TrackingDoc(real_open(path)))

    response = client.post(
        "/parse",
        headers={"X-API-Key": TEST_API_KEY},
        files={"file": ("EXAM.PDF", pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 200
    data = response.json()
    assert "Evaluate integral of x squared" in data["markdown"]
    assert closed_flag["closed"] is True
    assert list(tmp_path.iterdir()) == []
