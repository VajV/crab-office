"""OpenClaw HTTP streaming client.

Uses the OpenAI-compatible /v1/chat/completions endpoint with stream=true
to get SSE text chunks.  Publishes AgentAction events to Redis so the
UI can visualize tool usage in real time.  Also publishes text-chunk events
so the frontend can render the response progressively.
"""

from __future__ import annotations

import json
import logging
import os
import re
from datetime import datetime, timezone
from typing import Callable, Optional

import httpx

from models import AgentAction

logger = logging.getLogger(__name__)

OPENCLAW_HTTP_URL = os.getenv(
    "OPENCLAW_HTTP_URL",
    "http://localhost:18789/v1/chat/completions",
)
OPENCLAW_TOKEN = os.getenv(
    "OPENCLAW_GATEWAY_TOKEN",
    "crab-office-dev-token-change-me",
)
OPENCLAW_MODEL = os.getenv("OPENCLAW_MODEL", "openclaw")
OPENCLAW_TIMEOUT = int(os.getenv("OPENCLAW_TIMEOUT", "120"))

# ── Heuristic tool-call detection from response text ──────────────────────────

# Patterns that indicate OpenClaw used a tool internally.
# These appear in the final text output even though tool_calls are hidden.
_TOOL_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("web_fetch",   re.compile(r"(?:🌐|web_fetch|fetching|searching the web|поиск в интернет)", re.I)),
    ("exec",        re.compile(r"(?:💻|executing|running command|выполня[юе]|запуска[юе]|```(?:bash|sh)\n)", re.I)),
    ("file_write",  re.compile(r"(?:✍️|writing file|creating file|записыва[юе]|создаю файл)", re.I)),
    ("file_read",   re.compile(r"(?:📄|reading file|чита[юе] файл)", re.I)),
    ("browser",     re.compile(r"(?:🖥️|opening browser|browsing|открыва[юе] браузер)", re.I)),
    ("thinking",    re.compile(r"(?:🧠|thinking|размышля[юе]|анализиру[юе])", re.I)),
]


def _detect_tool_mentions(text: str) -> list[str]:
    """Return list of actionType strings detected heuristically in *text*."""
    seen: list[str] = []
    for action_type, pattern in _TOOL_PATTERNS:
        if pattern.search(text) and action_type not in seen:
            seen.append(action_type)
    return seen


async def chat_stream(
    messages: list[dict],
    *,
    room_id: int,
    agent_external_id: str,
    on_action: Optional[Callable] = None,
    on_text_chunk: Optional[Callable] = None,
) -> Optional[str]:
    """Send a chat request with stream=true and collect the full response.

    *on_action*     — called with ``AgentAction`` on tool-call detection.
    *on_text_chunk* — called with ``dict(text=..., room_id=..., ...)`` for
                      each SSE text delta so the UI can stream.
    """
    body = {
        "model": OPENCLAW_MODEL,
        "stream": True,
        "messages": messages,
    }

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {OPENCLAW_TOKEN}",
    }

    collected: list[str] = []
    emitted_tools: set[str] = set()  # track which heuristic tools we already emitted

    try:
        async with httpx.AsyncClient(timeout=OPENCLAW_TIMEOUT) as client:
            async with client.stream(
                "POST",
                OPENCLAW_HTTP_URL,
                json=body,
                headers=headers,
            ) as resp:
                if resp.status_code != 200:
                    error_body = await resp.aread()
                    logger.error(
                        "OpenClaw HTTP %d: %s", resp.status_code, error_body[:500]
                    )
                    return None

                async for line in resp.aiter_lines():
                    if not line.startswith("data: "):
                        continue
                    data = line[6:]
                    if data.strip() == "[DONE]":
                        break

                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        continue

                    delta = (
                        chunk.get("choices", [{}])[0]
                        .get("delta", {})
                    )

                    # --- text content ---
                    content = delta.get("content")
                    if content:
                        collected.append(content)

                        # Publish text chunk for streaming UI
                        if on_text_chunk:
                            await on_text_chunk({
                                "roomId": room_id,
                                "agentExternalId": agent_external_id,
                                "chunk": content,
                                "timestamp": datetime.now(timezone.utc).isoformat(),
                            })

                        # Heuristic tool detection in accumulated text
                        if on_action:
                            full_so_far = "".join(collected)
                            for tool_type in _detect_tool_mentions(full_so_far):
                                if tool_type not in emitted_tools:
                                    emitted_tools.add(tool_type)
                                    action = AgentAction(
                                        roomId=room_id,
                                        agentExternalId=agent_external_id,
                                        actionType=tool_type,
                                        toolName=tool_type,
                                        status="completed",
                                        params={},
                                        result="Detected in response text",
                                        timestamp=datetime.now(timezone.utc).isoformat(),
                                    )
                                    await on_action(action)

                    # --- tool_calls in delta (OpenAI format) ---
                    tool_calls = delta.get("tool_calls")
                    if tool_calls and on_action:
                        for tc in tool_calls:
                            fn = tc.get("function", {})
                            tool_name = fn.get("name", "unknown")
                            args_str = fn.get("arguments", "{}")
                            try:
                                params = json.loads(args_str)
                            except (json.JSONDecodeError, TypeError):
                                params = {"raw": args_str}

                            action = AgentAction(
                                roomId=room_id,
                                agentExternalId=agent_external_id,
                                actionType="tool_call",
                                toolName=tool_name,
                                status="started",
                                params=params,
                                timestamp=datetime.now(timezone.utc).isoformat(),
                            )
                            await on_action(action)

        full_text = "".join(collected)
        if not full_text:
            return None
        return full_text

    except httpx.TimeoutException:
        logger.error("OpenClaw HTTP request timed out after %ds", OPENCLAW_TIMEOUT)
        return None
    except Exception:
        logger.exception("OpenClaw HTTP streaming error")
        return None
