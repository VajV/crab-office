"""Simple user-intent parser for runtime office commands."""

from __future__ import annotations

import re

ROLE_ALIASES = {
    "seo": "seo",
    "сео": "seo",
    "developer": "developer",
    "dev": "developer",
    "разработчик": "developer",
    "architect": "architect",
    "архитектор": "architect",
    "analyst": "analyst",
    "аналитик": "analyst",
    "copywriter": "copywriter",
    "копирайтер": "copywriter",
    "manager": "manager",
    "менеджер": "manager",
    "designer": "designer",
    "дизайнер": "designer",
    "qa": "qa",
}


def parse_runtime_command(text: str) -> dict | None:
    normalized = text.strip().lower()
    if not normalized:
        return None

    task_match = re.search(r"(?:дай|назначь|assign)\s+([\wа-я-]+)?\s*задач[ау]?\s+(.+)", normalized, re.IGNORECASE)
    if task_match:
        role_token = (task_match.group(1) or "").strip().lower()
        title = task_match.group(2).strip()
        payload = {"title": title, "description": title}
        role = ROLE_ALIASES.get(role_token) if role_token else None
        if role:
            payload["role"] = role
        return {
            "commandType": "assign_task",
            "payload": payload,
        }

    interaction_match = re.search(r"(?:пусть|let)\s+([\wа-я-]+)\s+(?:поговорит|talk)\s+(?:с|to)\s+([\wа-я-]+)", normalized, re.IGNORECASE)
    if interaction_match:
        initiator_role = ROLE_ALIASES.get(interaction_match.group(1).strip().lower())
        target_role = ROLE_ALIASES.get(interaction_match.group(2).strip().lower())
        if initiator_role and target_role:
            return {
                "commandType": "start_interaction",
                "payload": {
                    "initiatorRole": initiator_role,
                    "targetRole": target_role,
                    "interactionType": "discussion",
                    "summary": f"{initiator_role} coordinating with {target_role}",
                },
            }

    match = re.search(r"(?:создай|create)\s+([\wа-я-]+)", normalized, re.IGNORECASE)
    if not match:
        return None

    role_token = match.group(1).strip().lower()
    role = ROLE_ALIASES.get(role_token)
    if not role:
        return None

    return {
        "commandType": "spawn_agent",
        "payload": {
            "role": role,
        },
    }
