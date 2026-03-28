from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class FileRecord(BaseModel):
    file_id: str
    original_name: str
    stored_name: str
    mime_type: str
    size_bytes: int
    created_at: datetime
    expires_at: datetime


class UploadResponse(BaseModel):
    file_id: str
    url: str
    expires_at: datetime


class DeleteResponse(BaseModel):
    ok: bool = True
    file_id: str


class HealthResponse(BaseModel):
    ok: bool = True
    timestamp: datetime = Field(default_factory=datetime.utcnow)
