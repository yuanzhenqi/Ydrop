from __future__ import annotations

import json
import re
import urllib.request
from datetime import datetime, timedelta, timezone

from .config import get_settings
from .models import (
    AiAnalyzeRequest,
    AiAnalyzeResponse,
    ExtractedEntityResponse,
    ReminderCandidateResponse,
)


def analyze_note(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    settings = get_settings()
    if settings.ai_provider_base_url and settings.ai_provider_api_key:
        try:
            return _analyze_with_provider(payload)
        except Exception:
            pass
    return _analyze_heuristically(payload)


def _analyze_with_provider(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    settings = get_settings()
    request_body = json.dumps(
        {
            "model": settings.ai_provider_model or payload.model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Return only JSON with keys summary, suggestedTitle, "
                        "suggestedCategory, suggestedPriority, todoItems, "
                        "extractedEntities, reminderCandidates. "
                        "suggestedCategory must be NOTE, TODO, TASK, or REMINDER. "
                        "suggestedPriority must be LOW, MEDIUM, HIGH, or URGENT. "
                        "reminderCandidates[].scheduledAt must be unix milliseconds."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(payload.model_dump(mode="json"), ensure_ascii=False),
                },
            ],
            "response_format": {"type": "json_object"},
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        settings.ai_provider_base_url.rstrip("/") + "/chat/completions",
        data=request_body,
        method="POST",
        headers={
            "Authorization": f"Bearer {settings.ai_provider_api_key}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload_json = json.loads(response.read().decode("utf-8"))
    content = payload_json["choices"][0]["message"]["content"]
    return AiAnalyzeResponse.model_validate_json(_strip_json_fence(content))


def _strip_json_fence(content: str) -> str:
    normalized = content.strip()
    fenced = re.match(r"^```(?:json)?\s*(.*?)\s*```$", normalized, flags=re.DOTALL)
    if fenced:
        return fenced.group(1).strip()
    return normalized


def _analyze_heuristically(payload: AiAnalyzeRequest) -> AiAnalyzeResponse:
    content = (payload.transcript or payload.content or "").strip()
    summary = _first_sentence(content)
    todo_items = _extract_todos(content)
    reminder_candidates = _extract_reminder_candidates(content, payload.title)
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


def _extract_reminder_candidates(content: str, title: str) -> list[ReminderCandidateResponse]:
    now = datetime.now(timezone.utc)
    candidates: list[ReminderCandidateResponse] = []
    if "明天" in content:
        target = (now + timedelta(days=1)).astimezone().replace(hour=9, minute=0, second=0, microsecond=0)
        candidates.append(
            ReminderCandidateResponse(
                title=title or "明天提醒",
                scheduledAt=int(target.timestamp() * 1000),
                reason="识别到“明天”时间表达",
            )
        )
    if "今晚" in content or "今天晚上" in content:
        target = now.astimezone().replace(hour=20, minute=0, second=0, microsecond=0)
        if target.timestamp() * 1000 <= datetime.now().timestamp() * 1000:
            target = target + timedelta(days=1)
        candidates.append(
            ReminderCandidateResponse(
                title=title or "今晚提醒",
                scheduledAt=int(target.timestamp() * 1000),
                reason="识别到“今晚”时间表达",
            )
        )
    return candidates[:3]


def _suggest_priority(content: str) -> str:
    if any(keyword in content for keyword in ("尽快", "马上", "紧急", "立刻", "asap", "ASAP")):
        return "URGENT"
    if any(keyword in content for keyword in ("今天", "本周", "重要", "截止")):
        return "HIGH"
    return "MEDIUM"
