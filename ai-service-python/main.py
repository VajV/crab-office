"""FastAPI entry point for Crab Office AI service.

Provides room generation and file sandbox APIs.
Agent brain logic is handled by OpenClaw Gateway.
"""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path

import redis.asyncio as aioredis
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException

import openclaw_http
import openclaw_ws
from agent_logic import move_agent
from architect import generate_room, get_llm_status
from file_tools import execute_tool, list_files_structured, read_file_payload
from models import (
    AgentAction,
    AgentEvent,
    GenerateRequest,
    Position,
    RoomOut,
    SandboxFileContentResponse,
    SandboxFileListResponse,
)

load_dotenv(Path(__file__).resolve().with_name(".env"))

logging.basicConfig(level=logging.INFO)


app = FastAPI(title="Crab Office AI Service", version="0.3.0")


@app.get("/health")
async def health():
    return {"status": "ok", **get_llm_status()}


@app.post("/generate", response_model=RoomOut)
async def generate(req: GenerateRequest):
    """Generate an office room based on user prompt."""
    room = await generate_room(req.prompt, req.preset)
    return room


@app.post("/agents/event", response_model=AgentEvent)
async def agent_event(
    room_id: int,
    agent_external_id: str,
    current_state: str,
    target_state: str,
    x: int,
    y: int,
    message: str = "",
):
    """Trigger an agent state change and publish it via Redis."""
    result = await move_agent(
        room_id=room_id,
        agent_external_id=agent_external_id,
        current_state=current_state,
        target_state=target_state,
        new_position=Position(x=x, y=y),
        message=message,
    )
    if result is None:
        raise HTTPException(status_code=400, detail=f"Invalid transition {current_state} -> {target_state}")
    return result


@app.get("/rooms/{room_id}/files", response_model=SandboxFileListResponse)
async def list_room_files(room_id: int):
    return SandboxFileListResponse(roomId=room_id, files=list_files_structured(room_id))


@app.get("/rooms/{room_id}/files/content", response_model=SandboxFileContentResponse)
async def read_room_file(room_id: int, path: str):
    try:
        payload = read_file_payload(room_id, path)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File not found: {path}")
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return SandboxFileContentResponse(roomId=room_id, **payload)


@app.post("/chat")
async def chat(body: dict):
    """Bridge from Java's OpenAI-compatible HTTP POST to OpenClaw WebSocket.

    Accepts the same request shape that Java's OpenClawService sends:
      { "model": "...", "stream": false, "messages": [...] }

    Returns an OpenAI-compatible response:
      { "choices": [{ "message": { "role": "assistant", "content": "..." } }] }
    """
    messages: list = body.get("messages", [])
    system_prompt: str | None = next(
        (m.get("content") for m in messages if m.get("role") == "system"), None
    )
    user_message: str = next(
        (m.get("content", "") for m in reversed(messages) if m.get("role") == "user"),
        "",
    )

    if not user_message:
        raise HTTPException(status_code=400, detail="No user message in request")

    reply = await openclaw_ws.chat(user_message, system_prompt=system_prompt)

    if not reply:
        raise HTTPException(status_code=502, detail="OpenClaw did not return a response")

    return {
        "choices": [
            {"message": {"role": "assistant", "content": reply}}
        ]
    }


# ── Redis helper for action publishing ───────────────────────────────────────

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
ACTION_CHANNEL = "crab:agent-actions"
CHAT_STREAM_CHANNEL = "crab:chat-stream"


async def _publish_action(action: AgentAction) -> None:
    """Publish an AgentAction to Redis Pub/Sub."""
    try:
        r = aioredis.from_url(REDIS_URL)
        await r.publish(ACTION_CHANNEL, action.model_dump_json())
        await r.aclose()
    except Exception:
        logging.getLogger(__name__).exception("Failed to publish action to Redis")


async def _publish_text_chunk(chunk_payload: dict) -> None:
    """Publish a text chunk to Redis so Java can relay to STOMP."""
    try:
        r = aioredis.from_url(REDIS_URL)
        await r.publish(CHAT_STREAM_CHANNEL, json.dumps(chunk_payload))
        await r.aclose()
    except Exception:
        logging.getLogger(__name__).exception("Failed to publish text chunk to Redis")


# ── Chat-stream endpoint (HTTP SSE via OpenClaw + Redis actions) ─────────────

@app.post("/chat-stream")
async def chat_stream(body: dict):
    """Streaming chat via OpenClaw HTTP SSE.

    Accepts OpenAI-compatible request + room_id & agent_external_id.
    Publishes AgentAction events to Redis as side effect.
    Returns the same OpenAI-compatible response as /chat.
    """
    messages: list = body.get("messages", [])
    room_id: int = body.get("room_id", 0)
    agent_external_id: str = body.get("agent_external_id", "unknown")

    if not messages:
        raise HTTPException(status_code=400, detail="No messages in request")

    reply = await openclaw_http.chat_stream(
        messages,
        room_id=room_id,
        agent_external_id=agent_external_id,
        on_action=_publish_action,
        on_text_chunk=_publish_text_chunk,
    )

    if not reply:
        raise HTTPException(status_code=502, detail="OpenClaw did not return a response")

    return {
        "choices": [
            {"message": {"role": "assistant", "content": reply}}
        ]
    }


# ── Tool execution endpoint ─────────────────────────────────────────────────

@app.post("/rooms/{room_id}/tools/execute")
async def execute_room_tool(room_id: int, body: dict):
    """Execute a sandbox tool and publish AgentAction events to Redis.

    Body: { "tool_name": str, "params": dict, "agent_external_id": str, "agent_role": str }
    """
    tool_name: str = body.get("tool_name", "")
    params: dict = body.get("params", {})
    agent_role: str = body.get("agent_role", "developer")
    agent_external_id: str = body.get("agent_external_id", "unknown")

    if not tool_name:
        raise HTTPException(status_code=400, detail="tool_name is required")

    from datetime import datetime, timezone

    started = AgentAction(
        roomId=room_id,
        agentExternalId=agent_external_id,
        actionType=tool_name,
        toolName=tool_name,
        status="started",
        params=params,
        timestamp=datetime.now(timezone.utc).isoformat(),
    )
    await _publish_action(started)

    result = await execute_tool(room_id, tool_name, params, agent_role)

    done = AgentAction(
        roomId=room_id,
        agentExternalId=agent_external_id,
        actionType=tool_name,
        toolName=tool_name,
        status=result["status"],
        params=params,
        result=result["result"][:500] if result["result"] else None,
        timestamp=datetime.now(timezone.utc).isoformat(),
    )
    await _publish_action(done)

    return result
