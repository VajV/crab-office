"""Tests for run_code tool — uses mock container manager."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from container_manager import ExecResult


@pytest.fixture
def mock_manager():
    mgr = MagicMock()
    mgr.exec_command = AsyncMock()
    return mgr


@pytest.mark.asyncio
async def test_run_code_success(mock_manager):
    from file_tools import run_code

    mock_manager.exec_command.return_value = ExecResult(
        exit_code=0, stdout="Hello world\n", stderr="", timed_out=False
    )
    with patch("container_manager.get_container_manager", return_value=mock_manager):
        result = await run_code(1, "python hello.py")

    assert "exit code 0" in result
    assert "Hello world" in result
    assert "✅" in result
    mock_manager.exec_command.assert_awaited_once_with(1, "python hello.py")


@pytest.mark.asyncio
async def test_run_code_failure(mock_manager):
    from file_tools import run_code

    mock_manager.exec_command.return_value = ExecResult(
        exit_code=1, stdout="NameError: name 'foo' is not defined", stderr="", timed_out=False
    )
    with patch("container_manager.get_container_manager", return_value=mock_manager):
        result = await run_code(1, "python broken.py")

    assert "exit code 1" in result
    assert "❌" in result
    assert "NameError" in result


@pytest.mark.asyncio
async def test_run_code_exception(mock_manager):
    from file_tools import run_code

    mock_manager.exec_command.side_effect = RuntimeError("Docker down")
    with patch("container_manager.get_container_manager", return_value=mock_manager):
        result = await run_code(1, "python hello.py")

    assert "Ошибка выполнения" in result


@pytest.mark.asyncio
async def test_run_code_in_tool_registry():
    """Verify run_code is registered with correct params and roles."""
    from file_tools import TOOL_REGISTRY

    assert "run_code" in TOOL_REGISTRY
    func, params, roles = TOOL_REGISTRY["run_code"]
    assert params == ["command"]
    assert "developer" in roles
    assert "architect" in roles
    assert "analyst" not in roles
    assert "manager" not in roles
