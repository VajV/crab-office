"""Sandboxed file tools for agent brain — write, read, list files."""

from __future__ import annotations

import logging
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


# Registry: tool name → (function, required_params, allowed_roles)
TOOL_REGISTRY: dict[str, tuple] = {
    "write_file": (write_file, ["path", "content"], {"developer", "architect"}),
    "read_file": (read_file, ["path"], {"analyst", "developer", "architect"}),
    "list_files": (list_files, [], {"architect", "developer", "analyst", "manager"}),
}
