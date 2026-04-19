"""Bridge from OpenClaw runtime actions to Java backend command ingress."""

from __future__ import annotations

import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)

BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://localhost:8080")
COMMAND_TIMEOUT = int(os.getenv("OPENCLAW_COMMAND_TIMEOUT", "20"))


async def send_command(
    room_id: int,
    command_type: str,
    payload: dict[str, Any],
    *,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    url = f"{BACKEND_URL}/api/rooms/{room_id}/openclaw/commands"
    body = {
        "commandType": command_type,
        "payload": payload,
        "correlationId": correlation_id,
        "runId": run_id,
    }

    try:
        async with httpx.AsyncClient(timeout=COMMAND_TIMEOUT) as client:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to send OpenClaw runtime command %s for room %s", command_type, room_id)
        return None


async def publish_simulation_event(
    room_id: int,
    event_type: str,
    agent_external_id: str,
    *,
    location_id: str | None = None,
    state: str | None = None,
    payload: dict[str, Any] | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    url = f"{BACKEND_URL}/api/rooms/{room_id}/simulation-events"
    body = {
        "eventType": event_type,
        "locationId": location_id,
        "agentExternalId": agent_external_id,
        "state": state,
        "payload": payload or {},
        "correlationId": correlation_id,
        "runId": run_id,
    }

    try:
        async with httpx.AsyncClient(timeout=COMMAND_TIMEOUT) as client:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to publish simulation event %s for room %s", event_type, room_id)
        return None


async def spawn_agent(
    room_id: int,
    role: str,
    *,
    name: str | None = None,
    preferred_location_id: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {"role": role}
    if name:
        payload["name"] = name
    if preferred_location_id:
        payload["preferredLocationId"] = preferred_location_id
    return await send_command(room_id, "spawn_agent", payload, correlation_id=correlation_id, run_id=run_id)


async def set_agent_state(
    room_id: int,
    agent_external_id: str,
    state: str,
    *,
    status_text: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {
        "agentExternalId": agent_external_id,
        "state": state,
    }
    if status_text:
        payload["statusText"] = status_text
    return await send_command(room_id, "set_agent_state", payload, correlation_id=correlation_id, run_id=run_id)


async def move_agent(
    room_id: int,
    agent_external_id: str,
    location_id: str,
    x: int,
    y: int,
    *,
    reason: str | None = None,
    target_agent_external_id: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {
        "agentExternalId": agent_external_id,
        "locationId": location_id,
        "x": x,
        "y": y,
    }
    if reason:
        payload["reason"] = reason
    if target_agent_external_id:
        payload["targetAgentExternalId"] = target_agent_external_id
    return await send_command(room_id, "move_agent", payload, correlation_id=correlation_id, run_id=run_id)


async def assign_task(
    room_id: int,
    title: str,
    *,
    description: str | None = None,
    agent_external_id: str | None = None,
    role: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {
        "title": title,
        "description": description or title,
    }
    if agent_external_id:
        payload["agentExternalId"] = agent_external_id
    if role:
        payload["role"] = role
    return await send_command(room_id, "assign_task", payload, correlation_id=correlation_id, run_id=run_id)


async def start_interaction(
    room_id: int,
    agent_external_id: str,
    target_agent_external_id: str,
    interaction_type: str,
    *,
    summary: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {
        "agentExternalId": agent_external_id,
        "targetAgentExternalId": target_agent_external_id,
        "interactionType": interaction_type,
    }
    if summary:
        payload["summary"] = summary
    return await send_command(room_id, "start_interaction", payload, correlation_id=correlation_id, run_id=run_id)


async def finish_interaction(
    room_id: int,
    agent_external_id: str,
    target_agent_external_id: str,
    interaction_type: str,
    *,
    summary: str | None = None,
    correlation_id: str | None = None,
    run_id: str | None = None,
) -> dict[str, Any] | None:
    payload: dict[str, Any] = {
        "agentExternalId": agent_external_id,
        "targetAgentExternalId": target_agent_external_id,
        "interactionType": interaction_type,
    }
    if summary:
        payload["summary"] = summary
    return await send_command(room_id, "finish_interaction", payload, correlation_id=correlation_id, run_id=run_id)
