# File System Sandbox Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give agents the ability to create, read, and list real files in a sandboxed directory via text-based tool calling.

**Architecture:** New `file_tools.py` module provides sandboxed file I/O. `agent_brain.py` parses `CALL_TOOL:` blocks from LLM responses, executes them via `file_tools`, publishes SYSTEM notifications, and sends clean agent text to chat. Prompts updated so agents know about their tools.

**Tech Stack:** Python 3.12 / pathlib / FastAPI, existing Redis pub/sub, Next.js frontend

---

### Task 1: Create `file_tools.py` — Sandbox File Operations

**Files:**
- Create: `ai-service-python/file_tools.py`

**Step 1: Create the file with all three tool functions**

```python
"""Sandboxed file tools for agent brain — write, read, list files."""

from __future__ import annotations

import logging
import os
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
```

**Step 2: Verify the module loads**

Run: `cd c:\Users\Kukyo\crab-office\ai-service-python && .venv\Scripts\python.exe -c "from file_tools import TOOL_REGISTRY; print(list(TOOL_REGISTRY.keys()))"`
Expected: `['write_file', 'read_file', 'list_files']`

**Step 3: Commit**

```bash
git add ai-service-python/file_tools.py
git commit -m "feat: add sandboxed file_tools module (write/read/list)"
```

---

### Task 2: Add Tool Parsing to `agent_brain.py`

**Files:**
- Modify: `ai-service-python/agent_brain.py`

**Step 1: Add import and tool-parsing function**

After the existing imports (line 15), add:

```python
import re
from file_tools import TOOL_REGISTRY
```

Add new function after `_publish_message()` (after line 136):

```python
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

        # Check required params
        for p in required_params:
            if p not in params:
                msg = f"⚠️ Отсутствует параметр '{p}' для {tool_name}"
                notifications.append(msg)
                return msg

        # Execute
        result = func(room_id, **params)
        notifications.append(f"🔧 {agent_name} ({agent_ext_id}): {result}")
        return ""

    clean = TOOL_PATTERN.sub(replacer, reply_text).strip()
    # Remove empty lines left by tool removal
    clean = re.sub(r"\n{3,}", "\n\n", clean).strip()
    return clean, notifications
```

**Step 2: Modify `_process_subtask()` to use tool execution**

Replace the current `_process_subtask()` function (lines 171-183) with:

```python
async def _process_subtask(
    agent: dict, task_text: str, original_message: str, room_id: int, room_name: str,
) -> None:
    """Process a single subtask for a specific agent."""
    combined = (
        f"Исходное сообщение пользователя: {original_message}\n\n"
        f"Твоя задача от менеджера: {task_text}"
    )
    reply = await _generate_agent_reply(agent, combined, room_name)
    if reply.strip() == "[SKIP]":
        logger.debug("Agent %s skipped subtask in room %d", agent.get("name"), room_id)
        return

    # Execute any CALL_TOOL blocks
    clean_text, notifications = await _execute_tools(reply, room_id, agent)

    # Publish tool notifications as SYSTEM messages
    for note in notifications:
        await _publish_message(room_id, agent.get("externalId"), "SYSTEM", note)

    # Publish clean agent reply (if anything remains after tool extraction)
    if clean_text:
        await _publish_message(room_id, agent.get("externalId", "unknown"), "AGENT", clean_text)
    logger.info("Agent %s completed subtask in room %d", agent.get("name"), room_id)
```

Also update the fallback path in `handle_inbound_message()` (around line 228) — the block where there's no manager. After generating the reply, add tool execution:

```python
        reply = await _generate_agent_reply(agent, chat_msg.content, room_name)
        if reply.strip() != "[SKIP]":
            clean_text, notifications = await _execute_tools(reply, room_id, agent)
            for note in notifications:
                await _publish_message(room_id, agent.get("externalId"), "SYSTEM", note)
            if clean_text:
                await _publish_message(room_id, agent.get("externalId", "unknown"), "AGENT", clean_text)
```

**Step 3: Verify syntax**

Run: `cd c:\Users\Kukyo\crab-office\ai-service-python && .venv\Scripts\python.exe -c "import agent_brain; print('OK')"`
Expected: `OK`

**Step 4: Commit**

```bash
git add ai-service-python/agent_brain.py
git commit -m "feat: parse CALL_TOOL blocks and execute sandboxed file tools"
```

---

### Task 3: Update Agent Prompts with Tool Instructions

**Files:**
- Modify: `ai-service-python/agent_prompts.py`

**Step 1: Add tool instruction blocks to each role**

Append to the `architect` prompt (after the last line about simplicity):

```
\n\nTOOLS AVAILABLE:\n
- CALL_TOOL: write_file {"path": "relative/path", "content": "file content"}\n
- CALL_TOOL: read_file {"path": "relative/path"}\n
- CALL_TOOL: list_files {}\n
When you need to create project structure or files, USE write_file. Do not just describe the file — create it.
```

Append to the `developer` prompt (after the last line about simplest approach):

```
\n\nTOOLS AVAILABLE:\n
- CALL_TOOL: write_file {"path": "relative/path", "content": "file content"}\n
- CALL_TOOL: read_file {"path": "relative/path"}\n
- CALL_TOOL: list_files {}\n
When asked to write code, USE write_file to create real files. Do not just show code in chat — save it to a file.\n
Example: CALL_TOOL: write_file {"path": "src/Main.java", "content": "public class Main {\\n    public static void main(String[] args) {\\n        System.out.println(\"Hello\");\\n    }\\n}"}
```

Append to the `analyst` prompt (after the last line about missing data):

```
\n\nTOOLS AVAILABLE:\n
- CALL_TOOL: read_file {"path": "relative/path"}\n
- CALL_TOOL: list_files {}\n
Use read_file to inspect existing project files before giving recommendations. Use list_files to see what's in the workspace.
```

The `manager` prompt stays unchanged (manager delegates, doesn't use file tools directly).

**Step 2: Verify prompts load**

Run: `cd c:\Users\Kukyo\crab-office\ai-service-python && .venv\Scripts\python.exe -c "from agent_prompts import build_system_prompt; p = build_system_prompt('developer', 'Bot', 'R1'); print('TOOLS' in p)"`
Expected: `True`

**Step 3: Commit**

```bash
git add ai-service-python/agent_prompts.py
git commit -m "feat: add tool instructions to agent role prompts"
```

---

### Task 4: Add `crab_sandbox/` to `.gitignore`

**Files:**
- Modify: `.gitignore`

**Step 1: Append sandbox entry**

Add at the end of `.gitignore`:

```
# Agent sandbox
crab_sandbox/
```

**Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: gitignore crab_sandbox directory"
```

---

### Task 5: Improve ChatPanel SYSTEM Message Rendering

**Files:**
- Modify: `frontend-nextjs/src/components/ChatPanel.tsx`

**Step 1: Update the SYSTEM message block to detect file tool notifications**

In the message rendering section, update the SYSTEM `<span>` to show a file icon when the message starts with 🔧 or ✅ or ❌ and contains a file path pattern:

Replace the current SYSTEM label:
```tsx
{m.senderType === "SYSTEM" && (
  <span className="text-xs text-indigo-400 block mb-1">⚙️ система</span>
)}
```

With:
```tsx
{m.senderType === "SYSTEM" && (
  <span className="text-xs text-indigo-400 block mb-1">
    {m.content.startsWith("🔧") ? "📁 файловая операция" : "⚙️ система"}
  </span>
)}
```

**Step 2: Commit**

```bash
git add frontend-nextjs/src/components/ChatPanel.tsx
git commit -m "feat: distinguish file tool notifications in chat UI"
```

---

### Task 6: End-to-End Smoke Test

**Step 1: Restart AI brain service**

Kill existing agent_brain process, then:
```bash
cd c:\Users\Kukyo\crab-office\ai-service-python
.venv\Scripts\Activate.ps1
python -m agent_brain
```

**Step 2: Send a test message**

```powershell
$body = @{content = "Developer, create a Java class MarsKitchen with a cook() method"} | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/rooms/16/messages" -Method Post -ContentType "application/json" -Body $body
```

**Step 3: Verify results**

1. Check AI brain logs: should show `write_file:` log entry
2. Check `crab_sandbox/room_16/` directory: should contain the Java file
3. Check messages API: should have SYSTEM message with "✅ Файл ... создан" and AGENT message with clean text

```powershell
Get-ChildItem -Recurse c:\Users\Kukyo\crab-office\crab_sandbox\room_16\
$msgs = Invoke-RestMethod -Uri "http://localhost:8080/api/rooms/16/messages" -Method Get
$msgs | Select-Object -Last 5 | ForEach-Object { "$($_.id) [$($_.senderType)] $($_.content.Substring(0, [Math]::Min(80, $_.content.Length)))" }
```

Expected: Java file exists on disk, SYSTEM notification in chat, clean agent text in chat.

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat: file system sandbox with tool calling — complete"
```
