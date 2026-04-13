"""统一的 LLM provider 调用层。

支持 OPENAI / ANTHROPIC / AUTO 三种 endpoint_mode，自动处理：
- URL 路径拼接（智能补 /v1）
- 请求/响应格式差异
- system message 处理（Anthropic 是顶层字段）
- response_format（text / json_object）
"""

from __future__ import annotations

import json
import logging
import urllib.request
from typing import Any

logger = logging.getLogger("ai_provider")


class LlmError(Exception):
    """Provider 调用失败时抛出，带 endpoint_mode 和原始错误信息。"""
    def __init__(self, mode: str, message: str, status: int | None = None):
        self.mode = mode
        self.status = status
        super().__init__(f"[{mode}] {message}")


def call_llm(
    messages: list[dict[str, str]],
    cfg: dict[str, Any],
    response_format: str = "text",
    timeout: int = 30,
) -> str:
    """调用 LLM，返回纯文本内容。

    Args:
        messages: [{"role": "user"|"system"|"assistant", "content": "..."}]
        cfg: {"base_url", "token", "model", "endpoint_mode"}
        response_format: "text" 或 "json_object"
        timeout: 秒

    Returns:
        LLM 返回的字符串内容（不含 JSON fence）
    """
    mode = (cfg.get("endpoint_mode") or "AUTO").upper()

    if mode == "ANTHROPIC":
        return _call_anthropic(messages, cfg, response_format, timeout)
    if mode == "OPENAI":
        return _call_openai(messages, cfg, response_format, timeout)

    # AUTO: 先试 OpenAI，失败再试 Anthropic
    try:
        return _call_openai(messages, cfg, response_format, timeout)
    except LlmError as e:
        logger.info("AUTO: OpenAI failed (%s), falling back to Anthropic", e)
        return _call_anthropic(messages, cfg, response_format, timeout)


def _build_url(base_url: str, endpoint: str) -> str:
    """智能拼 URL。
    endpoint 形如 '/chat/completions' 或 '/messages'。
    如果 base_url 以 /v1 结尾，直接拼；否则补 /v1 再拼。
    """
    base = base_url.rstrip("/")
    if base.endswith("/v1"):
        return base + endpoint
    return base + "/v1" + endpoint


def _call_openai(
    messages: list[dict[str, str]],
    cfg: dict[str, Any],
    response_format: str,
    timeout: int,
) -> str:
    body: dict[str, Any] = {
        "model": cfg["model"],
        "messages": messages,
    }
    if response_format == "json_object":
        body["response_format"] = {"type": "json_object"}

    url = _build_url(cfg["base_url"], "/chat/completions")
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": f"Bearer {cfg['token']}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")[:200] if hasattr(e, "read") else str(e)
        raise LlmError("OPENAI", f"HTTP {e.code}: {body_text}", status=e.code) from e
    except Exception as e:
        raise LlmError("OPENAI", f"{type(e).__name__}: {e}") from e

    try:
        j = json.loads(raw)
        return j["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as e:
        raise LlmError("OPENAI", f"unexpected response: {raw[:200]}") from e


def _call_anthropic(
    messages: list[dict[str, str]],
    cfg: dict[str, Any],
    response_format: str,
    timeout: int,
) -> str:
    # Anthropic 要求 system 是顶层字段，messages 不能包含 system role
    system_parts: list[str] = []
    user_messages: list[dict[str, str]] = []
    for m in messages:
        if m["role"] == "system":
            system_parts.append(m["content"])
        else:
            user_messages.append({"role": m["role"], "content": m["content"]})

    if response_format == "json_object" and system_parts:
        # 强化 JSON 输出指令
        system_parts.append("Return only a valid JSON object, no markdown fences, no explanations.")

    body: dict[str, Any] = {
        "model": cfg["model"],
        "max_tokens": 2048,
        "messages": user_messages or [{"role": "user", "content": "hello"}],
    }
    if system_parts:
        body["system"] = "\n\n".join(system_parts)

    url = _build_url(cfg["base_url"], "/messages")
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "x-api-key": cfg["token"],
            "Authorization": f"Bearer {cfg['token']}",  # 兼容网关
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")[:200] if hasattr(e, "read") else str(e)
        raise LlmError("ANTHROPIC", f"HTTP {e.code}: {body_text}", status=e.code) from e
    except Exception as e:
        raise LlmError("ANTHROPIC", f"{type(e).__name__}: {e}") from e

    try:
        j = json.loads(raw)
        # Anthropic: {"content": [{"type": "text", "text": "..."}], ...}
        content = j.get("content") or []
        if isinstance(content, list) and content:
            texts = [c.get("text", "") for c in content if c.get("type") == "text"]
            return "\n".join(texts).strip()
        # 某些网关可能返回 OpenAI 风格，兜底
        if "choices" in j:
            return j["choices"][0]["message"]["content"]
        raise LlmError("ANTHROPIC", f"no content in response: {raw[:200]}")
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as e:
        raise LlmError("ANTHROPIC", f"unexpected response: {raw[:200]}") from e


def strip_json_fence(text: str) -> str:
    """清理模型返回中可能的 markdown code fence。"""
    s = text.strip()
    if s.startswith("```"):
        # 去掉 ```json 或 ``` 开头
        lines = s.split("\n")
        if lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        s = "\n".join(lines).strip()
    return s
