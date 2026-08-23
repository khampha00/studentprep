FROM python:3.11-slim

WORKDIR /app

# Install system dependencies required for docling and OCR processing
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Install docling and FastAPI
RUN pip install --no-cache-dir docling "fastapi[standard]"

# Temporary sleep to keep container alive until the domain-logic-implementer writes the python server code
CMD ["sleep", "infinity"]
