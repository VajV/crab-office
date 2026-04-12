"""Crab Architect — generates office layout via OpenRouter (with mock fallback)."""

from __future__ import annotations

import json
import logging
import os

from openai import AsyncOpenAI

from models import AgentOut, LayoutOut, Position, RoomOut

logger = logging.getLogger(__name__)

DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v3.2"
DEFAULT_OPENROUTER_APP_TITLE = "Crab Office"

SYSTEM_PROMPT = """\
You are the Crab Architect. Given a user request, return a JSON object describing a virtual pixel-art office room.
The JSON must follow this schema exactly (no extra keys):
{
  "roomName": "string",
  "theme": "string (tech | cozy | creative)",
  "layout": {"width": int, "height": int, "backgroundPreset": "string"},
  "agents": [
    {"externalId": "string", "name": "string", "role": "string (architect|developer|analyst|manager)", "position": {"x": int, "y": int}, "state": "idle"}
  ]
}
Generate 3-6 agents with unique roles and positions that fit inside the layout grid.
Return ONLY valid JSON, no markdown fences, no explanation.
"""

_MOCK_ROOM = RoomOut(
    roomName="Mock Dev Office",
    theme="tech",
    layout=LayoutOut(width=12, height=8, backgroundPreset="tech"),
    agents=[
        AgentOut(externalId="agent-architect-1", name="Crab Architect", role="architect", position=Position(x=2, y=3), state="idle"),
        AgentOut(externalId="agent-dev-1", name="Crab Developer", role="developer", position=Position(x=5, y=4), state="idle"),
        AgentOut(externalId="agent-analyst-1", name="Crab Analyst", role="analyst", position=Position(x=8, y=2), state="idle"),
        AgentOut(externalId="agent-manager-1", name="Crab Manager", role="manager", position=Position(x=10, y=6), state="idle"),
    ],
)


def _get_model_name() -> str:
    return os.getenv("OPENROUTER_MODEL", DEFAULT_OPENROUTER_MODEL)


def get_llm_status() -> dict[str, str]:
    api_key = os.getenv("OPENROUTER_API_KEY", "")
    return {
        "provider": "openrouter" if api_key else "mock",
        "model": _get_model_name(),
    }


def _get_default_headers() -> dict[str, str]:
    headers: dict[str, str] = {}
    http_referer = os.getenv("OPENROUTER_HTTP_REFERER", "").strip()
    app_title = os.getenv("OPENROUTER_APP_TITLE", DEFAULT_OPENROUTER_APP_TITLE).strip()

    if http_referer:
        headers["HTTP-Referer"] = http_referer
    if app_title:
        headers["X-OpenRouter-Title"] = app_title

    return headers


def _get_client() -> AsyncOpenAI:
    client_kwargs = {
        "base_url": "https://openrouter.ai/api/v1",
        "api_key": os.getenv("OPENROUTER_API_KEY", ""),
    }
    default_headers = _get_default_headers()
    if default_headers:
        client_kwargs["default_headers"] = default_headers
    return AsyncOpenAI(**client_kwargs)


async def generate_room(prompt: str, preset: str) -> RoomOut:
    """Call OpenRouter to generate a room. Falls back to mock on any error."""
    api_key = os.getenv("OPENROUTER_API_KEY", "")
    model = _get_model_name()
    if not api_key:
        logger.warning("OPENROUTER_API_KEY not set in ai-service-python/.env — returning mock room")
        return _MOCK_ROOM

    try:
        client = _get_client()
        logger.info("Calling OpenRouter model %s", model)
        resp = await client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"Preset: {preset}. Request: {prompt}"},
            ],
            temperature=0.7,
            max_tokens=1024,
        )
        raw = resp.choices[0].message.content or ""
        raw = raw.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        data = json.loads(raw)
        return RoomOut(**data)
    except Exception:
        logger.exception("OpenRouter call failed — returning mock room")
        return _MOCK_ROOM
