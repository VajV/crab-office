"""Tests for container_manager — uses mock docker client."""

from __future__ import annotations

import asyncio
from unittest.mock import MagicMock, patch

import pytest

from container_manager import ContainerManager, ExecResult


def _make_mock_client():
    """Build a docker.DockerClient mock with realistic behaviour."""
    client = MagicMock()

    # containers.run returns a fake container
    fake_container = MagicMock()
    fake_container.id = "abc123"
    fake_container.status = "running"
    client.containers.run.return_value = fake_container
    client.containers.get.return_value = fake_container

    # exec_create / exec_start / exec_inspect
    client.api.exec_create.return_value = {"Id": "exec-1"}
    client.api.exec_start.return_value = b"Hello world\n"
    client.api.exec_inspect.return_value = {"ExitCode": 0}

    return client


@pytest.fixture
def manager(tmp_path, monkeypatch):
    monkeypatch.setattr("container_manager.SANDBOX_ROOT", tmp_path)
    monkeypatch.setattr("container_manager.MAX_CONTAINERS", 3)
    client = _make_mock_client()
    return ContainerManager(client=client)


@pytest.mark.asyncio
async def test_get_or_create_creates_container(manager):
    cid = await manager.get_or_create(1)
    assert cid == "abc123"
    manager._client.containers.run.assert_called_once()
    call_kwargs = manager._client.containers.run.call_args.kwargs
    assert call_kwargs["pids_limit"] == 50
    assert call_kwargs["mem_limit"] == "512m"


@pytest.mark.asyncio
async def test_exec_command_returns_result(manager):
    result = await manager.exec_command(1, "python hello.py")
    assert isinstance(result, ExecResult)
    assert result.exit_code == 0
    assert "Hello world" in result.stdout
    assert result.timed_out is False


@pytest.mark.asyncio
async def test_exec_command_truncates_long_output(manager):
    long_output = b"x" * 5000
    manager._client.api.exec_start.return_value = long_output
    result = await manager.exec_command(1, "echo lots")
    assert "truncated" in result.stdout
    assert len(result.stdout) < 5000


@pytest.mark.asyncio
async def test_stop_container_removes_it(manager):
    await manager.get_or_create(1)
    assert 1 in manager._containers
    await manager.stop_container(1)
    assert 1 not in manager._containers
    manager._client.containers.get.return_value.stop.assert_called_once()


@pytest.mark.asyncio
async def test_cleanup_idle_removes_old_containers(manager):
    await manager.get_or_create(1)
    # Force last_used to be old
    manager._containers[1].last_used = 0
    removed = await manager.cleanup_idle(max_idle_seconds=1)
    assert removed == 1
    assert 1 not in manager._containers


@pytest.mark.asyncio
async def test_evicts_oldest_when_limit_reached(manager):
    # MAX_CONTAINERS = 3
    await manager.get_or_create(1)
    await manager.get_or_create(2)
    await manager.get_or_create(3)
    # Force room 1 to be oldest
    manager._containers[1].last_used = 0
    await manager.get_or_create(4)
    assert 1 not in manager._containers
    assert 4 in manager._containers
