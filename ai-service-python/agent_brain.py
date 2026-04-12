"""Agent brain — listens for user messages, generates agent responses via LLM."""

from __future__ import annotations

import asyncio
import json
import logging
import os

import redis.asyncio as aioredis
from openai import AsyncOpenAI

from agent_prompts import build_system_prompt
from agent_router import route_message
from models import ChatMessage

logger = logging.getLogger(__name__)

REDIS_INBOUND = "crab:chat-inbound"
REDIS_OUTBOUND = "crab:chat-outbound"


def _get_redis() -> aioredis.Redis:
    return aioredis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"))


def _get_client() -> AsyncOpenAI:
    from architect import _get_client as get_openai_client
    return get_openai_client()


def _get_model() -> str:
    from architect import _get_model_name
    return _get_model_name()


async def _fetch_room_agents(room_id: int) -> list[dict]:
    """Fetch agent info from Redis cache or Java backend."""
    r = _get_redis()
    cached = await r.get(f"crab:room:{room_id}:agents")
    await r.aclose()

    if cached:
        return json.loads(cached)

    # Fallback: ask Java backend
    import httpx
    backend_url = os.getenv("JAVA_BACKEND_URL", "http://localhost:8080")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.get(f"{backend_url}/api/rooms/{room_id}")
            resp.raise_for_status()
            room_data = resp.json()
            agents = room_data.get("agents", [])
            # Cache in Redis for 10 minutes
            r = _get_redis()
            await r.setex(f"crab:room:{room_id}:agents", 600, json.dumps(agents))
            await r.aclose()
            return agents
    except Exception:
        logger.exception("Failed to fetch room agents from backend")
        return []


async def _pick_responding_agent(agents: list[dict], user_message: str) -> dict | None:
    """Use the router agent to pick the best responder."""
    return await route_message(user_message, agents)


async def _generate_agent_reply(
    agent: dict,
    user_message: str,
    room_name: str,
    history: list[dict] | None = None,
) -> str:
    """Call the LLM to generate a reply from the agent's perspective."""
    api_key = os.getenv("OPENROUTER_API_KEY", "")
    if not api_key:
        return f"[{agent.get('name', 'Agent')}] (mock) Received your message: {user_message}"

    system_prompt = build_system_prompt(
        role=agent.get("role", "developer"),
        agent_name=agent.get("name", "Agent"),
        room_name=room_name,
    )

    messages = [{"role": "system", "content": system_prompt}]
    if history:
        for h in history[-10:]:  # last 10 messages for context
            role = "assistant" if h.get("senderType") == "AGENT" else "user"
            messages.append({"role": role, "content": h.get("content", "")})
    messages.append({"role": "user", "content": user_message})

    try:
        client = _get_client()
        model = _get_model()
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,
            temperature=0.8,
            max_tokens=512,
        )
        return resp.choices[0].message.content or "(no response)"
    except Exception:
        logger.exception("LLM call failed for agent %s", agent.get("name"))
        return "Sorry, I'm having trouble thinking right now. Try again in a moment."


async def handle_inbound_message(raw_data: str) -> None:
    """Process a single inbound user message."""
    try:
        msg = json.loads(raw_data)
        chat_msg = ChatMessage(**msg)
    except Exception:
        logger.exception("Failed to parse inbound message")
        return

    if chat_msg.senderType != "USER":
        return  # Only respond to user messages

    room_id = chat_msg.roomId
    agents = await _fetch_room_agents(room_id)
    if not agents:
        logger.warning("No agents found for room %d", room_id)
        return

    agent = await _pick_responding_agent(agents, chat_msg.content)
    if not agent:
        return

    reply_text = await _generate_agent_reply(
        agent=agent,
        user_message=chat_msg.content,
        room_name=f"Room {room_id}",
    )

    # Build outbound message
    outbound = {
        "roomId": room_id,
        "agentExternalId": agent.get("externalId", "unknown"),
        "senderType": "AGENT",
        "content": reply_text,
    }

    r = _get_redis()
    await r.publish(REDIS_OUTBOUND, json.dumps(outbound))
    await r.aclose()
    logger.info("Agent %s replied in room %d", agent.get("name"), room_id)


async def brain_loop() -> None:
    """Main loop — subscribe to Redis and process messages."""
    logger.info("Agent brain starting — listening on %s", REDIS_INBOUND)
    r = _get_redis()
    pubsub = r.pubsub()
    await pubsub.subscribe(REDIS_INBOUND)

    try:
        async for raw_message in pubsub.listen():
            if raw_message["type"] != "message":
                continue
            data = raw_message["data"]
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            asyncio.create_task(handle_inbound_message(data))
    except asyncio.CancelledError:
        logger.info("Agent brain shutting down")
    finally:
        await pubsub.unsubscribe(REDIS_INBOUND)
        await r.aclose()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from pathlib import Path
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().with_name(".env"))
    asyncio.run(brain_loop())
