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
from openclaw_runtime_bridge import (
    assign_task as backend_assign_task,
    finish_interaction as backend_finish_interaction,
    move_agent as backend_move_agent,
    start_interaction as backend_start_interaction,
    set_agent_state as backend_set_agent_state,
    spawn_agent as backend_spawn_agent,
)
from runtime_command_parser import parse_runtime_command
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

    room_id = body.get("room_id", 0)
    agent_external_id = body.get("agent_external_id")

    runtime_command = parse_runtime_command(user_message)
    if runtime_command:
        reply = await _execute_runtime_command(room_id, runtime_command)
        if reply is None:
            raise HTTPException(status_code=502, detail="Backend command ingress is unavailable")
    else:
        reply = await openclaw_ws.chat(
            user_message,
            system_prompt=system_prompt,
            room_id=room_id,
            agent_external_id=agent_external_id,
        )

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

    user_message: str = next(
        (m.get("content", "") for m in reversed(messages) if m.get("role") == "user"),
        "",
    )

    runtime_command = parse_runtime_command(user_message)
    if runtime_command:
        reply = await _execute_runtime_command(room_id, runtime_command)
        if reply is None:
            raise HTTPException(status_code=502, detail="Backend command ingress is unavailable")
        return {
            "choices": [
                {"message": {"role": "assistant", "content": reply}}
            ]
        }

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


@app.post("/rooms/{room_id}/runtime/spawn-agent")
async def runtime_spawn_agent(room_id: int, body: dict):
    role = body.get("role", "")
    name = body.get("name")
    preferred_location_id = body.get("preferredLocationId")
    correlation_id = body.get("correlationId")
    run_id = body.get("runId")

    if not role:
        raise HTTPException(status_code=400, detail="role is required")

    result = await backend_spawn_agent(
        room_id,
        role,
        name=name,
        preferred_location_id=preferred_location_id,
        correlation_id=correlation_id,
        run_id=run_id,
    )
    if result is None:
        raise HTTPException(status_code=502, detail="Failed to reach backend command ingress")
    return result


@app.post("/rooms/{room_id}/runtime/set-state")
async def runtime_set_state(room_id: int, body: dict):
    agent_external_id = body.get("agentExternalId", "")
    state = body.get("state", "")
    status_text = body.get("statusText")
    correlation_id = body.get("correlationId")
    run_id = body.get("runId")

    if not agent_external_id or not state:
        raise HTTPException(status_code=400, detail="agentExternalId and state are required")

    result = await backend_set_agent_state(
        room_id,
        agent_external_id,
        state,
        status_text=status_text,
        correlation_id=correlation_id,
        run_id=run_id,
    )
    if result is None:
        raise HTTPException(status_code=502, detail="Failed to reach backend command ingress")
    return result


@app.post("/rooms/{room_id}/runtime/move-agent")
async def runtime_move_agent_endpoint(room_id: int, body: dict):
    agent_external_id = body.get("agentExternalId", "")
    location_id = body.get("locationId", "")
    x = body.get("x")
    y = body.get("y")
    reason = body.get("reason")
    target_agent_external_id = body.get("targetAgentExternalId")
    correlation_id = body.get("correlationId")
    run_id = body.get("runId")

    if not agent_external_id or not location_id or x is None or y is None:
        raise HTTPException(status_code=400, detail="agentExternalId, locationId, x, y are required")

    result = await backend_move_agent(
        room_id,
        agent_external_id,
        location_id,
        int(x),
        int(y),
        reason=reason,
        target_agent_external_id=target_agent_external_id,
        correlation_id=correlation_id,
        run_id=run_id,
    )
    if result is None:
        raise HTTPException(status_code=502, detail="Failed to reach backend command ingress")
    return result


async def _execute_runtime_command(room_id: int, runtime_command: dict) -> str | None:
    command_type = runtime_command.get("commandType")
    payload = runtime_command.get("payload", {})

    if command_type == "spawn_agent":
        result = await backend_spawn_agent(room_id, payload["role"])
        if result:
            return f"Создал нового агента роли {payload['role']}."
        return None

    if command_type == "assign_task":
        result = await backend_assign_task(
            room_id,
            payload["title"],
            description=payload.get("description"),
            role=payload.get("role"),
        )
        if result:
            return f"Назначил задачу: {payload['title']}"
        return None

    if command_type == "start_interaction":
        initiator_role = payload.get("initiatorRole")
        target_role = payload.get("targetRole")
        if initiator_role and target_role:
            result = await backend_start_interaction(
                room_id,
                f"agent-{initiator_role}-1",
                f"agent-{target_role}-1",
                payload.get("interactionType", "discussion"),
                summary=payload.get("summary"),
            )
            if result:
                return f"Запустил взаимодействие между {initiator_role} и {target_role}."
        return None

    if command_type == "finish_interaction":
        initiator_role = payload.get("initiatorRole")
        target_role = payload.get("targetRole")
        if initiator_role and target_role:
            result = await backend_finish_interaction(
                room_id,
                f"agent-{initiator_role}-1",
                f"agent-{target_role}-1",
                payload.get("interactionType", "discussion"),
                summary=payload.get("summary"),
            )
            if result:
                return f"Завершил взаимодействие между {initiator_role} и {target_role}."
        return None

    return None
