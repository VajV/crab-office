"""Router agent — decides which agent handles a given message."""

from __future__ import annotations

import json
import logging
import os

logger = logging.getLogger(__name__)

ROUTER_PROMPT = """\
You are a message router in Crab Office. Given a user message and a list of available agents with their roles, decide which agent should respond.

Available agents:
{agents_json}

Reply with ONLY a JSON object: {{"agentExternalId": "<id>", "reason": "<short reason>"}}
If the message is general and any agent could respond, pick the manager.
"""


async def route_message(user_message: str, agents: list[dict]) -> dict | None:
    """Pick the best agent to respond to the user message."""
    if not agents:
        return None

    if len(agents) == 1:
        return agents[0]

    api_key = os.getenv("OPENROUTER_API_KEY", "")
    if not api_key:
        return agents[0]

    agents_summary = [
        {"externalId": a.get("externalId"), "name": a.get("name"), "role": a.get("role")}
        for a in agents
    ]

    try:
        from architect import _get_client, _get_model_name
        client = _get_client()
        model = _get_model_name()

        resp = await client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": ROUTER_PROMPT.format(agents_json=json.dumps(agents_summary))},
                {"role": "user", "content": user_message},
            ],
            temperature=0.3,
            max_tokens=128,
        )
        raw = (resp.choices[0].message.content or "").strip()
        raw = raw.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        decision = json.loads(raw)
        chosen_id = decision.get("agentExternalId")
        for a in agents:
            if a.get("externalId") == chosen_id:
                logger.info("Router picked %s (reason: %s)", a.get("name"), decision.get("reason"))
                return a
    except Exception:
        logger.exception("Router LLM call failed — defaulting to first agent")

    return agents[0]
