from pathlib import Path
import sys

import pytest

sys.path.append(str(Path(__file__).resolve().parents[1]))

import openclaw_runtime_bridge


@pytest.mark.asyncio
async def test_spawn_agent_sends_backend_command(monkeypatch):
    captured = {}

    async def fake_send_command(room_id, command_type, payload, *, correlation_id=None, run_id=None):
        captured["room_id"] = room_id
        captured["command_type"] = command_type
        captured["payload"] = payload
        captured["correlation_id"] = correlation_id
        captured["run_id"] = run_id
        return {"status": "accepted"}

    monkeypatch.setattr(openclaw_runtime_bridge, "send_command", fake_send_command)

    result = await openclaw_runtime_bridge.spawn_agent(
        4,
        "seo",
        name="SEO-1",
        preferred_location_id="marketing-room",
        correlation_id="corr-1",
        run_id="run-1",
    )

    assert result == {"status": "accepted"}
    assert captured == {
        "room_id": 4,
        "command_type": "spawn_agent",
        "payload": {
            "role": "seo",
            "name": "SEO-1",
            "preferredLocationId": "marketing-room",
        },
        "correlation_id": "corr-1",
        "run_id": "run-1",
    }


@pytest.mark.asyncio
async def test_move_agent_sends_backend_command(monkeypatch):
    captured = {}

    async def fake_send_command(room_id, command_type, payload, *, correlation_id=None, run_id=None):
        captured["room_id"] = room_id
        captured["command_type"] = command_type
        captured["payload"] = payload
        return {"status": "accepted"}

    monkeypatch.setattr(openclaw_runtime_bridge, "send_command", fake_send_command)

    await openclaw_runtime_bridge.move_agent(
        4,
        "agent-seo-1",
        "marketing-room",
        7,
        5,
        reason="Going to analyst desk",
        target_agent_external_id="agent-analyst-1",
    )

    assert captured == {
        "room_id": 4,
        "command_type": "move_agent",
        "payload": {
            "agentExternalId": "agent-seo-1",
            "locationId": "marketing-room",
            "x": 7,
            "y": 5,
            "reason": "Going to analyst desk",
            "targetAgentExternalId": "agent-analyst-1",
        },
    }
