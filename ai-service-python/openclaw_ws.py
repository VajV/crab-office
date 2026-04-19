"""OpenClaw WebSocket client bridge.

Implements the minimal operator handshake (connect → chat.send → collect events)
to route chat messages through OpenClaw's Pi agent with full tool access.

Protocol: ws://localhost:18789/v1
Auth: shared token in connect.params.auth.token
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import uuid
from typing import Optional

import websockets

from openclaw_runtime_bridge import (
    assign_task as backend_assign_task,
    finish_interaction as backend_finish_interaction,
    publish_simulation_event,
    set_agent_state,
    spawn_agent as backend_spawn_agent,
    start_interaction as backend_start_interaction,
)
from runtime_command_parser import parse_runtime_command

logger = logging.getLogger(__name__)

OPENCLAW_WS_URL = os.getenv("OPENCLAW_WS_URL", "ws://localhost:18789/v1")
OPENCLAW_TOKEN = os.getenv("OPENCLAW_GATEWAY_TOKEN", "crab-office-dev-token-change-me")

# Seconds to wait for the agent to produce a complete response
AGENT_TIMEOUT = int(os.getenv("OPENCLAW_TIMEOUT", "120"))


async def chat(
    user_message: str,
    system_prompt: Optional[str] = None,
    room_id: int | None = None,
    agent_external_id: str | None = None,
) -> Optional[str]:
    """Send a message to OpenClaw and return the agent's response text.

    Opens and closes a WebSocket connection per call (stateless per-request).
    `OPENCLAW_ALLOW_INSECURE_PRIVATE_WS=1` on the gateway side allows us to
    skip device-identity signing for private-network connections.
    """
    logger.info("OpenClaw: connecting to %s", OPENCLAW_WS_URL)
    parsed_command = parse_runtime_command(user_message)
    if room_id is not None and parsed_command:
        result = await _execute_runtime_command(room_id, agent_external_id, parsed_command)
        if result:
            return result

    try:
        async with websockets.connect(
            OPENCLAW_WS_URL,
            open_timeout=10,
            ping_interval=None,
        ) as ws:
            # ── Step 1: Absorb optional connect.challenge ──────────────────
            try:
                first = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
                if first.get("event") == "connect.challenge":
                    logger.debug("Got connect.challenge (ignored — using token auth only)")
                # Any other unexpected frame is just discarded
            except asyncio.TimeoutError:
                logger.debug("No initial frame before connect; proceeding")

            # ── Step 2: Operator connect ───────────────────────────────────
            conn_id = str(uuid.uuid4())
            await ws.send(json.dumps({
                "type": "req",
                "id": conn_id,
                "method": "connect",
                "params": {
                    "minProtocol": 3,
                    "maxProtocol": 3,
                    "client": {
                        "id": "cli",
                        "version": "1.0.0",
                        "platform": "linux",
                        "mode": "cli",
                    },
                    "role": "operator",
                    "scopes": ["operator.read", "operator.write"],
                    "caps": [],
                    "commands": [],
                    "permissions": {},
                    "auth": {"token": OPENCLAW_TOKEN},
                    "locale": "ru",
                    "userAgent": "crab-office-backend/1.0.0",
                },
            }))

            hello = await _read_response(ws, conn_id, timeout=15)
            if not hello or not hello.get("ok"):
                logger.error("OpenClaw connect rejected: %s", hello)
                return None
            logger.info("OpenClaw: connected OK (protocol=%s)", hello.get("payload", {}).get("protocol"))

            # ── Step 3: Build message text ────────────────────────────────
            # Prefix the system context so OpenClaw's agent sees the role info.
            if system_prompt:
                text = f"[Контекст системы: {system_prompt}]\n\n{user_message}"
            else:
                text = user_message

            # ── Step 4: Send user message ─────────────────────────────────
            send_id = str(uuid.uuid4())
            await ws.send(json.dumps({
                "type": "req",
                "id": send_id,
                "method": "chat.send",
                "params": {"text": text},
            }))

            send_resp = await _read_response(ws, send_id, timeout=10)
            if not send_resp or not send_resp.get("ok"):
                logger.error("OpenClaw chat.send rejected: %s", send_resp)
                return None
            logger.info("OpenClaw: chat.send acknowledged")

            # ── Step 5: Request agent.wait to know when run finishes ───────
            wait_id = str(uuid.uuid4())
            await ws.send(json.dumps({
                "type": "req",
                "id": wait_id,
                "method": "agent.wait",
                "params": {},
            }))

            # ── Step 6: Collect events until agent.wait resolves ──────────
            return await _collect_response(
                ws,
                wait_id,
                timeout=AGENT_TIMEOUT,
                room_id=room_id,
                agent_external_id=agent_external_id,
            )

    except (websockets.exceptions.WebSocketException, OSError) as exc:
        logger.error("OpenClaw WebSocket connection error: %s", exc)
        return None
    except Exception:
        logger.exception("Unexpected error in OpenClaw WebSocket client")
        return None


# ── helpers ──────────────────────────────────────────────────────────────────

async def _read_response(ws, req_id: str, timeout: float = 10) -> Optional[dict]:
    """Read frames until we find a `res` frame matching *req_id*, or timeout."""
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        remaining = deadline - asyncio.get_event_loop().time()
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 2.0))
            frame = json.loads(raw)
            if frame.get("type") == "res" and frame.get("id") == req_id:
                return frame
            if frame.get("type") == "event":
                logger.debug("Event while waiting for %s: %s", req_id, frame.get("event"))
        except asyncio.TimeoutError:
            break
    return None


async def _collect_response(
    ws,
    wait_id: str,
    timeout: float = 120,
    room_id: int | None = None,
    agent_external_id: str | None = None,
) -> Optional[str]:
    """Drain WebSocket events until agent.wait resolves (or timeout).

    Primary signal  : agent.wait `res` → extract text from snapshot
    Secondary signal: chat.done / run.done / session.done events
    In-flight text  : accumulated from chat.inject / session.message events
    """
    last_assistant: Optional[str] = None
    deadline = asyncio.get_event_loop().time() + timeout

    while asyncio.get_event_loop().time() < deadline:
        remaining = deadline - asyncio.get_event_loop().time()
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 5.0))
            frame = json.loads(raw)
        except asyncio.TimeoutError:
            # No frame in 5 s; return what we have (agent probably finished)
            logger.warning("OpenClaw: 5 s idle — returning collected text")
            return last_assistant

        ftype = frame.get("type", "")
        event = frame.get("event", "")
        payload = frame.get("payload", {})

        # Phase 1 recon: log every raw frame to discover tool-call event shapes
        logger.info(
            "OpenClaw RAW FRAME: %s",
            json.dumps(frame, ensure_ascii=False, default=str)[:3000],
        )

        if ftype == "res":
            if frame.get("id") == wait_id:
                # agent.wait resolved — extract text from snapshot
                if frame.get("ok"):
                    logger.info("OpenClaw: agent.wait resolved OK")
                    text = _extract_snapshot_text(payload)
                    return text or last_assistant
                else:
                    logger.warning("OpenClaw: agent.wait returned error: %s", frame)
                    return last_assistant

        elif ftype == "event":
            # Collect assistant text from different event flavours
            if event == "chat.inject":
                role = payload.get("role", "")
                text = payload.get("text") or payload.get("content") or ""
                if role == "assistant" and text:
                    logger.debug("chat.inject assistant: %s…", text[:60])
                    last_assistant = text
                elif role == "tool" and room_id is not None and agent_external_id:
                    await _handle_runtime_tool_event(room_id, agent_external_id, payload)

            elif event == "session.message":
                role = payload.get("role", "")
                text = payload.get("text") or payload.get("content") or ""
                if role == "assistant" and text:
                    last_assistant = text
                elif role == "tool" and room_id is not None and agent_external_id:
                    await _handle_runtime_tool_event(room_id, agent_external_id, payload)

            elif event in ("chat.done", "run.done", "session.done", "agent.done"):
                logger.info("OpenClaw: completion event %s", event)
                return last_assistant

            elif event in ("chat.error", "run.error", "session.error", "agent.error"):
                logger.error("OpenClaw error event %s: %s", event, payload)
                return last_assistant

    logger.warning("OpenClaw: timeout after %d s — returning last collected text", timeout)
    return last_assistant


async def _execute_runtime_command(room_id: int,
                                   agent_external_id: str | None,
                                   parsed_command: dict) -> Optional[str]:
    command_type = parsed_command.get("commandType")
    payload = parsed_command.get("payload", {})

    if command_type == "spawn_agent":
        result = await backend_spawn_agent(room_id, payload["role"])
        if result:
            return f"Создал нового агента роли {payload['role']}. Он уже появился в офисе."
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
        initiator = payload.get("initiatorRole")
        target = payload.get("targetRole")
        if initiator and target:
            initiator_external_id = f"agent-{initiator}-1"
            target_external_id = f"agent-{target}-1"
            result = await backend_start_interaction(
                room_id,
                initiator_external_id,
                target_external_id,
                payload.get("interactionType", "discussion"),
                summary=payload.get("summary"),
            )
            if result:
                return f"Запустил взаимодействие между {initiator} и {target}."
        return None

    return None


async def _handle_runtime_tool_event(room_id: int, agent_external_id: str, payload: dict) -> None:
    text = (payload.get("text") or payload.get("content") or "").lower()
    if not text:
        return

    if re.search(r"web|browser|internet|fetch|search", text, re.IGNORECASE):
        await publish_simulation_event(
            room_id,
            "web_research_started",
            agent_external_id,
            state="thinking",
            payload={
                "query": text[:200],
                "tool": "runtime-tool",
                "statusText": "Researching the web",
            },
        )
        await set_agent_state(room_id, agent_external_id, "thinking", status_text="Researching the web")

    if re.search(r"talk|discussion|coordina", text, re.IGNORECASE):
        target_agent_external_id = payload.get("targetAgentExternalId") or "agent-copywriter-1"
        await backend_start_interaction(
            room_id,
            agent_external_id,
            target_agent_external_id,
            "discussion",
            summary="Discussing task details",
        )

    if re.search(r"finished discussion|interaction complete|coordination done", text, re.IGNORECASE):
        target_agent_external_id = payload.get("targetAgentExternalId") or "agent-copywriter-1"
        await backend_finish_interaction(
            room_id,
            agent_external_id,
            target_agent_external_id,
            "discussion",
            summary="Interaction completed",
        )


def _extract_snapshot_text(snapshot: dict) -> Optional[str]:
    """Try to extract the last assistant message text from an agent.wait snapshot."""
    # Common snapshot shapes (speculative — adjust after observing real payloads)
    if "text" in snapshot and isinstance(snapshot["text"], str):
        return snapshot["text"]
    if "lastMessage" in snapshot:
        m = snapshot["lastMessage"]
        if isinstance(m, dict):
            return m.get("text") or m.get("content")
        if isinstance(m, str):
            return m
    if "message" in snapshot:
        m = snapshot["message"]
        if isinstance(m, dict):
            return m.get("text") or m.get("content")
    if "messages" in snapshot and isinstance(snapshot["messages"], list):
        for m in reversed(snapshot["messages"]):
            if isinstance(m, dict) and m.get("role") == "assistant":
                return m.get("text") or m.get("content")
    return None
