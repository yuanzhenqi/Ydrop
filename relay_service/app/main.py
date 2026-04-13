from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .ai import analyze_note
from .auth import require_relay_token
from .cleanup import cleanup_loop
from .config import get_settings
from .database import close_db, get_db
from .models import AiAnalyzeRequest, AiAnalyzeResponse, DeleteResponse, HealthResponse, UploadResponse
from . import settings_store
from .routes_ai import router as ai_router
from .routes_notes import router as notes_router
from .routes_reminders import router as reminders_router
from .routes_settings import router as settings_router
from .routes_sync import router as sync_router
from .storage import RelayStorage
from .sync_orchestrator import sync_loop


@asynccontextmanager
async def lifespan(_: FastAPI):
    await get_db()
    await settings_store.init_and_migrate()
    cleanup_task = asyncio.create_task(cleanup_loop())
    sync_task = asyncio.create_task(sync_loop())
    try:
        yield
    finally:
        cleanup_task.cancel()
        sync_task.cancel()
        await close_db()


app = FastAPI(title="Ydrop Relay Service", version="0.2.0", lifespan=lifespan)
storage = RelayStorage()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── 笔记/提醒/同步 API ──
app.include_router(notes_router)
app.include_router(reminders_router)
app.include_router(sync_router)
app.include_router(ai_router)
app.include_router(settings_router)


# ── 原有接口 ──

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


# ── 静态文件服务（Next.js 导出） ──

settings = get_settings()
static_dir = settings.static_dir

if static_dir.exists() and (static_dir / "_next").exists():
    app.mount("/_next", StaticFiles(directory=str(static_dir / "_next")), name="next-static")

    @app.get("/{full_path:path}")
    async def serve_spa(full_path: str):
        file_path = static_dir / full_path
        if file_path.is_file():
            return FileResponse(str(file_path))
        html_path = static_dir / f"{full_path}.html"
        if html_path.is_file():
            return FileResponse(str(html_path))
        index = static_dir / "index.html"
        if index.is_file():
            return FileResponse(str(index))
        return FileResponse(str(static_dir / "404.html")) if (static_dir / "404.html").exists() else FileResponse(str(index))
