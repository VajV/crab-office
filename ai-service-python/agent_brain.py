"""Agent brain — manager-first delegation: listens for user messages,
delegates subtasks to specialist agents via LLM."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re

import redis.asyncio as aioredis
from openai import AsyncOpenAI

from agent_prompts import build_system_prompt, build_delegation_prompt
from file_tools import TOOL_REGISTRY
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
    """Use the router agent to pick the best responder (fallback only)."""
    from agent_router import route_message
    return await route_message(user_message, agents)


def _find_manager(agents: list[dict]) -> dict | None:
    """Find the manager agent in the room."""
    for a in agents:
        if a.get("role") == "manager":
            return a
    return None


def _agent_name_by_id(agents: list[dict], agent_ext_id: str) -> str:
    """Resolve a human-readable agent name from its external id."""
    for agent in agents:
        if agent.get("externalId") == agent_ext_id:
            return agent.get("name", agent_ext_id)
    return agent_ext_id


def _normalize_subtasks(raw_subtasks: list[dict], agents: list[dict]) -> list[dict]:
    """Validate manager subtasks and attach a normalized execution order."""
    agent_ids = {a.get("externalId") for a in agents}
    valid: list[dict] = []

    for position, subtask in enumerate(raw_subtasks, start=1):
        if not isinstance(subtask, dict):
            continue

        agent_ext_id = subtask.get("agentExternalId")
        task_text = subtask.get("task")
        if agent_ext_id not in agent_ids:
            continue
        if not isinstance(task_text, str) or not task_text.strip():
            continue

        raw_order = subtask.get("order", position)
        try:
            order = int(raw_order)
        except (TypeError, ValueError):
            order = position
        if order < 1:
            order = position

        valid.append({
            "agentExternalId": agent_ext_id,
            "task": task_text.strip(),
            "order": order,
            "_position": position,
        })

    valid.sort(key=lambda st: (st["order"], st["_position"]))
    for subtask in valid:
        subtask.pop("_position", None)
    return valid


def _group_subtasks_by_order(subtasks: list[dict]) -> list[tuple[int, list[dict]]]:
    """Group manager subtasks into execution steps by order."""
    grouped: list[tuple[int, list[dict]]] = []
    current_order: int | None = None
    current_items: list[dict] = []

    for subtask in subtasks:
        order = subtask["order"]
        if current_order is None or order != current_order:
            if current_items:
                grouped.append((current_order, current_items))
            current_order = order
            current_items = [subtask]
        else:
            current_items.append(subtask)

    if current_items and current_order is not None:
        grouped.append((current_order, current_items))
    return grouped


def _build_delegation_summary(subtasks: list[dict], agents: list[dict]) -> str:
    """Build a readable plan summary for the chat timeline."""
    task_lines = []
    for index, subtask in enumerate(subtasks, start=1):
        agent_name = _agent_name_by_id(agents, subtask["agentExternalId"])
        task_lines.append(
            f"{index}. [шаг {subtask['order']}] {agent_name} — {subtask['task']}"
        )

    step_count = len({subtask["order"] for subtask in subtasks})
    return (
        f"📋 Менеджер собрал план: {step_count} шаг(ов), {len(subtasks)} задач."
        f"\n\n" + "\n".join(task_lines)
    )


def _build_step_status(
    order: int,
    step_subtasks: list[dict],
    agents: list[dict],
    remaining_steps: list[int],
) -> str:
    """Build a SYSTEM status update for the currently active step."""
    active_names = ", ".join(
        _agent_name_by_id(agents, subtask["agentExternalId"])
        for subtask in step_subtasks
    )
    detail_lines = [
        f"- {_agent_name_by_id(agents, subtask['agentExternalId'])}: {subtask['task']}"
        for subtask in step_subtasks
    ]
    queue_text = (
        "Нет следующих шагов."
        if not remaining_steps
        else "В очереди шаги: " + ", ".join(str(step) for step in remaining_steps)
    )
    return (
        f"⏳ Активен шаг {order}. Сейчас работают: {active_names}.\n"
        f"{queue_text}\n\n" + "\n".join(detail_lines)
    )


async def _manager_delegate(
    manager: dict,
    user_message: str,
    agents: list[dict],
    room_name: str,
) -> list[dict]:
    """Ask the manager to decompose user message into subtasks."""
    non_manager_agents = [
        {"externalId": a.get("externalId"), "name": a.get("name"), "role": a.get("role")}
        for a in agents if a.get("role") != "manager"
    ]

    prompt = build_delegation_prompt(
        agents=non_manager_agents,
        manager_name=manager.get("name", "Manager"),
        room_name=room_name,
    )

    try:
        client = _get_client()
        model = _get_model()
        resp = await client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": prompt},
                {"role": "user", "content": user_message},
            ],
            temperature=0.3,
            max_tokens=512,
        )
        raw = (resp.choices[0].message.content or "").strip()
        raw = raw.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        subtasks = json.loads(raw)
        if not isinstance(subtasks, list):
            logger.warning("Manager returned non-list: %s", raw)
            return []
        return _normalize_subtasks(subtasks, agents)
    except Exception:
        logger.exception("Manager delegation LLM call failed")
        return []


async def _publish_message(
    room_id: int, agent_ext_id: str | None, sender_type: str, content: str
) -> None:
    """Publish a message to the outbound Redis channel."""
    outbound = {
        "roomId": room_id,
        "agentExternalId": agent_ext_id,
        "senderType": sender_type,
        "content": content,
    }
    r = _get_redis()
    await r.publish(REDIS_OUTBOUND, json.dumps(outbound))
    await r.aclose()


TOOL_PATTERN = re.compile(
    r"CALL_TOOL:\s*(\w+)\s*(\{.*?\})",
    re.DOTALL,
)


async def _execute_tools(
    reply_text: str, room_id: int, agent: dict,
) -> tuple[str, list[str]]:
    """Parse CALL_TOOL blocks, execute tools, return (clean_text, notifications)."""
    notifications: list[str] = []
    agent_role = agent.get("role", "")
    agent_name = agent.get("name", "Agent")
    agent_ext_id = agent.get("externalId", "unknown")

    def replacer(match: re.Match) -> str:
        tool_name = match.group(1)
        raw_json = match.group(2)

        if tool_name not in TOOL_REGISTRY:
            msg = f"⚠️ Неизвестный инструмент: {tool_name}"
            notifications.append(msg)
            return msg

        func, required_params, allowed_roles = TOOL_REGISTRY[tool_name]
        if agent_role not in allowed_roles:
            msg = f"⚠️ {agent_name} не имеет доступа к {tool_name}"
            notifications.append(msg)
            return msg

        try:
            params = json.loads(raw_json)
        except json.JSONDecodeError:
            msg = f"⚠️ Невалидный JSON для {tool_name}"
            notifications.append(msg)
            return msg

        for p in required_params:
            if p not in params:
                msg = f"⚠️ Отсутствует параметр '{p}' для {tool_name}"
                notifications.append(msg)
                return msg

        result = func(room_id, **params)
        notifications.append(f"🔧 {agent_name} ({agent_ext_id}): {result}")
        return ""

    clean = TOOL_PATTERN.sub(replacer, reply_text).strip()
    clean = re.sub(r"\n{3,}", "\n\n", clean).strip()
    return clean, notifications


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


async def _process_subtask(
    agent: dict, task_text: str, original_message: str, room_id: int, room_name: str,
) -> None:
    """Process a single subtask for a specific agent."""
    # Give agent both original context and the manager's specific task
    combined = (
        f"Исходное сообщение пользователя: {original_message}\n\n"
        f"Твоя задача от менеджера: {task_text}"
    )
    reply = await _generate_agent_reply(agent, combined, room_name)
    if reply.strip() == "[SKIP]":
        logger.debug("Agent %s skipped subtask in room %d", agent.get("name"), room_id)
        return

    # Execute any CALL_TOOL blocks in the reply
    clean_reply, notifications = await _execute_tools(reply, room_id, agent)

    # Publish tool notifications as SYSTEM messages
    for note in notifications:
        await _publish_message(room_id, agent.get("externalId", "unknown"), "SYSTEM", note)

    # Publish the cleaned agent reply (if anything remains)
    if clean_reply:
        await _publish_message(room_id, agent.get("externalId", "unknown"), "AGENT", clean_reply)
    logger.info("Agent %s completed subtask in room %d", agent.get("name"), room_id)


async def handle_inbound_message(raw_data: str) -> None:
    """Process a single inbound user message using manager delegation."""
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

    room_name = f"Room {room_id}"
    manager = _find_manager(agents)

    if not manager:
        # Fallback: no manager in room — use old single-agent routing
        logger.warning("No manager in room %d, falling back to router", room_id)
        agent = await _pick_responding_agent(agents, chat_msg.content)
        if not agent:
            return
        reply = await _generate_agent_reply(agent, chat_msg.content, room_name)
        if reply.strip() != "[SKIP]":
            clean_reply, notifications = await _execute_tools(reply, room_id, agent)
            for note in notifications:
                await _publish_message(room_id, agent.get("externalId", "unknown"), "SYSTEM", note)
            if clean_reply:
                await _publish_message(room_id, agent.get("externalId", "unknown"), "AGENT", clean_reply)
        return

    # --- Manager-first delegation flow ---

    # Step 1: Manager decomposes into subtasks
    subtasks = await _manager_delegate(manager, chat_msg.content, agents, room_name)

    if not subtasks:
        # Manager couldn't delegate — have manager respond directly
        logger.info("Manager produced no subtasks, responding directly")
        reply = await _generate_agent_reply(manager, chat_msg.content, room_name)
        if reply.strip() != "[SKIP]":
            await _publish_message(room_id, manager.get("externalId", "unknown"), "AGENT", reply)
        return

    # Step 2: Announce delegation via SYSTEM message
    delegation_text = _build_delegation_summary(subtasks, agents)
    await _publish_message(room_id, manager.get("externalId"), "SYSTEM", delegation_text)
    logger.info("Manager delegated %d subtasks in room %d", len(subtasks), room_id)

    # Step 3: Execute subtasks step-by-step by manager order.
    agent_map = {a.get("externalId"): a for a in agents}
    step_groups = _group_subtasks_by_order(subtasks)

    for index, (order, step_subtasks) in enumerate(step_groups):
        remaining_steps = [step_order for step_order, _ in step_groups[index + 1:]]
        step_status = _build_step_status(order, step_subtasks, agents, remaining_steps)
        await _publish_message(room_id, manager.get("externalId"), "SYSTEM", step_status)

        coros = []
        for subtask in step_subtasks:
            agent = agent_map.get(subtask["agentExternalId"])
            if agent:
                coros.append(
                    _process_subtask(agent, subtask["task"], chat_msg.content, room_id, room_name)
                )

        if coros:
            await asyncio.gather(*coros)

        await _publish_message(
            room_id,
            manager.get("externalId"),
            "SYSTEM",
            f"✅ Шаг {order} завершен.",
        )

    logger.info("All %d subtasks completed for room %d", len(subtasks), room_id)


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
