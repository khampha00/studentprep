FROM python:3.11-slim

WORKDIR /app

# Install system dependencies required for docling and OCR processing
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Install docling, python-multipart and FastAPI
RUN pip install --no-cache-dir docling "fastapi[standard]" python-multipart boto3

# Copy the server code
COPY main.py .

# Run the FastAPI server
CMD ["fastapi", "run", "main.py", "--host", "0.0.0.0", "--port", "8000"]
