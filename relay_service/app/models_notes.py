from __future__ import annotations

from pydantic import BaseModel, Field


class NoteCreate(BaseModel):
    content: str
    category: str = "NOTE"
    priority: str = "MEDIUM"
    tags: list[str] = Field(default_factory=list)


class NoteUpdate(BaseModel):
    title: str | None = None
    content: str | None = None
    category: str | None = None
    priority: str | None = None
    color_token: str | None = None
    tags: list[str] | None = None
    attachments: list["AttachmentItem"] | None = None


class LinkPreviewItem(BaseModel):
    """对齐 Android model/LinkPreview.kt（snake_case 命名跟随 web API 风格）"""
    url: str
    title: str = ""
    description: str = ""
    image_url: str = ""
    site_name: str = ""
    summary: str = ""
    fetched_at: int = 0
    error: str | None = None


class AttachmentItem(BaseModel):
    """图片附件（Web 端 A 方案：Vision AI 单通道，无本地 OCR）。

    与 Android NoteAttachment.kt 字段保留兼容（id/type/remote_url/ai_description/keywords），
    Web 不上传 ocr_text，由前端 UI 决定是否显示该段。"""
    id: str
    type: str = "IMAGE"
    remote_url: str = ""
    description: str = ""
    keywords: list[str] = Field(default_factory=list)
    actionable_items: list[str] = Field(default_factory=list)
    dates: list[str] = Field(default_factory=list)
    error: str | None = None
    created_at: int = 0


class NoteResponse(BaseModel):
    id: str
    title: str
    content: str
    original_content: str | None = None
    source: str
    category: str
    priority: str
    color_token: str
    status: str
    created_at: int
    updated_at: int
    last_synced_at: int | None = None
    sync_error: str | None = None
    pinned: bool = False
    remote_path: str | None = None
    is_archived: bool = False
    archived_at: int | None = None
    is_trashed: bool = False
    trashed_at: int | None = None
    tags: list[str] = Field(default_factory=list)
    transcript: str | None = None
    audio_path: str | None = None
    relay_url: str | None = None
    transcription_status: str = "NOT_STARTED"
    link_previews: list[LinkPreviewItem] = Field(default_factory=list)
    attachments: list[AttachmentItem] = Field(default_factory=list)


class NoteListResponse(BaseModel):
    items: list[NoteResponse]
    total: int


class ReminderCreate(BaseModel):
    note_id: str
    title: str
    scheduled_at: int
    source: str = "MANUAL"
    recurrence: str | None = None  # DAILY / WEEKLY / MONTHLY / WEEKDAYS / null


class ReminderResponse(BaseModel):
    id: str
    note_id: str
    title: str
    scheduled_at: int
    source: str
    status: str
    delivery_targets: list[str] = Field(default_factory=lambda: ["LOCAL_NOTIFICATION"])
    recurrence: str | None = None
    created_at: int
    updated_at: int


class ReminderListResponse(BaseModel):
    items: list[ReminderResponse]
    total: int


class AiSuggestionResponse(BaseModel):
    id: str
    note_id: str
    status: str
    summary: str = ""
    suggested_title: str | None = None
    suggested_category: str | None = None
    suggested_priority: str | None = None
    suggested_tags: list[str] = Field(default_factory=list)
    todo_items: list[str] = Field(default_factory=list)
    extracted_entities: list[dict] = Field(default_factory=list)
    reminder_candidates: list[dict] = Field(default_factory=list)
    error_message: str | None = None
    created_at: int
    updated_at: int


class SyncStatusResponse(BaseModel):
    last_sync_at: int | None = None
    pushed: int = 0
    pulled: int = 0
    errors: int = 0
    running: bool = False
