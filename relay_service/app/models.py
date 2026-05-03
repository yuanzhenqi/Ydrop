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


class ExtractedEntityResponse(BaseModel):
    label: str
    value: str


class ReminderCandidateResponse(BaseModel):
    title: str
    scheduledAt: int
    reason: str | None = None
    scheduledAtIso: str | None = None  # "YYYY-MM-DDTHH:mm" 在客户端时区


class LinkPreviewSummary(BaseModel):
    """Android 端传上来的已抓取链接预览；relay 不再去抓一次，直接塞进 prompt。"""
    url: str
    title: str = ""
    description: str = ""
    summary: str = ""
    siteName: str = ""
    imageUrl: str = ""
    error: str | None = None


class ImageContextSummary(BaseModel):
    """Android 端 OCR + vision 已经抓完的图片上下文；relay 不再调 vision。"""
    ocrText: str = ""
    aiDescription: str = ""
    keywords: list[str] = Field(default_factory=list)


class AiAnalyzeRequest(BaseModel):
    noteId: str
    title: str
    content: str
    source: str
    category: str
    priority: str
    transcript: str | None = None
    trigger: str
    model: str
    currentTimeText: str | None = None
    currentTimezone: str | None = None
    currentTimeEpochMs: int | None = None
    prompt: str | None = None
    existingTags: list[str] = Field(default_factory=list)
    currentTags: list[str] = Field(default_factory=list)
    linkPreviews: list[LinkPreviewSummary] = Field(default_factory=list)
    imageContexts: list[ImageContextSummary] = Field(default_factory=list)


class AiAnalyzeResponse(BaseModel):
    summary: str = ""
    suggestedTitle: str | None = None
    suggestedCategory: str | None = None
    suggestedPriority: str | None = None
    todoItems: list[str] = Field(default_factory=list)
    extractedEntities: list[ExtractedEntityResponse] = Field(default_factory=list)
    reminderCandidates: list[ReminderCandidateResponse] = Field(default_factory=list)

    def is_effectively_empty(self) -> bool:
        return (
            not self.summary.strip()
            and not (self.suggestedTitle or "").strip()
            and not (self.suggestedCategory or "").strip()
            and not (self.suggestedPriority or "").strip()
            and not self.todoItems
            and not self.extractedEntities
            and not self.reminderCandidates
        )
