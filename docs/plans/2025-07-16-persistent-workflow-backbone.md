# Persistent Workflow Backbone — Task Queue & Agent Orchestration

> Connects the in-memory agent brain to the persistent task layer so every subtask
> is tracked in the database, visible on the frontend in real time, and survives
> process restarts.

**Repo:** crab-office  
**Date:** 2025-07-16  
**Depends on:** `2025-07-16-agent-chat-system.md` (fully implemented)

---

## Current state (what already works)

| Layer | Status | Key files |
|-------|--------|-----------|
| Manager delegation | ✅ In-memory DAG with `order` grouping | `ai-service-python/agent_brain.py` |
| TaskEntity / TaskService | ✅ Flat CRUD, WebSocket broadcast on create/update | `backend-java/.../entity/TaskEntity.java`, `.../service/TaskService.java` |
| TaskController | ⚠️ Only `GET /api/rooms/{roomId}/tasks` | `.../controller/TaskController.java` |
| TaskPanel (frontend) | ⚠️ Renders tasks, but loads once via REST — no real-time | `frontend-nextjs/src/components/TaskPanel.tsx` |
| useSocket | ⚠️ No subscription to `/topic/rooms/{id}/tasks` | `frontend-nextjs/src/hooks/useSocket.ts` |

**Core gap:** The agent brain creates subtasks, executes them in-memory, but never
persists them to the Java backend. The frontend never sees tasks appear or change
status in real time.

---

## Architecture changes

```
User message
    │
    ▼
[Java ChatService] ──Redis crab:chat-inbound──▶ [Python agent_brain]
                                                        │
                                           1. Manager decomposes
                                           2. POST /api/tasks (create PENDING)
                                           3. PATCH /api/tasks/{id}/status (IN_PROGRESS)
                                           4. Agent executes
                                           5. PATCH /api/tasks/{id}/status (DONE/FAILED)
                                                        │
[Java TaskService] ◀── HTTP from Python ───────────────┘
    │
    ▼  WebSocket broadcast /topic/rooms/{roomId}/tasks
[Frontend TaskPanel] ◀── STOMP subscription ── [useSocket]
```

No new Redis channels needed. Python calls Java REST directly (same pattern as
the existing `_fetch_room_agents` call). Java TaskService already broadcasts
via WebSocket on create/update.

---

## Stage 1: Java — Expose Task REST endpoints

### Task 1.1 — Add `POST` and `PATCH` to TaskController

Expose endpoints so the Python brain can create tasks and update their status.

**File:** `backend-java/src/main/java/com/craboffice/backend/controller/TaskController.java`

```java
package com.craboffice.backend.controller;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms/{roomId}/tasks")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public List<TaskDto> getTasks(@PathVariable Long roomId) {
        return taskService.getTasks(roomId);
    }

    @PostMapping
    public TaskDto createTask(@PathVariable Long roomId, @RequestBody TaskDto body) {
        return taskService.createTask(
                roomId,
                body.getTitle(),
                body.getDescription(),
                body.getAssignedAgentExternalId()
        );
    }

    @PatchMapping("/{taskId}/status")
    public TaskDto updateStatus(
            @PathVariable Long roomId,
            @PathVariable Long taskId,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return taskService.updateStatus(taskId, status);
    }
}
```

**Verify:** `cd backend-java && mvnw.cmd compile`

---

### Task 1.2 — Add `result` field to TaskEntity and TaskDto

Store the agent's output per task so the manager (or user) can review results.

**File:** `backend-java/src/main/java/com/craboffice/backend/entity/TaskEntity.java`

Add after the `description` field:

```java
    @Column(columnDefinition = "TEXT")
    private String result;
```

**File:** `backend-java/src/main/java/com/craboffice/backend/dto/TaskDto.java`

Add field:

```java
    private String result;
```

**File:** `backend-java/src/main/java/com/craboffice/backend/service/TaskService.java`

Update `toDto` to include `result`:

```java
    private TaskDto toDto(TaskEntity e) {
        return TaskDto.builder()
                .id(e.getId())
                .roomId(e.getRoomId())
                .assignedAgentExternalId(e.getAssignedAgentExternalId())
                .title(e.getTitle())
                .description(e.getDescription())
                .result(e.getResult())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt().toString())
                .updatedAt(e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null)
                .build();
    }
```

Add `updateResult` method:

```java
    public TaskDto updateResult(Long taskId, String result) {
        TaskEntity entity = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));
        entity.setResult(result);
        entity.setUpdatedAt(Instant.now());
        entity = taskRepository.save(entity);
        TaskDto dto = toDto(entity);
        messagingTemplate.convertAndSend("/topic/rooms/" + entity.getRoomId() + "/tasks", dto);
        return dto;
    }
```

**Verify:** `cd backend-java && mvnw.cmd compile`

---

### Task 1.3 — Add result PATCH endpoint

**File:** `backend-java/src/main/java/com/craboffice/backend/controller/TaskController.java`

Add inside the class:

```java
    @PatchMapping("/{taskId}/result")
    public TaskDto updateResult(
            @PathVariable Long roomId,
            @PathVariable Long taskId,
            @RequestBody Map<String, String> body) {
        String result = body.get("result");
        return taskService.updateResult(taskId, result);
    }
```

**Verify:** `cd backend-java && mvnw.cmd compile`

---

## Stage 2: Python — Persist subtasks from agent brain

### Task 2.1 — Add HTTP helper for task CRUD

Create a thin async client that the brain uses to talk to the Java task API.

**File:** `ai-service-python/task_client.py` (new file)

```python
"""Async HTTP client for the Java Task REST API."""

from __future__ import annotations

import logging
import os

import httpx

logger = logging.getLogger(__name__)

BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://localhost:8080")


async def create_task(
    room_id: int, title: str, description: str, agent_external_id: str
) -> dict | None:
    """POST /api/rooms/{roomId}/tasks — returns the created TaskDto."""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks"
    payload = {
        "title": title,
        "description": description,
        "assignedAgentExternalId": agent_external_id,
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to create task via Java API")
        return None


async def update_task_status(task_id: int, room_id: int, status: str) -> dict | None:
    """PATCH /api/rooms/{roomId}/tasks/{taskId}/status"""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks/{task_id}/status"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.patch(url, json={"status": status})
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to update task status via Java API")
        return None


async def update_task_result(task_id: int, room_id: int, result: str) -> dict | None:
    """PATCH /api/rooms/{roomId}/tasks/{taskId}/result"""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks/{task_id}/result"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.patch(url, json={"result": result})
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to update task result via Java API")
        return None
```

**Verify:** `cd ai-service-python && python -c "import task_client; print('OK')"`

---

### Task 2.2 — Wire task persistence into agent_brain.py

Modify the delegation flow so each subtask is:
1. Created as PENDING in Java before execution
2. Set to IN_PROGRESS when the agent starts
3. Set to DONE (with result) or FAILED after execution

**File:** `ai-service-python/agent_brain.py`

Add import at top (after existing imports):

```python
from task_client import create_task, update_task_status, update_task_result
```

Replace the `_process_subtask` function:

```python
async def _process_subtask(
    agent: dict, task_text: str, original_message: str,
    room_id: int, room_name: str, task_id: int | None = None,
) -> None:
    """Process a single subtask for a specific agent."""
    # Mark task as in-progress
    if task_id:
        await update_task_status(task_id, room_id, "IN_PROGRESS")

    combined = (
        f"Исходное сообщение пользователя: {original_message}\n\n"
        f"Твоя задача от менеджера: {task_text}"
    )

    try:
        reply = await _generate_agent_reply(agent, combined, room_name)
        if reply.strip() == "[SKIP]":
            logger.debug("Agent %s skipped subtask in room %d", agent.get("name"), room_id)
            if task_id:
                await update_task_status(task_id, room_id, "DONE")
            return

        clean_reply, notifications = await _execute_tools(reply, room_id, agent)

        for note in notifications:
            await _publish_message(room_id, agent.get("externalId", "unknown"), "SYSTEM", note)

        if clean_reply:
            await _publish_message(room_id, agent.get("externalId", "unknown"), "AGENT", clean_reply)

        # Mark task done with result
        if task_id:
            result_text = clean_reply[:2000] if clean_reply else "(skipped)"
            await update_task_result(task_id, room_id, result_text)
            await update_task_status(task_id, room_id, "DONE")

        logger.info("Agent %s completed subtask in room %d", agent.get("name"), room_id)

    except Exception:
        logger.exception("Agent %s failed subtask in room %d", agent.get("name"), room_id)
        if task_id:
            await update_task_status(task_id, room_id, "FAILED")
```

In `handle_inbound_message`, replace the subtask execution block (the section after
`# Step 3: Execute subtasks step-by-step by manager order.`):

```python
    # Step 3: Create tasks in Java and execute step-by-step.
    agent_map = {a.get("externalId"): a for a in agents}
    step_groups = _group_subtasks_by_order(subtasks)

    # Create all tasks as PENDING first
    for subtask in subtasks:
        task_dto = await create_task(
            room_id=room_id,
            title=subtask["task"][:200],
            description=subtask["task"],
            agent_external_id=subtask["agentExternalId"],
        )
        subtask["_taskId"] = task_dto["id"] if task_dto else None

    for index, (order, step_subtasks) in enumerate(step_groups):
        remaining_steps = [step_order for step_order, _ in step_groups[index + 1:]]
        step_status = _build_step_status(order, step_subtasks, agents, remaining_steps)
        await _publish_message(room_id, manager.get("externalId"), "SYSTEM", step_status)

        coros = []
        for subtask in step_subtasks:
            agent = agent_map.get(subtask["agentExternalId"])
            if agent:
                coros.append(
                    _process_subtask(
                        agent, subtask["task"], chat_msg.content,
                        room_id, room_name, task_id=subtask.get("_taskId"),
                    )
                )

        if coros:
            await asyncio.gather(*coros)

        await _publish_message(
            room_id,
            manager.get("externalId"),
            "SYSTEM",
            f"✅ Шаг {order} завершен.",
        )
```

**Verify:** `cd ai-service-python && python -c "from agent_brain import handle_inbound_message; print('OK')"`

---

## Stage 3: Frontend — Real-time task updates

### Task 3.1 — Add task subscription to useSocket

**File:** `frontend-nextjs/src/hooks/useSocket.ts`

Add a third callback parameter and a subscription to `/topic/rooms/{id}/tasks`:

```typescript
"use client";

import { useEffect, useRef } from "react";
import { Client } from "@stomp/stompjs";
import type { AgentEvent, Message, Task } from "@/types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL || "ws://localhost:8080/ws";

export function useSocket(
  roomId: number | null,
  onEvent: (event: AgentEvent) => void,
  onMessage?: (msg: Message) => void,
  onTask?: (task: Task) => void,
) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;
  const onTaskRef = useRef(onTask);
  onTaskRef.current = onTask;

  useEffect(() => {
    if (roomId == null) return;

    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log("[WS] Connected to", WS_URL, "room", roomId);
        client.subscribe(`/topic/rooms/${roomId}`, (msg) => {
          try {
            const event: AgentEvent = JSON.parse(msg.body);
            onEventRef.current(event);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/chat`, (msg) => {
          try {
            const parsed: Message = JSON.parse(msg.body);
            onMessageRef.current?.(parsed);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/tasks`, (msg) => {
          try {
            const task: Task = JSON.parse(msg.body);
            onTaskRef.current?.(task);
          } catch {
            // ignore
          }
        });
      },
      onStompError: (frame) => {
        console.error("[WS] STOMP error:", frame.headers["message"], frame.body);
      },
      onWebSocketError: (event) => {
        console.error("[WS] WebSocket error:", event);
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [roomId]);
}
```

---

### Task 3.2 — Wire real-time tasks into room page

**File:** `frontend-nextjs/src/app/room/[id]/page.tsx`

Add `handleTask` callback and pass it to `useSocket`:

```typescript
  const handleTask = useCallback((task: Task) => {
    setTasks((prev) => {
      const idx = prev.findIndex((t) => t.id === task.id);
      if (idx >= 0) {
        // Update existing task
        const next = [...prev];
        next[idx] = task;
        return next;
      }
      // New task — prepend (newest first)
      return [task, ...prev];
    });
  }, []);

  useSocket(room?.id ?? null, handleEvent, handleMessage, handleTask);
```

Remove the old `useSocket` call that only passes two callbacks.

---

### Task 3.3 — Show task result in TaskPanel

**File:** `frontend-nextjs/src/types.ts`

Add `result` to the `Task` interface:

```typescript
export interface Task {
  id: number;
  roomId: number;
  assignedAgentExternalId: string | null;
  title: string;
  description: string | null;
  result: string | null;
  status: "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED";
  createdAt: string;
  updatedAt: string | null;
}
```

**File:** `frontend-nextjs/src/components/TaskPanel.tsx`

Add result display (collapsed by default):

```tsx
"use client";

import { useState } from "react";
import type { Task } from "@/types";

const statusColors: Record<string, string> = {
  PENDING: "bg-gray-600",
  IN_PROGRESS: "bg-blue-600",
  DONE: "bg-green-600",
  FAILED: "bg-red-600",
};

const statusLabels: Record<string, string> = {
  PENDING: "⏳ Ожидание",
  IN_PROGRESS: "🔄 В работе",
  DONE: "✅ Готово",
  FAILED: "❌ Ошибка",
};

interface TaskPanelProps {
  tasks: Task[];
}

export default function TaskPanel({ tasks }: TaskPanelProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  if (tasks.length === 0) return null;

  return (
    <div className="w-full max-w-lg bg-gray-900 border border-gray-700 rounded-lg overflow-hidden">
      <div className="px-3 py-2 bg-gray-800 text-sm font-medium text-gray-300 border-b border-gray-700">
        📋 Задачи ({tasks.filter((t) => t.status === "DONE").length}/{tasks.length})
      </div>
      <div className="p-3 space-y-2 max-h-64 overflow-y-auto">
        {tasks.map((t) => (
          <div key={t.id}>
            <button
              onClick={() => setExpandedId(expandedId === t.id ? null : t.id)}
              className="flex items-center gap-2 text-sm w-full text-left hover:bg-gray-800 rounded px-1 py-0.5"
            >
              <span className={`px-2 py-0.5 rounded text-xs text-white ${statusColors[t.status] || "bg-gray-600"}`}>
                {statusLabels[t.status] || t.status}
              </span>
              <span className="text-gray-200 truncate flex-1">{t.title}</span>
              {t.assignedAgentExternalId && (
                <span className="text-xs text-gray-500">{t.assignedAgentExternalId}</span>
              )}
            </button>
            {expandedId === t.id && t.result && (
              <div className="mt-1 ml-6 p-2 bg-gray-800 rounded text-xs text-gray-300 whitespace-pre-wrap max-h-32 overflow-y-auto">
                {t.result}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

**Verify:** `cd frontend-nextjs && npx next build`

---

## Summary of changes

| What changed | Why |
|---|---|
| `TaskController` — added `POST` and two `PATCH` endpoints | Python brain needs to create tasks and update status/result |
| `TaskEntity` / `TaskDto` — added `result` field | Store agent output per task for review |
| `TaskService` — added `updateResult` method | Persist and broadcast result changes |
| New `task_client.py` in Python | Thin HTTP client for Java task API |
| `agent_brain.py` — `_process_subtask` now persists lifecycle | Each subtask: create → IN_PROGRESS → DONE/FAILED |
| `agent_brain.py` — `handle_inbound_message` creates tasks before execution | All tasks visible immediately as PENDING |
| `useSocket.ts` — added `/tasks` subscription | Frontend receives real-time task updates |
| `room/[id]/page.tsx` — added `handleTask` callback | Merges incoming task updates into state |
| `types.ts` — added `result` to `Task` | Frontend knows about the new field |
| `TaskPanel.tsx` — expandable rows with result, progress counter | Users can click a task to see the agent's output |

---

## What was NOT changed (intentionally)

- **No new Redis channels.** Python calls Java directly via HTTP — simpler, no new
  dispatcher needed, and Java already broadcasts to WebSocket on every
  `taskRepository.save()`.
- **No `parentTaskId` / `workflowId` / dependency graph in DB.** The in-memory DAG
  in `agent_brain.py` with `order` grouping is sufficient for the current
  sequential-step model. Adding DB-level dependencies is a future stage when
  we need cross-request task resumption.
- **No Telegram task notifications.** The Telegram bot currently bridges chat only.
  Adding task notifications there is a separate feature.

---

## Execution order

1. Stage 1 (Java) → Stage 2 (Python) → Stage 3 (Frontend)
2. Each stage can be verified independently before moving to the next.
3. Total: 7 tasks across 3 stages.
