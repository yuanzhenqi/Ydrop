from __future__ import annotations

from fastapi import Header, HTTPException, status

from .config import get_settings


def require_relay_token(
    authorization: str | None = Header(default=None),
    x_relay_token: str | None = Header(default=None),
) -> None:
    expected = get_settings().relay_token
    bearer_token = None
    if authorization and authorization.lower().startswith("bearer "):
        bearer_token = authorization[7:].strip()

    provided_token = bearer_token or x_relay_token
    if not expected or provided_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid relay token",
        )
