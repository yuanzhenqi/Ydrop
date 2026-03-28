from __future__ import annotations

import json
import mimetypes
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Iterable
from uuid import uuid4

from fastapi import HTTPException, UploadFile, status

from .config import get_settings
from .models import FileRecord, UploadResponse


ALLOWED_SUFFIXES = {".m4a", ".mp3", ".wav", ".aac", ".flac", ".ogg"}


class MetadataStore:
    def __init__(self, metadata_path: Path) -> None:
        self.metadata_path = metadata_path

    def load_all(self) -> dict[str, FileRecord]:
        if not self.metadata_path.exists():
            return {}
        raw = json.loads(self.metadata_path.read_text(encoding="utf-8"))
        return {key: FileRecord.model_validate(value) for key, value in raw.items()}

    def save_all(self, records: dict[str, FileRecord]) -> None:
        payload = {key: value.model_dump(mode="json") for key, value in records.items()}
        self.metadata_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


class RelayStorage:
    def __init__(self) -> None:
        self.settings = get_settings()
        self.metadata = MetadataStore(self.settings.metadata_path)

    async def save_upload(self, upload: UploadFile, ttl_minutes: int | None) -> UploadResponse:
        suffix = Path(upload.filename or "audio.bin").suffix.lower()
        if suffix not in ALLOWED_SUFFIXES:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported file type")

        max_bytes = self.settings.max_upload_mb * 1024 * 1024
        file_id = str(uuid4())
        stored_name = f"{file_id}{suffix}"
        target_path = self.settings.storage_dir / stored_name

        size = 0
        with target_path.open("wb") as output:
            while chunk := await upload.read(1024 * 1024):
                size += len(chunk)
                if size > max_bytes:
                    output.close()
                    target_path.unlink(missing_ok=True)
                    raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="File too large")
                output.write(chunk)

        now = datetime.now(UTC)
        ttl = ttl_minutes or self.settings.default_ttl_minutes
        ttl = max(1, min(ttl, self.settings.max_ttl_minutes))
        expires_at = now + timedelta(minutes=ttl)
        mime_type = upload.content_type or mimetypes.guess_type(upload.filename or stored_name)[0] or "application/octet-stream"
        record = FileRecord(
            file_id=file_id,
            original_name=upload.filename or stored_name,
            stored_name=stored_name,
            mime_type=mime_type,
            size_bytes=size,
            created_at=now,
            expires_at=expires_at,
        )

        records = self.metadata.load_all()
        records[file_id] = record
        self.metadata.save_all(records)

        return UploadResponse(
            file_id=file_id,
            url=f"{self.settings.public_base_url}/files/{file_id}",
            expires_at=expires_at,
        )

    def get_record(self, file_id: str) -> FileRecord:
        records = self.metadata.load_all()
        record = records.get(file_id)
        if record is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
        if record.expires_at < datetime.now(UTC):
            self.delete(file_id, ignore_missing=True)
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File expired")
        return record

    def file_path(self, record: FileRecord) -> Path:
        return self.settings.storage_dir / record.stored_name

    def delete(self, file_id: str, ignore_missing: bool = False) -> None:
        records = self.metadata.load_all()
        record = records.pop(file_id, None)
        if record is None:
            if ignore_missing:
                return
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")

        path = self.file_path(record)
        path.unlink(missing_ok=True)
        self.metadata.save_all(records)

    def cleanup_expired(self) -> list[str]:
        records = self.metadata.load_all()
        now = datetime.now(UTC)
        expired = [key for key, value in records.items() if value.expires_at < now]
        for key in expired:
            path = self.settings.storage_dir / records[key].stored_name
            path.unlink(missing_ok=True)
            records.pop(key, None)
        if expired:
            self.metadata.save_all(records)
        return expired

    def all_records(self) -> Iterable[FileRecord]:
        return self.metadata.load_all().values()
