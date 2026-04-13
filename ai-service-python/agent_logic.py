"""Agent logic — simple state machine and event publishing."""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone

import redis.asyncio as aioredis

from models import AgentEvent, Position

logger = logging.getLogger(__name__)

VALID_STATES = {"idle", "walking", "working", "typing"}
TRANSITIONS: dict[str, set[str]] = {
    "idle": {"walking", "working", "typing"},
    "walking": {"idle", "working"},
    "working": {"idle", "typing"},
    "typing": {"idle", "working"},
}

REDIS_CHANNEL = "crab:agent-events"


def _get_redis() -> aioredis.Redis:
    return aioredis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"))


def validate_transition(current: str, target: str) -> bool:
    return target in TRANSITIONS.get(current, set())


async def publish_event(event: AgentEvent) -> None:
    """Publish an agent event to Redis Pub/Sub."""
    try:
        r = _get_redis()
        await r.publish(REDIS_CHANNEL, event.model_dump_json())
        await r.aclose()
    except Exception:
        logger.exception("Failed to publish event to Redis")


async def move_agent(
    room_id: int,
    agent_external_id: str,
    current_state: str,
    target_state: str,
    new_position: Position,
    message: str = "",
) -> AgentEvent | None:
    """Validate transition, build event and publish it."""
    if not validate_transition(current_state, target_state):
        logger.warning("Invalid transition %s -> %s for %s", current_state, target_state, agent_external_id)
        return None

    event = AgentEvent(
        roomId=room_id,
        agentExternalId=agent_external_id,
        eventType="AGENT_STATE_CHANGED",
        state=target_state,
        x=new_position.x,
        y=new_position.y,
        message=message or f"{agent_external_id} transitioned to {target_state}",
        timestamp=datetime.now(timezone.utc).isoformat(),
    )
    await publish_event(event)
    return event
