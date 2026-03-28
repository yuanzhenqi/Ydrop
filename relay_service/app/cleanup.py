from __future__ import annotations

import asyncio
import logging

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
        await asyncio.sleep(settings.cleanup_interval_seconds)
