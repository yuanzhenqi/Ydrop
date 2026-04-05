from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, File, Form, UploadFile
from fastapi.responses import FileResponse

from .ai import analyze_note
from .auth import require_relay_token
from .cleanup import cleanup_loop
from .models import AiAnalyzeRequest, AiAnalyzeResponse, DeleteResponse, HealthResponse, UploadResponse
from .storage import RelayStorage


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(cleanup_loop())
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="YDoc Relay Service", version="0.1.0", lifespan=lifespan)
storage = RelayStorage()


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse()


@app.get("/healthz", response_model=HealthResponse)
async def healthz() -> HealthResponse:
    return HealthResponse()


@app.post("/upload", response_model=UploadResponse, dependencies=[Depends(require_relay_token)])
async def upload(
    file: UploadFile = File(...),
    ttl_minutes: int | None = Form(default=None),
) -> UploadResponse:
    return await storage.save_upload(file, ttl_minutes)


@app.get("/files/{file_id}")
async def download(file_id: str) -> FileResponse:
    record = storage.get_record(file_id)
    path = storage.file_path(record)
    return FileResponse(path=path, media_type=record.mime_type, filename=record.original_name)


@app.delete("/files/{file_id}", response_model=DeleteResponse, dependencies=[Depends(require_relay_token)])
async def delete(file_id: str) -> DeleteResponse:
    storage.delete(file_id)
    return DeleteResponse(file_id=file_id)


@app.post("/ai/analyze-note", response_model=AiAnalyzeResponse, dependencies=[Depends(require_relay_token)])
async def analyze_ai_note(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    return analyze_note(payload)
