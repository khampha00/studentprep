import os
import tempfile
from fastapi import FastAPI, UploadFile, File, HTTPException
import pymupdf

app = FastAPI(title="Docling Parser Service")

@app.post("/parse")
async def parse_pdf(file: UploadFile = File(...)):
    if not file.filename.endswith(".pdf"):
        raise HTTPException(status_code=400, detail="Only PDF files are supported.")
    
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as temp_pdf:
            content = await file.read()
            temp_pdf.write(content)
            temp_pdf_path = temp_pdf.name
        
        doc = pymupdf.open(temp_pdf_path)
        markdown_text = ""
        for page in doc:
            markdown_text += page.get_text() + "\n\n"
            
        os.unlink(temp_pdf_path)
        return {"markdown": markdown_text}
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))
