"""Tests for container event publishing."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from container_manager import ContainerManager, ExecResult


def _make_mock_client():
    client = MagicMock()
    fake_container = MagicMock()
    fake_container.id = "abc123"
    fake_container.status = "running"
    client.containers.run.return_value = fake_container
    client.containers.get.return_value = fake_container
    client.api.exec_create.return_value = {"Id": "exec-1"}
    client.api.exec_start.return_value = b"ok\n"
    client.api.exec_inspect.return_value = {"ExitCode": 0}
    return client


@pytest.fixture
def manager_with_redis(tmp_path, monkeypatch):
    monkeypatch.setattr("container_manager.SANDBOX_ROOT", tmp_path)
    mock_redis = MagicMock()
    client = _make_mock_client()
    mgr = ContainerManager(client=client)
    mgr._redis = mock_redis
    return mgr, mock_redis


@pytest.mark.asyncio
async def test_creating_event_on_container_start(manager_with_redis):
    mgr, mock_redis = manager_with_redis
    await mgr.get_or_create(1)

    calls = mock_redis.publish.call_args_list
    assert len(calls) >= 1
    event = json.loads(calls[0][0][1])
    assert event["roomId"] == 1
    assert event["status"] == "creating"
    assert "timestamp" in event


@pytest.mark.asyncio
async def test_running_and_result_events_on_exec(manager_with_redis):
    mgr, mock_redis = manager_with_redis
    await mgr.exec_command(1, "python hello.py")

    calls = mock_redis.publish.call_args_list
    # Should have: creating, running, stopped (success)
    statuses = [json.loads(c[0][1])["status"] for c in calls]
    assert "creating" in statuses
    assert "running" in statuses
    assert "stopped" in statuses


@pytest.mark.asyncio
async def test_error_event_on_nonzero_exit(manager_with_redis):
    mgr, mock_redis = manager_with_redis
    mgr._client.api.exec_inspect.return_value = {"ExitCode": 1}
    mgr._client.api.exec_start.return_value = b"Error!"

    await mgr.exec_command(1, "python fail.py")

    calls = mock_redis.publish.call_args_list
    last_event = json.loads(calls[-1][0][1])
    assert last_event["status"] == "error"
    assert last_event["exitCode"] == 1
    assert "Error!" in last_event["stdout"]
    assert last_event["command"] == "python fail.py"


@pytest.mark.asyncio
async def test_event_channel_name(manager_with_redis):
    mgr, mock_redis = manager_with_redis
    await mgr.get_or_create(1)

    channel = mock_redis.publish.call_args_list[0][0][0]
    assert channel == "crab:container-events"
