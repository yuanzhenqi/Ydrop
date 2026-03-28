from __future__ import annotations

from fastapi import Header, HTTPException, status

from .config import get_settings


def require_relay_token(x_relay_token: str | None = Header(default=None)) -> None:
    expected = get_settings().relay_token
    if not expected or x_relay_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid relay token",
        )
