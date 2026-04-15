"""Sandboxed file tools for agent brain — write, read, list files, run code."""

from __future__ import annotations

import asyncio
import logging
import subprocess
from pathlib import Path

logger = logging.getLogger(__name__)

SANDBOX_ROOT = Path(__file__).resolve().parent.parent / "crab_sandbox"

MAX_FILE_SIZE = 100_000  # 100 KB
BLOCKED_EXTENSIONS = {".exe", ".bat", ".sh", ".ps1", ".cmd", ".com", ".scr", ".msi"}


def _sandbox_path(room_id: int) -> Path:
    """Return the sandbox directory for a room, creating it if needed."""
    room_dir = SANDBOX_ROOT / f"room_{room_id}"
    room_dir.mkdir(parents=True, exist_ok=True)
    return room_dir


def _safe_resolve(room_id: int, relative_path: str) -> Path:
    """Resolve a relative path inside the room sandbox. Raises ValueError on escape."""
    room_dir = _sandbox_path(room_id)
    target = (room_dir / relative_path).resolve()
    if not str(target).startswith(str(room_dir)):
        raise ValueError(f"Path escapes sandbox: {relative_path}")
    if target.suffix.lower() in BLOCKED_EXTENSIONS:
        raise ValueError(f"Blocked file extension: {target.suffix}")
    return target


def write_file(room_id: int, path: str, content: str) -> str:
    """Write content to a file in the room sandbox. Returns status message."""
    try:
        target = _safe_resolve(room_id, path)
        if len(content.encode("utf-8")) > MAX_FILE_SIZE:
            return f"❌ Файл слишком большой (макс. {MAX_FILE_SIZE // 1000} КБ)"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        logger.info("write_file: %s (%d bytes)", target, len(content))
        return f"✅ Файл {path} создан ({len(content)} байт)"
    except ValueError as e:
        return f"❌ {e}"
    except Exception as e:
        logger.exception("write_file failed")
        return f"❌ Ошибка записи: {e}"


def read_file(room_id: int, path: str) -> str:
    """Read a file from the room sandbox. Returns content or error."""
    try:
        target = _safe_resolve(room_id, path)
        if not target.exists():
            return f"❌ Файл не найден: {path}"
        if target.stat().st_size > MAX_FILE_SIZE:
            return f"❌ Файл слишком большой для чтения"
        content = target.read_text(encoding="utf-8")
        return content
    except ValueError as e:
        return f"❌ {e}"
    except Exception as e:
        logger.exception("read_file failed")
        return f"❌ Ошибка чтения: {e}"


def list_files(room_id: int) -> str:
    """List all files in the room sandbox. Returns tree-like listing."""
    room_dir = _sandbox_path(room_id)
    files = sorted(room_dir.rglob("*"))
    files = [f for f in files if f.is_file()]
    if not files:
        return "📂 Песочница пуста"
    lines = [f"📂 room_{room_id}/"]
    for f in files:
        rel = f.relative_to(room_dir)
        size = f.stat().st_size
        lines.append(f"  📄 {rel} ({size} байт)")
    return "\n".join(lines)


def list_files_structured(room_id: int) -> list[dict]:
    """Return structured file metadata for the room sandbox."""
    room_dir = _sandbox_path(room_id)
    files = [f for f in sorted(room_dir.rglob("*")) if f.is_file()]
    return [
        {
            "path": str(f.relative_to(room_dir)).replace("\\", "/"),
            "name": f.name,
            "size": f.stat().st_size,
        }
        for f in files
    ]


def read_file_payload(room_id: int, path: str) -> dict:
    """Return file content and metadata for the given path."""
    target = _safe_resolve(room_id, path)
    if not target.exists():
        raise FileNotFoundError(path)
    return {
        "path": path,
        "content": target.read_text(encoding="utf-8"),
        "size": target.stat().st_size,
    }


# Registry: tool name → (function, required_params, allowed_roles)
# Functions may be sync (return str) or async (return Awaitable[str]).
TOOL_REGISTRY: dict[str, tuple] = {
    "write_file": (write_file, ["path", "content"], {"developer", "architect"}),
    "read_file": (read_file, ["path"], {"analyst", "developer", "architect"}),
    "list_files": (list_files, [], {"architect", "developer", "analyst", "manager"}),
    "run_code": (None, ["command"], {"developer", "architect"}),  # set below
}


async def run_code(room_id: int, command: str) -> str:
    """Execute a command inside the room sandbox with restrictions."""
    ALLOWED_COMMANDS = {"python", "python3", "node", "cat", "echo", "ls", "dir"}
    BLOCKED_PATTERNS = {"rm ", "del ", "curl ", "wget ", "sudo ", "chmod ", "chown "}

    parts = command.strip().split()
    if not parts:
        return "❌ Пустая команда"

    base_cmd = Path(parts[0]).name.lower()
    if base_cmd not in ALLOWED_COMMANDS:
        return f"❌ Команда '{base_cmd}' не разрешена. Доступные: {', '.join(sorted(ALLOWED_COMMANDS))}"

    cmd_lower = command.lower()
    for blocked in BLOCKED_PATTERNS:
        if blocked in cmd_lower:
            return f"❌ Запрещённый паттерн в команде: {blocked.strip()}"

    sandbox = _sandbox_path(room_id)
    max_output = 10_000  # 10 KB

    try:
        proc = await asyncio.to_thread(
            subprocess.run,
            command,
            shell=True,
            cwd=str(sandbox),
            capture_output=True,
            text=True,
            timeout=30,
        )
        stdout = proc.stdout[:max_output] if proc.stdout else ""
        stderr = proc.stderr[:max_output] if proc.stderr else ""
        result_parts = []
        if stdout:
            result_parts.append(stdout)
        if stderr:
            result_parts.append(f"[stderr] {stderr}")
        result_parts.append(f"(exit code: {proc.returncode})")
        return "\n".join(result_parts) if result_parts else "(no output)"
    except subprocess.TimeoutExpired:
        return "❌ Таймаут: команда не завершилась за 30 секунд"
    except Exception as e:
        logger.exception("run_code failed")
        return f"❌ Ошибка выполнения: {e}"


# Wire run_code into the registry (needs to be after the function definition)
TOOL_REGISTRY["run_code"] = (run_code, ["command"], {"developer", "architect"})


async def execute_tool(room_id: int, tool_name: str, params: dict, role: str = "developer") -> dict:
    """Look up a tool in the registry, check permissions, and execute it.

    Returns ``{"status": "completed"|"failed", "result": str}``.
    """
    entry = TOOL_REGISTRY.get(tool_name)
    if entry is None:
        return {"status": "failed", "result": f"Unknown tool: {tool_name}"}

    fn, required_params, allowed_roles = entry
    if role not in allowed_roles:
        return {"status": "failed", "result": f"Role '{role}' is not allowed to use '{tool_name}'"}

    missing = [p for p in required_params if p not in params]
    if missing:
        return {"status": "failed", "result": f"Missing params: {', '.join(missing)}"}

    try:
        if asyncio.iscoroutinefunction(fn):
            result = await fn(room_id, **params)
        else:
            result = fn(room_id, **params)
        failed = isinstance(result, str) and result.startswith("❌")
        return {"status": "failed" if failed else "completed", "result": result}
    except Exception as e:
        logger.exception("execute_tool failed: %s", tool_name)
        return {"status": "failed", "result": str(e)}
