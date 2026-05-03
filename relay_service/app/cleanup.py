from __future__ import annotations

import asyncio
import logging
import os
import time

from .config import get_settings
from .storage import RelayStorage


async def cleanup_loop() -> None:
    settings = get_settings()
    storage = RelayStorage()
    logger = logging.getLogger("relay.cleanup")
    while True:
        removed = storage.cleanup_expired()
        if removed:
            logger.info("Removed %s expired relay files", len(removed))
        # 兜底清理：图片附件超过 30 天没访问的认为是孤儿（客户端 DELETE 没送到或 note 已删）
        orphan_count = _cleanup_orphan_images(settings, max_age_days=30)
        if orphan_count:
            logger.info("Removed %s orphan image attachments", orphan_count)
        await asyncio.sleep(settings.cleanup_interval_seconds)


def _cleanup_orphan_images(settings, max_age_days: int) -> int:
    """扫 static/images/ 下 mtime 超过 max_age_days 的文件删掉。
    属于"客户端没送到 DELETE"的兜底：客户端每次正常删附件都会走 DELETE /api/images/{filename}，
    这里只是多一层保险，阈值设大一些避免误删正在用的。"""
    images_dir = os.path.join(str(settings.static_dir), "images")
    if not os.path.isdir(images_dir):
        return 0
    cutoff = time.time() - max_age_days * 24 * 3600
    removed = 0
    try:
        for name in os.listdir(images_dir):
            p = os.path.join(images_dir, name)
            if not os.path.isfile(p):
                continue
            try:
                # 用 atime（如果文件系统启用），否则 mtime——mtime 更稳定
                if os.path.getmtime(p) < cutoff:
                    os.remove(p)
                    removed += 1
            except OSError:
                pass
    except OSError:
        pass
    return removed
