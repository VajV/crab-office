import pytest

from agent_logic import move_agent
from models import Position


@pytest.mark.asyncio
async def test_move_agent_returns_flat_coordinates(monkeypatch):
    published = {}

    async def fake_publish(event):
        published["payload"] = event.model_dump()

    monkeypatch.setattr("agent_logic.publish_event", fake_publish)

    event = await move_agent(
        room_id=4,
        agent_external_id="agent-dev-1",
        current_state="idle",
        target_state="walking",
        new_position=Position(x=6, y=4),
        message="move",
    )

    assert event is not None
    assert event.x == 6
    assert event.y == 4
    assert "position" not in published["payload"]