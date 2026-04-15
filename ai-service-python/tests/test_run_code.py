"""Tests for run_code tool — tests actual file_tools.run_code function."""

from __future__ import annotations

import pytest

from file_tools import run_code, TOOL_REGISTRY


@pytest.mark.asyncio
async def test_run_code_success(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_1"
    room_dir.mkdir(parents=True)
    (room_dir / "hello.py").write_text('print("Hello world")', encoding="utf-8")

    result = await run_code(1, "python hello.py")

    assert "Hello world" in result
    assert "exit code: 0" in result


@pytest.mark.asyncio
async def test_run_code_failure(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_1"
    room_dir.mkdir(parents=True)
    (room_dir / "broken.py").write_text('raise NameError("foo")', encoding="utf-8")

    result = await run_code(1, "python broken.py")

    assert "NameError" in result
    assert "exit code: 1" in result


@pytest.mark.asyncio
async def test_run_code_blocked_command(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)

    result = await run_code(1, "curl http://evil.com")

    assert "❌" in result
    assert "не разрешена" in result


@pytest.mark.asyncio
async def test_run_code_empty_command(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)

    result = await run_code(1, "")

    assert "❌" in result


@pytest.mark.asyncio
async def test_run_code_blocked_pattern(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)

    result = await run_code(1, "python -c 'import os; os.system(\"rm -rf /\")'")

    assert "❌" in result


def test_run_code_in_tool_registry():
    """Verify run_code is registered with correct params and roles."""
    assert "run_code" in TOOL_REGISTRY
    func, params, roles = TOOL_REGISTRY["run_code"]
    assert params == ["command"]
    assert "developer" in roles
    assert "architect" in roles
    assert "analyst" not in roles
    assert "manager" not in roles
