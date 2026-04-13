from __future__ import annotations

import json
import logging
import re
import urllib.request
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .config import get_settings
from .models import (
    AiAnalyzeRequest,
    AiAnalyzeResponse,
    ExtractedEntityResponse,
    ReminderCandidateResponse,
)

logger = logging.getLogger(__name__)

DEFAULT_ANALYSIS_PROMPT = (
    "You are the structured extraction engine for a personal assistant app. "
    "Analyze the note, transcript, and current context to produce structured help for reminders, schedules, tasks, and general organization. "
    "Always write a concise non-empty summary using the user's own perspective and wording style. "
    "Never describe the note as 'the user said', 'the user wants', or similar third-person phrasing. "
    "Use the provided current system time and timezone to resolve relative dates and times. "
    "If the note clearly asks for a reminder or contains a concrete future time, prefer suggestedCategory = REMINDER and create reminderCandidates. "
    "If the note is more like a planned task or schedule but the exact reminder time is not reliable enough, prefer suggestedCategory = TASK and leave reminderCandidates empty. "
    "If the note is mainly a plain record with no action or time intent, prefer suggestedCategory = NOTE. "
    "Suggest a clearer title only when the current title is vague. "
    "Suggest priority only when it is reasonably inferable; otherwise default to MEDIUM. "
    "Extract concrete todo items from explicit or strongly implied actions. "
    "Extract useful entities such as intent, people, dates, times, places, organizations, projects, and reference values when relevant. "
    "reminderCandidates[].scheduledAt must represent an absolute Unix millisecond timestamp, not a formatted date string. "
    "Do not guess reminder times when the time expression is too ambiguous to resolve with confidence."
)

STRUCTURED_OUTPUT_REQUIREMENTS = (
    "Return exactly one raw JSON object and nothing else. "
    "Do not use markdown fences. "
    "Do not add explanations, headings, notes, comments, or trailing text. "
    "The JSON object may only contain these keys: summary, suggestedTitle, suggestedCategory, "
    "suggestedPriority, todoItems, extractedEntities, reminderCandidates. "
    "Use null for missing suggestedTitle, suggestedCategory, and suggestedPriority. "
    "Use [] for empty todoItems, extractedEntities, and reminderCandidates. "
    "suggestedCategory must be NOTE, TODO, TASK, or REMINDER. "
    "suggestedPriority must be LOW, MEDIUM, HIGH, or URGENT. "
    "extractedEntities items must be objects with keys label and value. "
    "reminderCandidates items must be objects with keys title, scheduledAt, and reason. "
    "reminderCandidates[].scheduledAt must be unix milliseconds. "
    "Valid example JSON object: "
    "{\"summary\":\"The user wants a reminder for tomorrow at 9 AM to submit the weekly report.\","
    "\"suggestedTitle\":\"Submit weekly report\","
    "\"suggestedCategory\":\"REMINDER\","
    "\"suggestedPriority\":\"HIGH\","
    "\"todoItems\":[\"Submit weekly report\"],"
    "\"extractedEntities\":[{\"label\":\"time\",\"value\":\"tomorrow 9 AM\"},{\"label\":\"task\",\"value\":\"weekly report\"}],"
    "\"reminderCandidates\":[{\"title\":\"Submit weekly report\",\"scheduledAt\":1736384400000,\"reason\":\"The user explicitly asked to be reminded tomorrow at 9 AM.\"}]}"
)


def build_system_prompt(payload: AiAnalyzeRequest) -> str:
    context = _resolve_time_context(payload)
    base_prompt = _build_base_prompt(payload.prompt, context)
    return (
        f"Current system time: {context['current_time']}\n"
        f"Current system timezone: {context['current_timezone']}\n"
        f"Current system Unix milliseconds: {context['current_time_ms']}\n"
        "Resolve all relative dates and times against this context.\n\n"
        f"{base_prompt}\n\n{STRUCTURED_OUTPUT_REQUIREMENTS}"
    )


def _build_base_prompt(prompt: str | None, context: dict[str, str]) -> str:
    supplement = _render_prompt_variables((prompt or "").strip(), context)
    if not supplement:
        return DEFAULT_ANALYSIS_PROMPT
    return f"{DEFAULT_ANALYSIS_PROMPT}\n\nAdditional instructions from the user:\n{supplement}"


def _provider_payload(payload: AiAnalyzeRequest) -> dict:
    return payload.model_dump(mode="json", exclude={"prompt"})


def analyze_note(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    cfg = _resolve_ai_config()
    if cfg["enabled"] and cfg["base_url"] and cfg["token"]:
        try:
            return _analyze_with_provider(payload, cfg)
        except Exception as exc:
            logger.warning(
                "AI provider analysis failed in provider mode, falling back to heuristic (%s: %s)",
                type(exc).__name__,
                exc,
                exc_info=True,
            )
    return _analyze_heuristically(payload)


def _resolve_ai_config() -> dict:
    """从 settings_store 同步读取 AI 配置（ai.py 是同步调用链）。
    把 async store 包装成同步使用——用 asyncio.run_coroutine_threadsafe 或直接同步读 DB。
    简化：用 sqlite3 同步直读（和 aiosqlite 的文件相同）。"""
    import sqlite3
    from .config import get_settings
    env = get_settings()
    try:
        conn = sqlite3.connect(env.sqlite_db_path)
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT key, value FROM app_settings WHERE key LIKE 'ai.%'").fetchall()
        conn.close()
        kv = {r["key"]: r["value"] for r in rows}
        return {
            "enabled": (kv.get("ai.enabled", "false").lower() == "true"),
            "base_url": kv.get("ai.base_url", "") or env.ai_provider_base_url,
            "token": kv.get("ai.token", "") or env.ai_provider_api_key,
            "model": kv.get("ai.model", "") or env.ai_provider_model,
            "endpoint_mode": kv.get("ai.endpoint_mode", "AUTO"),
        }
    except Exception:
        return {
            "enabled": bool(env.ai_provider_base_url and env.ai_provider_api_key),
            "base_url": env.ai_provider_base_url,
            "token": env.ai_provider_api_key,
            "model": env.ai_provider_model,
            "endpoint_mode": "AUTO",
        }


def _analyze_with_provider(payload: AiAnalyzeRequest, cfg: dict | None = None) -> AiAnalyzeResponse:
    from .ai_provider import call_llm

    if cfg is None:
        cfg = _resolve_ai_config()

    # 确保 model 有值
    if not cfg.get("model"):
        cfg = {**cfg, "model": payload.model or "gpt-4o"}

    messages = [
        {"role": "system", "content": build_system_prompt(payload)},
        {"role": "user", "content": json.dumps(_provider_payload(payload), ensure_ascii=False)},
    ]
    content = call_llm(messages, cfg, response_format="json_object", timeout=20)
    response = _decode_structured_response(content)
    if response.is_effectively_empty():
        logger.warning(
            "AI provider returned an empty structured result, falling back to minimal summary. Preview: %s",
            _safe_preview(content),
        )
        return _minimal_summary_response(payload)
    return response


def _strip_json_fence(content: str) -> str:
    normalized = content.strip()
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```$", normalized, flags=re.DOTALL)
    if fenced:
        return fenced.group(1).strip()
    return normalized


def _decode_structured_response(content: str) -> AiAnalyzeResponse:
    normalized = _strip_json_fence(content)
    candidate = normalized if _looks_like_json_object(normalized) else _extract_first_json_object(normalized)
    if not candidate:
        raise ValueError(f"AI provider returned non-JSON text. Preview: {_safe_preview(content)}")
    try:
        response = AiAnalyzeResponse.model_validate_json(candidate)
    except Exception as exc:
        raise ValueError(f"AI provider returned malformed JSON. Preview: {_safe_preview(candidate)}") from exc
    return response


def _looks_like_json_object(content: str) -> bool:
    normalized = content.strip()
    return normalized.startswith("{") and normalized.endswith("}")


def _extract_first_json_object(content: str) -> str | None:
    start = content.find("{")
    while start >= 0:
        depth = 0
        in_string = False
        escaping = False

        for index in range(start, len(content)):
            ch = content[index]
            if in_string:
                if escaping:
                    escaping = False
                elif ch == "\\":
                    escaping = True
                elif ch == '"':
                    in_string = False
                continue

            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return content[start : index + 1]
        start = content.find("{", start + 1)
    return None


def _safe_preview(content: str, max_length: int = 220) -> str:
    compact = re.sub(r"\s+", " ", content).strip()
    if not compact:
        return "(empty)"
    if len(compact) <= max_length:
        return compact
    return compact[:max_length] + "..."


def _analyze_heuristically(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    content = (payload.transcript or payload.content or "").strip()
    summary = _first_sentence(content)
    todo_items = _extract_todos(content)
    reminder_candidates = _extract_reminder_candidates(content, payload)
    extracted_entities = _extract_entities(content)
    suggested_category = payload.category
    if todo_items:
        suggested_category = "TODO"
    elif reminder_candidates:
        suggested_category = "REMINDER"
    return AiAnalyzeResponse(
        summary=summary or "这条便签适合进一步整理。",
        suggestedTitle=(summary or payload.title or "便签整理")[:24],
        suggestedCategory=suggested_category,
        suggestedPriority=_suggest_priority(content),
        todoItems=todo_items,
        extractedEntities=extracted_entities,
        reminderCandidates=reminder_candidates,
    )


def _minimal_summary_response(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    return AiAnalyzeResponse(
        summary=_minimal_summary_text(payload),
        suggestedTitle=None,
        suggestedCategory=None,
        suggestedPriority=None,
        todoItems=[],
        extractedEntities=[],
        reminderCandidates=[],
    )


def _minimal_summary_text(payload: AiAnalyzeRequest) -> str:
    source = (payload.transcript or "").strip() or (payload.content or "").strip() or (payload.title or "").strip()
    if not source:
        return "这条便签已保存，可稍后再整理。"
    normalized = re.sub(r"\s+", " ", source).strip()
    return (_first_sentence(normalized) or normalized)[:96] or "这条便签已保存，可稍后再整理。"


def _first_sentence(content: str) -> str:
    normalized = re.sub(r"\s+", " ", content).strip()
    if not normalized:
        return ""
    for marker in ("。", "！", "？", ".", "!", "?"):
        if marker in normalized:
            return normalized.split(marker, 1)[0].strip()
    return normalized[:60]


def _extract_todos(content: str) -> list[str]:
    todos: list[str] = []
    for line in [part.strip() for part in content.splitlines() if part.strip()]:
        if line.startswith(("- ", "* ", "• ", "[ ]")):
            todos.append(line.replace("[ ]", "", 1).lstrip("-*• ").strip())
        elif any(keyword in line for keyword in ("待办", "需要", "记得", "todo", "TODO")):
            todos.append(line)
    return list(dict.fromkeys([item for item in todos if item]))[:6]


def _extract_entities(content: str) -> list[ExtractedEntityResponse]:
    entities: list[ExtractedEntityResponse] = []
    for phone in re.findall(r"1\d{10}", content):
        entities.append(ExtractedEntityResponse(label="电话", value=phone))
    for amount in re.findall(r"\d+(?:\.\d+)?(?:元|块|人民币)", content):
        entities.append(ExtractedEntityResponse(label="金额", value=amount))
    if "会议" in content:
        entities.append(ExtractedEntityResponse(label="事件", value="会议"))
    return entities[:6]


def _extract_reminder_candidates(content: str, payload: AiAnalyzeRequest) -> list[ReminderCandidateResponse]:
    now = _resolve_reference_datetime(payload)
    candidates: list[ReminderCandidateResponse] = []
    if "明天" in content:
        target = (now + timedelta(days=1)).replace(hour=9, minute=0, second=0, microsecond=0)
        candidates.append(
            ReminderCandidateResponse(
                title=payload.title or "明天提醒",
                scheduledAt=int(target.timestamp() * 1000),
                reason="识别到“明天”时间表达",
            )
        )
    if "今晚" in content or "今天晚上" in content:
        target = now.replace(hour=20, minute=0, second=0, microsecond=0)
        if target.timestamp() * 1000 <= now.timestamp() * 1000:
            target = target + timedelta(days=1)
        candidates.append(
            ReminderCandidateResponse(
                title=payload.title or "今晚提醒",
                scheduledAt=int(target.timestamp() * 1000),
                reason="识别到“今晚”时间表达",
            )
        )
    return candidates[:3]


def _resolve_time_context(payload: AiAnalyzeRequest) -> dict[str, str]:
    current_time_ms = payload.currentTimeEpochMs or int(datetime.now(timezone.utc).timestamp() * 1000)
    local_tz = datetime.now().astimezone().tzinfo
    timezone_name = (payload.currentTimezone or "").strip() or (local_tz.tzname(None) if local_tz else None) or "UTC"
    current_time = (payload.currentTimeText or "").strip()
    if not current_time:
        current_time = _resolve_reference_datetime(payload).strftime("%Y-%m-%d %H:%M:%S")
    return {
        "current_time": current_time,
        "current_timezone": timezone_name,
        "current_time_ms": str(current_time_ms),
    }


def _render_prompt_variables(prompt: str, context: dict[str, str]) -> str:
    return (
        prompt
        .replace("{{current_time}}", context["current_time"])
        .replace("{{current_timezone}}", context["current_timezone"])
        .replace("{{current_time_ms}}", context["current_time_ms"])
    )


def _resolve_reference_datetime(payload: AiAnalyzeRequest) -> datetime:
    current_time_ms = payload.currentTimeEpochMs or int(datetime.now(timezone.utc).timestamp() * 1000)
    base_utc = datetime.fromtimestamp(current_time_ms / 1000, tz=timezone.utc)
    timezone_name = (payload.currentTimezone or "").strip()
    if timezone_name:
        try:
            return base_utc.astimezone(ZoneInfo(timezone_name))
        except Exception:
            pass
    return base_utc.astimezone()


def _suggest_priority(content: str) -> str:
    if any(keyword in content for keyword in ("尽快", "马上", "紧急", "立刻", "asap", "ASAP")):
        return "URGENT"
    if any(keyword in content for keyword in ("今天", "本周", "重要", "截止")):
        return "HIGH"
    return "MEDIUM"
