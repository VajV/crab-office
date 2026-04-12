# File System Sandbox + Tool Calling — Design

**Date:** 2026-04-12  
**Status:** Approved

## Goal

Give agents "hands" — the ability to create, read, and list files in a sandboxed directory, turning chat-only agents into an autonomous team that produces real artifacts on disk.

## Architecture

### Sandbox Location

- Root: `crab-office/crab_sandbox/`
- Per-room isolation: `crab_sandbox/room_{id}/`
- Agents work only within their room's folder
- Added to `.gitignore`

### Tool Calling Approach: Text-based Parsing

Agents include `CALL_TOOL: <name> <json>` blocks in their LLM responses. Python brain parses these, executes the tool, strips the block from the chat message, and sends a SYSTEM notification about the result.

**Why not native function calling?** More stable across providers, easier to debug, no dependency on OpenRouter's function-calling proxy behavior.

### Tools (v1)

| Tool | Allowed Roles | Description |
|------|---------------|-------------|
| `write_file` | developer, architect | Create/overwrite a file in the sandbox |
| `read_file` | analyst, developer, architect | Read an existing file |
| `list_files` | all roles | List the file tree of the room's sandbox |

### Data Flow

```
User message
  → Manager delegates subtask to agent
    → Agent LLM response contains CALL_TOOL blocks
      → Python brain parses + executes tool
        → File written to crab_sandbox/room_{id}/...
        → SYSTEM message: "✅ Файл src/Foo.java создан (robot001)"
        → Clean agent text (CALL_TOOL stripped) → AGENT message
```

### Security

- **Path traversal**: `pathlib.resolve()` + verify result is inside sandbox root
- **Max file size**: 100 KB content limit
- **Blocked extensions**: `.exe`, `.bat`, `.sh`, `.ps1`, `.cmd`
- **Sandbox in .gitignore**: prevents accidental commits of generated artifacts

### Affected Files

| File | Change |
|------|--------|
| `ai-service-python/file_tools.py` | **New** — sandbox CRUD functions |
| `ai-service-python/agent_brain.py` | Parse CALL_TOOL in agent replies, execute tools |
| `ai-service-python/agent_prompts.py` | Add tool instructions to role prompts |
| `frontend-nextjs/src/components/ChatPanel.tsx` | Render file-action SYSTEM messages with file icon |
| `.gitignore` | Add `crab_sandbox/` |
