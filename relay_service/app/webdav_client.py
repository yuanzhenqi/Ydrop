"""Async WebDAV client — Python port of WebDavSyncClient.kt."""

from __future__ import annotations

import re
import logging
from base64 import b64encode
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from typing import Optional
from urllib.parse import quote, unquote

import httpx

from .config import get_settings

logger = logging.getLogger("webdav")

PROPFIND_BODY = """\
<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop><d:getlastmodified/></d:prop>
</d:propfind>"""


@dataclass
class RemoteFileInfo:
    path: str
    last_modified: Optional[int] = None  # epoch ms


class WebDavClient:
    def __init__(self, base_url: str = "", username: str = "", password: str = "", folder: str = "") -> None:
        """如果参数为空则 fallback 到 env，后续用 `from_store()` 工厂方法从 settings_store 读。"""
        env_settings = get_settings()
        self.base_url = (base_url or env_settings.webdav_base_url).rstrip("/")
        self.username = username or env_settings.webdav_username
        self.password = password or env_settings.webdav_password
        self.folder = (folder or env_settings.webdav_folder).strip("/") or "ydoc/inbox"
        self._client: Optional[httpx.AsyncClient] = None

    @classmethod
    async def from_store(cls) -> "WebDavClient":
        """从 settings_store 读取最新配置构造客户端。"""
        from . import settings_store
        cfg = await settings_store.get_webdav_config()
        return cls(
            base_url=cfg["base_url"],
            username=cfg["username"],
            password=cfg["password"],
            folder=cfg["folder"],
        )

    @property
    def configured(self) -> bool:
        return bool(self.base_url)

    def _auth_header(self) -> dict[str, str]:
        if not self.username:
            return {}
        raw = f"{self.username}:{self.password}"
        token = b64encode(raw.encode()).decode()
        return {"Authorization": f"Basic {token}"}

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=30, verify=False)
        return self._client

    def _url(self, path: str) -> str:
        return f"{self.base_url}/{path.lstrip('/')}"

    def inbox_folder(self) -> str:
        return self.folder

    def archive_folder(self) -> str:
        segments = [s for s in self.folder.split("/") if s]
        if not segments:
            return "archive"
        if segments[-1].lower() == "inbox":
            segments[-1] = "archive"
        else:
            segments.append("archive")
        return "/".join(segments)

    async def test_connection(self) -> None:
        client = await self._get_client()
        headers = {**self._auth_header(), "Depth": "0"}
        r = await client.request("PROPFIND", self._url(self.folder), headers=headers, content="")
        if r.status_code not in (200, 207, 301, 405):
            if r.status_code == 404:
                await self._ensure_folder(self.folder)
            else:
                raise ConnectionError(f"WebDAV 连接失败: HTTP {r.status_code}")

    async def _ensure_folder(self, folder: str) -> None:
        client = await self._get_client()
        headers = self._auth_header()
        parts = folder.split("/")
        accumulated = ""
        for part in parts:
            accumulated = f"{accumulated}/{part}" if accumulated else part
            r = await client.request("MKCOL", self._url(accumulated), headers=headers)
            if r.status_code not in range(200, 300) and r.status_code not in (405, 301):
                logger.warning("MKCOL %s -> %d", accumulated, r.status_code)

    async def list_remote(self) -> list[RemoteFileInfo]:
        folders = [self.inbox_folder(), self.archive_folder()]
        results: list[RemoteFileInfo] = []
        for folder in folders:
            results.extend(await self._list_folder(folder))
        return results

    async def _list_folder(self, folder: str) -> list[RemoteFileInfo]:
        client = await self._get_client()
        headers = {**self._auth_header(), "Depth": "1", "Content-Type": "application/xml; charset=utf-8"}
        r = await client.request("PROPFIND", self._url(folder), headers=headers, content=PROPFIND_BODY)
        if r.status_code == 404:
            return []
        if r.status_code not in (200, 207):
            logger.error("PROPFIND %s -> %d", folder, r.status_code)
            return []
        return self._parse_propfind(r.text, folder)

    def _parse_propfind(self, xml: str, folder: str) -> list[RemoteFileInfo]:
        results: list[RemoteFileInfo] = []
        resp_pattern = re.compile(r"<[^:>]*:response>(.*?)</[^:>]*:response>", re.DOTALL | re.IGNORECASE)
        href_pattern = re.compile(r"<[^:>]*:href[^>]*>([^<]+)</[^:>]*:href>", re.IGNORECASE)
        lm_pattern = re.compile(r"<[^:>]*:getlastmodified[^>]*>([^<]+)</[^:>]*:getlastmodified>", re.IGNORECASE)

        for resp_match in resp_pattern.finditer(xml):
            resp_text = resp_match.group(1)
            hrefs = href_pattern.findall(resp_text)
            if not hrefs:
                continue
            href = unquote(hrefs[-1].strip())
            if not href.endswith(".md"):
                continue
            filename = href.rsplit("/", 1)[-1]
            path = f"{folder}/{filename}"
            lm_match = lm_pattern.search(resp_text)
            last_modified = self._parse_http_date(lm_match.group(1).strip()) if lm_match else None
            results.append(RemoteFileInfo(path=path, last_modified=last_modified))
        return results

    @staticmethod
    def _parse_http_date(date_str: str) -> Optional[int]:
        try:
            dt = parsedate_to_datetime(date_str)
            return int(dt.timestamp() * 1000)
        except Exception:
            pass
        try:
            from datetime import datetime, timezone
            dt = datetime.strptime(date_str, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except Exception:
            return None

    async def push(self, remote_path: str, content: str) -> None:
        client = await self._get_client()
        folder = remote_path.rsplit("/", 1)[0] if "/" in remote_path else ""
        if folder:
            await self._ensure_folder(folder)
        headers = {**self._auth_header(), "Content-Type": "text/markdown; charset=utf-8"}
        encoded_filename = quote(remote_path.rsplit("/", 1)[-1])
        url = self._url(f"{folder}/{encoded_filename}" if folder else encoded_filename)
        r = await client.put(url, headers=headers, content=content.encode("utf-8"))
        if r.status_code not in range(200, 300):
            raise IOError(f"WebDAV 上传失败: HTTP {r.status_code}")

    async def pull(self, remote_path: str) -> str:
        client = await self._get_client()
        headers = self._auth_header()
        # remote_path may have an encoded filename
        parts = remote_path.rsplit("/", 1)
        if len(parts) == 2:
            url = self._url(f"{parts[0]}/{quote(parts[1])}")
        else:
            url = self._url(quote(remote_path))
        r = await client.get(url, headers=headers)
        if r.status_code != 200:
            raise IOError(f"WebDAV 下载失败: HTTP {r.status_code}")
        return r.text

    async def delete_path(self, remote_path: str) -> None:
        client = await self._get_client()
        headers = self._auth_header()
        parts = remote_path.rsplit("/", 1)
        if len(parts) == 2:
            url = self._url(f"{parts[0]}/{quote(parts[1])}")
        else:
            url = self._url(quote(remote_path))
        r = await client.request("DELETE", url, headers=headers)
        if r.status_code not in range(200, 300) and r.status_code != 404:
            raise IOError(f"WebDAV 删除失败: HTTP {r.status_code}")

    async def close(self) -> None:
        if self._client and not self._client.is_closed:
            await self._client.aclose()
            self._client = None
