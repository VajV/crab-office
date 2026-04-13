# Horizon 1 Closed Loop + Workbench Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close the agent response loop, normalize movement events, and expose the room sandbox through a read-only `Files` tab so the next step can layer OpenClaw on top of stable contracts.

**Architecture:** Keep Java as the single frontend-facing backend. Reuse the existing Redis listeners for chat and agent events, normalize the Python `AgentEvent` contract to match Java and TypeScript, add read-only sandbox HTTP endpoints in Python, proxy them through Java, and replace the standalone task card in the room UI with a tabbed workbench for `Tasks` and `Files`.

**Tech Stack:** Java 21 / Spring Boot 4 / Redis / PostgreSQL / Python 3.12 / FastAPI / Next.js 16 / STOMP / Framer Motion

---

### Task 1: Normalize Python `AgentEvent` Contract

**Files:**
- Modify: `ai-service-python/requirements.txt`
- Modify: `ai-service-python/models.py`
- Modify: `ai-service-python/agent_logic.py`
- Create: `ai-service-python/tests/test_agent_event_contract.py`

**Step 1: Add focused Python test dependencies**

Append to `ai-service-python/requirements.txt`:

```txt
pytest==8.3.*
pytest-asyncio==0.25.*
```

**Step 2: Write the failing contract test**

Create `ai-service-python/tests/test_agent_event_contract.py`:

```python
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
```

**Step 3: Run the test and verify it fails**

Run:

```powershell
cd c:\Users\dalad\crab-office\ai-service-python
c:/Users/dalad/crab-office/.venv/Scripts/python.exe -m pytest tests/test_agent_event_contract.py -q
```

Expected: FAIL because `AgentEvent` still exposes nested `position`.

**Step 4: Implement the minimal contract fix**

Change `ai-service-python/models.py` so `AgentEvent` has top-level `x` and `y` fields instead of `position`.

Update `ai-service-python/agent_logic.py` so `move_agent()` builds:

```python
event = AgentEvent(
    roomId=room_id,
    agentExternalId=agent_external_id,
    eventType="AGENT_STATE_CHANGED",
    state=target_state,
    x=new_position.x,
    y=new_position.y,
    message=message or f"{agent_external_id} transitioned to {target_state}",
    timestamp=datetime.now(timezone.utc).isoformat(),
)
```

**Step 5: Run the test again**

Run the same `pytest` command.

Expected: PASS.

**Step 6: Commit**

```bash
git add ai-service-python/requirements.txt ai-service-python/models.py ai-service-python/agent_logic.py ai-service-python/tests/test_agent_event_contract.py
git commit -m "fix: align agent event payload with java and frontend"
```

---

### Task 2: Lock Existing Redis Bridges in Java

**Files:**
- Modify: `backend-java/src/main/java/com/craboffice/backend/service/AgentDispatcher.java`
- Create: `backend-java/src/test/java/com/craboffice/backend/service/ChatDispatcherTest.java`
- Create: `backend-java/src/test/java/com/craboffice/backend/service/AgentDispatcherTest.java`

**Step 1: Write the failing dispatcher tests**

Create `backend-java/src/test/java/com/craboffice/backend/service/ChatDispatcherTest.java`:

```java
package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import static org.mockito.Mockito.*;

class ChatDispatcherTest {

    @Test
    void forwardsAgentMessageToChatService() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatDispatcher dispatcher = new ChatDispatcher(chatService, objectMapper);

        MessageDto dto = MessageDto.builder()
                .roomId(4L)
                .agentExternalId("agent-dev-1")
                .senderType("AGENT")
                .content("hello")
                .build();

        dispatcher.onMessage(
                new DefaultMessage(objectMapper.writeValueAsBytes(dto), new byte[0]),
                null
        );

        verify(chatService).saveAndBroadcastAgentMessage(any(MessageDto.class));
    }
}
```

Create `backend-java/src/test/java/com/craboffice/backend/service/AgentDispatcherTest.java`:

```java
package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.*;

class AgentDispatcherTest {

    @Test
    void broadcastsRoomEventToWebSocketTopic() throws Exception {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentDispatcher dispatcher = new AgentDispatcher(messagingTemplate, objectMapper);

        AgentEventDto dto = AgentEventDto.builder()
                .roomId(4L)
                .agentExternalId("agent-dev-1")
                .eventType("AGENT_STATE_CHANGED")
                .state("walking")
                .x(6)
                .y(4)
                .message("move")
                .timestamp("2026-04-13T08:00:00Z")
                .build();

        dispatcher.onMessage(
                new DefaultMessage(objectMapper.writeValueAsBytes(dto), new byte[0]),
                null
        );

        verify(messagingTemplate).convertAndSend(eq("/topic/rooms/4"), any(AgentEventDto.class));
    }
}
```

**Step 2: Run the tests and verify they fail**

Run:

```powershell
cd c:\Users\dalad\crab-office\backend-java
.\mvnw.cmd -q -Dtest=ChatDispatcherTest,AgentDispatcherTest test
```

Expected: FAIL because `AgentDispatcher` currently constructs its own `ObjectMapper` and does not match the new constructor used by the test.

**Step 3: Implement the minimal hardening**

Update `AgentDispatcher.java` to inject `ObjectMapper` through the constructor, mirroring `ChatDispatcher`.

Use this shape:

```java
private final SimpMessagingTemplate messagingTemplate;
private final ObjectMapper objectMapper;

public AgentDispatcher(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
}
```

Do not change the Redis channel or WebSocket destination shape in this task.

**Step 4: Run the tests again**

Run the same Maven command.

Expected: PASS.

**Step 5: Commit**

```bash
git add backend-java/src/main/java/com/craboffice/backend/service/AgentDispatcher.java backend-java/src/test/java/com/craboffice/backend/service/ChatDispatcherTest.java backend-java/src/test/java/com/craboffice/backend/service/AgentDispatcherTest.java
git commit -m "test: lock redis chat and agent event bridges"
```

---

### Task 3: Add Read-Only Sandbox API to Python

**Files:**
- Modify: `ai-service-python/file_tools.py`
- Modify: `ai-service-python/models.py`
- Modify: `ai-service-python/main.py`
- Create: `ai-service-python/tests/test_file_tools_api.py`

**Step 1: Write the failing sandbox helper test**

Create `ai-service-python/tests/test_file_tools_api.py`:

```python
from pathlib import Path

from file_tools import list_files_structured, read_file_payload


def test_list_files_structured_returns_relative_paths(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_4"
    room_dir.mkdir(parents=True)
    (room_dir / "src" / "App.java").parent.mkdir(parents=True)
    (room_dir / "src" / "App.java").write_text("class App {}", encoding="utf-8")

    payload = list_files_structured(4)

    assert payload[0]["path"] == "src/App.java"
    assert payload[0]["size"] > 0


def test_read_file_payload_returns_file_content(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_4"
    room_dir.mkdir(parents=True)
    (room_dir / "README.md").write_text("hello", encoding="utf-8")

    payload = read_file_payload(4, "README.md")

    assert payload["path"] == "README.md"
    assert payload["content"] == "hello"
```

**Step 2: Run the test and verify it fails**

Run:

```powershell
cd c:\Users\dalad\crab-office\ai-service-python
c:/Users/dalad/crab-office/.venv/Scripts/python.exe -m pytest tests/test_file_tools_api.py -q
```

Expected: FAIL because `list_files_structured` and `read_file_payload` do not exist yet.

**Step 3: Implement structured sandbox helpers**

Add to `ai-service-python/file_tools.py`:

```python
def list_files_structured(room_id: int) -> list[dict]:
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
    target = _safe_resolve(room_id, path)
    if not target.exists():
        raise FileNotFoundError(path)
    return {
        "path": path,
        "content": target.read_text(encoding="utf-8"),
        "size": target.stat().st_size,
    }
```

Add Pydantic response models in `ai-service-python/models.py`:

```python
class SandboxFileEntry(BaseModel):
    path: str
    name: str
    size: int


class SandboxFileListResponse(BaseModel):
    roomId: int
    files: list[SandboxFileEntry]


class SandboxFileContentResponse(BaseModel):
    roomId: int
    path: str
    content: str
    size: int
```

Add endpoints in `ai-service-python/main.py`:

```python
@app.get("/rooms/{room_id}/files", response_model=SandboxFileListResponse)
async def list_room_files(room_id: int):
    return SandboxFileListResponse(roomId=room_id, files=list_files_structured(room_id))


@app.get("/rooms/{room_id}/files/content", response_model=SandboxFileContentResponse)
async def read_room_file(room_id: int, path: str):
    try:
        payload = read_file_payload(room_id, path)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File not found: {path}")
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return SandboxFileContentResponse(roomId=room_id, **payload)
```

**Step 4: Run the test again**

Run the same `pytest` command.

Expected: PASS.

**Step 5: Commit**

```bash
git add ai-service-python/file_tools.py ai-service-python/models.py ai-service-python/main.py ai-service-python/tests/test_file_tools_api.py
git commit -m "feat: expose read-only sandbox metadata in ai service"
```

---

### Task 4: Add Java Proxy Endpoints for Room Files

**Files:**
- Create: `backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileEntryDto.java`
- Create: `backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileListResponse.java`
- Create: `backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileContentResponse.java`
- Create: `backend-java/src/main/java/com/craboffice/backend/service/SandboxService.java`
- Create: `backend-java/src/main/java/com/craboffice/backend/controller/SandboxController.java`
- Create: `backend-java/src/test/java/com/craboffice/backend/controller/SandboxControllerTest.java`

**Step 1: Write the failing controller test**

Create `backend-java/src/test/java/com/craboffice/backend/controller/SandboxControllerTest.java`:

```java
package com.craboffice.backend.controller;

import com.craboffice.backend.dto.SandboxFileContentResponse;
import com.craboffice.backend.dto.SandboxFileEntryDto;
import com.craboffice.backend.dto.SandboxFileListResponse;
import com.craboffice.backend.service.SandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SandboxController.class)
class SandboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SandboxService sandboxService;

    @Test
    void returnsRoomFileList() throws Exception {
        when(sandboxService.listFiles(4L)).thenReturn(
                new SandboxFileListResponse(4L, List.of(new SandboxFileEntryDto("src/App.java", "App.java", 12L)))
        );

        mockMvc.perform(get("/api/rooms/4/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].path").value("src/App.java"));
    }

    @Test
    void returnsRoomFileContent() throws Exception {
        when(sandboxService.readFile(4L, "README.md")).thenReturn(
                new SandboxFileContentResponse(4L, "README.md", "hello", 5L)
        );

        mockMvc.perform(get("/api/rooms/4/files/content").param("path", "README.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello"));
    }
}
```

**Step 2: Run the test and verify it fails**

Run:

```powershell
cd c:\Users\dalad\crab-office\backend-java
.\mvnw.cmd -q -Dtest=SandboxControllerTest test
```

Expected: FAIL because the DTOs, controller, and service do not exist yet.

**Step 3: Implement the Java proxy layer**

Create DTO records or Lombok DTOs that match Python responses.

Create `SandboxService.java` that uses the existing `app.ai-service-url` and `HttpClient` pattern from `RoomService`:

```java
public SandboxFileListResponse listFiles(Long roomId) { ... GET aiServiceUrl + "/rooms/" + roomId + "/files" ... }

public SandboxFileContentResponse readFile(Long roomId, String path) { ... GET aiServiceUrl + "/rooms/" + roomId + "/files/content?path=" + URLEncoder.encode(path, UTF_8) ... }
```

Create `SandboxController.java`:

```java
@RestController
@RequestMapping("/api/rooms/{roomId}/files")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @GetMapping
    public SandboxFileListResponse listFiles(@PathVariable Long roomId) {
        return sandboxService.listFiles(roomId);
    }

    @GetMapping("/content")
    public SandboxFileContentResponse readFile(@PathVariable Long roomId, @RequestParam String path) {
        return sandboxService.readFile(roomId, path);
    }
}
```

If the Python service returns `404` or `400`, translate those into `ResponseStatusException` with the same status.

**Step 4: Run the test again**

Run the same Maven command.

Expected: PASS.

**Step 5: Commit**

```bash
git add backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileEntryDto.java backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileListResponse.java backend-java/src/main/java/com/craboffice/backend/dto/SandboxFileContentResponse.java backend-java/src/main/java/com/craboffice/backend/service/SandboxService.java backend-java/src/main/java/com/craboffice/backend/controller/SandboxController.java backend-java/src/test/java/com/craboffice/backend/controller/SandboxControllerTest.java
git commit -m "feat: proxy room sandbox files through java api"
```

---

### Task 5: Replace the Standalone Task Card with a Room Workbench

**Files:**
- Modify: `frontend-nextjs/src/types.ts`
- Modify: `frontend-nextjs/src/app/room/[id]/page.tsx`
- Create: `frontend-nextjs/src/components/FilesPanel.tsx`
- Create: `frontend-nextjs/src/components/WorkbenchPanel.tsx`

**Step 1: Write the UI contract down before coding**

Extend `frontend-nextjs/src/types.ts` with:

```ts
export interface SandboxFileEntry {
  path: string;
  name: string;
  size: number;
}

export interface SandboxFileListResponse {
  roomId: number;
  files: SandboxFileEntry[];
}

export interface SandboxFileContentResponse {
  roomId: number;
  path: string;
  content: string;
  size: number;
}
```

**Step 2: Implement `FilesPanel.tsx`**

Create a read-only panel that accepts:

```ts
{
  files: SandboxFileEntry[];
  selectedPath: string | null;
  fileContent: string;
  loading: boolean;
  error: string | null;
  onSelect: (path: string) => void;
  onRefresh: () => void;
}
```

UI requirements:

- left column with file list;
- right column with file content preview;
- empty state when there are no files;
- refresh button;
- read-only code block styling with scroll.

**Step 3: Implement `WorkbenchPanel.tsx`**

Create a tabbed container with two tabs:

- `Задачи`
- `Файлы`

The `Задачи` tab should render the current task list using the existing status label/color rules from `TaskPanel.tsx`.

The `Файлы` tab should render `FilesPanel`.

**Step 4: Wire room page state and data loading**

In `frontend-nextjs/src/app/room/[id]/page.tsx`:

- fetch `/api/rooms/${roomId}/files` on room load;
- store `files`, `selectedFilePath`, `selectedFileContent`, `filesLoading`, and `filesError` state;
- fetch `/api/rooms/${roomId}/files/content?path=...` when the user selects a file;
- replace `<TaskPanel tasks={tasks} />` with `<WorkbenchPanel ... />`;
- after a SYSTEM message whose content starts with `🔧`, refetch the file list.

Use a dedicated helper like:

```ts
async function loadFiles(currentRoomId: number) {
  const res = await fetch(`${API_URL}/api/rooms/${currentRoomId}/files`);
  if (!res.ok) throw new Error(`Files unavailable (${res.status})`);
  return (await res.json()) as SandboxFileListResponse;
}
```

**Step 5: Run frontend verification**

Run:

```powershell
cd c:\Users\dalad\crab-office\frontend-nextjs
npm run lint
npm run build
```

Expected: both commands succeed.

**Step 6: Commit**

```bash
git add frontend-nextjs/src/types.ts frontend-nextjs/src/app/room/[id]/page.tsx frontend-nextjs/src/components/FilesPanel.tsx frontend-nextjs/src/components/WorkbenchPanel.tsx
git commit -m "feat: add room workbench with read-only files tab"
```

---

### Task 6: Run Horizon 1 Smoke Checks

**Files:**
- No new files required

**Step 1: Start the stack**

Run:

```powershell
cd c:\Users\dalad\crab-office
docker compose up -d

cd c:\Users\dalad\crab-office\backend-java
.\mvnw.cmd spring-boot:run

cd c:\Users\dalad\crab-office\ai-service-python
c:/Users/dalad/crab-office/.venv/Scripts/python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000

cd c:\Users\dalad\crab-office\frontend-nextjs
npm run dev
```

Expected: ports `8080`, `8000`, and `3000` are listening.

**Step 2: Verify room file APIs**

Run:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/rooms/4/files' | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri 'http://localhost:8080/api/rooms/4/files/content?path=README.md' | ConvertTo-Json -Depth 5
```

Expected: JSON responses, not HTML or CORS failures.

**Step 3: Verify chat loop still persists once**

Send a unique chat message and confirm each new saved message appears once in Postgres:

```powershell
$body = @{ content = 'H1 smoke unique marker' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/rooms/4/messages' -ContentType 'application/json' -Body $body

docker exec crab-postgres psql -U crab -d craboffice -c "SELECT sender_type, COALESCE(agent_external_id, '(none)'), LEFT(content, 120), COUNT(*) FROM messages WHERE id >= 1 GROUP BY 1,2,3 HAVING COUNT(*) > 1;"
```

Expected: no duplicate rows for the new smoke sequence.

**Step 4: Verify movement event animation path**

Trigger one agent event:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8000/agents/event?room_id=4&agent_external_id=agent-dev-1&current_state=idle&target_state=walking&x=7&y=4&message=move-smoke' | ConvertTo-Json -Compress
```

Expected:

- backend log shows `Dispatched agent event to /topic/rooms/4`;
- the agent sprite moves in the room UI.

**Step 5: Verify file visibility in the room UI**

Ask an agent to create a file via the existing tool path, then refresh the `Файлы` tab.

Expected:

- the file appears in the list;
- opening it shows the saved content;
- tasks tab still renders correctly.

**Step 6: Commit**

```bash
git add .
git commit -m "feat: complete horizon 1 closed loop and workbench"
```

---

## Execution Notes

- Treat `ChatDispatcher` as existing code to harden, not as a greenfield feature.
- Treat `AgentDispatcher` plus `PixelAgent` as an existing path with a contract mismatch, not as a fresh event architecture.
- Keep the `Files` tab read-only in Horizon 1. Do not add upload, edit, or delete flows yet.
- Keep Java as the only frontend-facing backend. Do not point the browser directly at FastAPI.
- Do not start OpenClaw work until this plan is complete and smoke-tested.