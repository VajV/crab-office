# OpenClaw Visual Office Phase 1 Contract

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Phase Goal:** Freeze the office simulation contract before implementation so backend, OpenClaw runtime, AI-service bridge, and frontend all build against one world model, one event vocabulary, and one ownership model.

**Current Baseline:**

- `backend-java` still exposes a single-room payload with one `layout` and a flat `agents` list.
- `frontend-nextjs` renders one office scene and updates agents from `AgentEvent { roomId, agentExternalId, x, y, state }`.
- `ai-service-python` already publishes low-level `AgentEvent` and `AgentAction`, but those contracts are not rich enough for a three-location office simulation.
- WebSocket/STOMP plumbing already exists and should be extended rather than replaced.

**Decision:** Keep `room` as the top-level office container for MVP and model the three fixed locations inside that room.

---

## 1. Ownership Rules

### Backend owns world state

Spring Boot is the source of truth for:

- office room identity
- fixed locations
- persistent agents
- agent coordinates and location assignment
- current visible agent state
- tasks and task assignment
- interactions and event history

Backend responsibilities:

- validate all state transitions
- persist world mutations
- emit normalized simulation events after successful mutation
- serve world snapshots for frontend hydration

### OpenClaw owns intent and runtime execution

OpenClaw in Docker is the runtime executor that:

- interprets user intent such as `создай SEO`
- chooses a structured command
- performs tool work such as web research or browser access
- reports command progress and runtime activity

OpenClaw must not be treated as the source of truth for office state. It can request state changes, but backend confirms and persists them.

### AI-service is an integration bridge, not a state owner

`ai-service-python` may remain the temporary ingress/adapter for OpenClaw HTTP/WebSocket traffic and low-level tool events, but it should not become a second simulation state store.

### Frontend renders confirmed state only

Next.js should:

- hydrate from backend snapshot APIs
- subscribe to backend WebSocket updates
- animate backend-confirmed deltas
- avoid inventing or persisting world state on its own

---

## 2. Office World Model

### Top-level model

For MVP, one `room` contains three fixed locations.

```text
Room
  -> Locations[3]
  -> Agents[*]
  -> Tasks[*]
  -> Events[*]
```

### Fixed locations

The office always contains these three locations:

1. `marketing-room`
2. `engineering-room`
3. `ops-room`

Each location needs:

- stable `id`
- display `name`
- `kind`
- `width`
- `height`
- `backgroundPreset`
- `spawnPoints`
- `interactionPoints`
- `sortOrder`

### Role-to-location defaults

Initial placement rules:

- `seo`, `copywriter`, `analyst`, `designer` -> `marketing-room`
- `developer`, `architect`, `qa` -> `engineering-room`
- `manager`, `operator`, `support` -> `ops-room`

If a role is unknown, default to `ops-room` until a more specific mapping exists.

---

## 3. Canonical Entity Schemas

### `Location`

```json
{
  "id": "marketing-room",
  "roomId": 4,
  "key": "marketing-room",
  "name": "Marketing",
  "kind": "marketing",
  "width": 12,
  "height": 8,
  "backgroundPreset": "marketing-loft",
  "spawnPoints": [{ "x": 2, "y": 5 }],
  "interactionPoints": [{ "x": 7, "y": 4, "kind": "meeting-desk" }],
  "sortOrder": 1
}
```

### `Agent`

```json
{
  "id": 101,
  "roomId": 4,
  "externalId": "agent-seo-1",
  "name": "SEO-1",
  "role": "seo",
  "spriteKey": "seo-blue",
  "locationId": "marketing-room",
  "x": 3,
  "y": 5,
  "state": "idle",
  "statusText": "Waiting for assignment",
  "currentTaskId": null,
  "targetAgentId": null,
  "createdAt": "2026-04-17T12:00:00Z",
  "updatedAt": "2026-04-17T12:00:00Z"
}
```

### `Task`

```json
{
  "id": 42,
  "roomId": 4,
  "title": "Prepare SEO brief",
  "description": "Draft keyword plan for landing page",
  "status": "IN_PROGRESS",
  "assignedAgentId": 101,
  "requestedBy": "user",
  "source": "openclaw",
  "locationHint": "marketing-room",
  "createdAt": "2026-04-17T12:05:00Z",
  "updatedAt": "2026-04-17T12:07:00Z"
}
```

### `AgentEvent`

```json
{
  "id": 9001,
  "roomId": 4,
  "locationId": "marketing-room",
  "agentId": 101,
  "eventType": "agent.state_changed",
  "state": "thinking",
  "payloadJson": {
    "fromState": "idle",
    "toState": "thinking",
    "statusText": "Analyzing search intent"
  },
  "correlationId": "corr_abc",
  "runId": "run_xyz",
  "timestamp": "2026-04-17T12:06:00Z"
}
```

### `AgentMovement`

```json
{
  "id": 501,
  "agentId": 101,
  "fromLocationId": "marketing-room",
  "toLocationId": "marketing-room",
  "fromX": 3,
  "fromY": 5,
  "toX": 7,
  "toY": 5,
  "reason": "Going to copywriter",
  "startedAt": "2026-04-17T12:08:00Z",
  "completedAt": null
}
```

### `AgentInteraction`

```json
{
  "id": 701,
  "roomId": 4,
  "initiatorAgentId": 101,
  "targetAgentId": 102,
  "interactionType": "discussion",
  "status": "STARTED",
  "taskId": 42,
  "summary": "Discussing landing page keywords",
  "startedAt": "2026-04-17T12:09:00Z",
  "endedAt": null
}
```

---

## 4. Canonical Visible Agent States

Visible states are intentionally small and stable.

MVP states:

- `idle`
- `thinking`
- `walking`
- `talking`
- `working`
- `waiting`

Rules:

- keep the user-facing state list short
- store extra detail in `statusText` and event payload metadata
- do not create a new top-level state for every tool

Examples:

- browser research should usually be visible as `thinking` or `working`
- travel to another agent should be visible as `walking`
- active exchange with another agent should be visible as `talking`

Optional later states such as `blocked` or `error` may be added after MVP if failure UX needs them.

---

## 5. Event Vocabulary

All user-visible office events must use a normalized envelope.

```json
{
  "eventId": "evt_123",
  "eventType": "agent.state_changed",
  "version": 1,
  "roomId": 4,
  "locationId": "marketing-room",
  "agentExternalId": "agent-seo-1",
  "correlationId": "corr_abc",
  "runId": "run_xyz",
  "timestamp": "2026-04-17T12:00:00Z",
  "payload": {}
}
```

### Required event types for MVP

- `agent.spawned`
- `agent.state_changed`
- `agent.moved`
- `agent.task_assigned`
- `agent.interaction_started`
- `agent.interaction_finished`
- `web_research_started`
- `web_research_finished`
- `task.completed`

### Event ownership rule

- OpenClaw may request a change.
- Backend persists the change.
- Backend emits the normalized event.
- Frontend renders the normalized event.

Current low-level streams such as `AgentAction` remain useful for workbench/debugging, but they do not replace the simulation event stream.

---

## 6. OpenClaw Command Vocabulary

OpenClaw should send structured commands, not UI instructions.

### Required commands for MVP

- `spawn_agent`
- `set_agent_state`
- `move_agent`
- `start_interaction`
- `finish_interaction`
- `start_web_research`
- `finish_web_research`
- `assign_task`
- `complete_task`

### Example: `spawn_agent`

```json
{
  "commandType": "spawn_agent",
  "payload": {
    "role": "seo",
    "name": "SEO-1",
    "preferredLocationId": "marketing-room"
  }
}
```

### Example: `set_agent_state`

```json
{
  "commandType": "set_agent_state",
  "payload": {
    "agentExternalId": "agent-seo-1",
    "state": "thinking",
    "statusText": "Planning keyword strategy"
  }
}
```

### Example: `move_agent`

```json
{
  "commandType": "move_agent",
  "payload": {
    "agentExternalId": "agent-seo-1",
    "targetAgentExternalId": "agent-copy-1",
    "locationId": "marketing-room",
    "x": 8,
    "y": 4,
    "reason": "Need to align copy with keywords"
  }
}
```

### Example: `start_web_research`

```json
{
  "commandType": "start_web_research",
  "payload": {
    "agentExternalId": "agent-seo-1",
    "query": "best landing page SEO structure",
    "tool": "browser.navigate"
  }
}
```

### Example: `complete_task`

```json
{
  "commandType": "complete_task",
  "payload": {
    "agentExternalId": "agent-seo-1",
    "taskId": 42,
    "resultSummary": "SEO brief drafted"
  }
}
```

---

## 7. API and WebSocket Decisions for MVP

### Snapshot API

Backend must expose:

- `GET /api/rooms/{roomId}/world`

This becomes the authoritative hydration endpoint for the office simulation and should include:

- room metadata
- three locations
- current agents
- current tasks
- optionally recent events

### Command ingress

Preferred control-plane direction for MVP:

```text
UI -> backend -> OpenClaw runtime
OpenClaw runtime -> backend events/commands -> WebSocket -> UI
```

If AI-service remains the temporary OpenClaw bridge, the control-plane rule remains the same: backend stays the owner of accepted world mutations.

### New WebSocket topics

Add:

- `/topic/rooms/{roomId}/world`
- `/topic/rooms/{roomId}/agents`
- `/topic/rooms/{roomId}/events`

Keep existing topics for supporting views:

- `/topic/rooms/{roomId}`
- `/topic/rooms/{roomId}/chat`
- `/topic/rooms/{roomId}/tasks`
- `/topic/rooms/{roomId}/actions`
- `/topic/rooms/{roomId}/chat-stream`
- `/topic/rooms/{roomId}/container`

---

## 8. UX Contract for Observation

The office view must let the user understand at a glance:

- who exists
- where each agent is
- what each agent is doing now
- why they are doing it
- what changed recently

### Mandatory visible cues

- every agent has a visible state
- every non-trivial action has a visible bubble, icon, or inspector line
- movement is animated from backend-confirmed coordinates
- interaction targets are visible in the inspector
- raw tool traces stay in a workbench/log surface, not only in the main office scene

### Product rule

If the system only prints text such as "agent is researching" but the office view does not show a visible state transition or action cue, the feature is incomplete.

---

## 9. Phase 1 Deliverables

Phase 1 is complete when the following are frozen in docs and accepted as the implementation contract:

1. One office room contains three fixed locations.
2. Backend is the sole source of truth for world state.
3. OpenClaw sends structured commands, not UI mutations.
4. Frontend hydrates from `GET /api/rooms/{roomId}/world` and listens for simulation deltas.
5. MVP visible agent states are fixed.
6. Required event types are fixed.
7. Required command types are fixed.
8. Role-to-location defaults are fixed.

---

## 10. Immediate Phase 2 Handoff

Phase 2 should implement this contract in the backend first:

1. add location-aware world entities and migrations
2. add `world` snapshot DTO and endpoint
3. add simulation event persistence/broadcast
4. extend agent model with `locationId`, `statusText`, and task linkage
5. keep current room/chat/task flows working while introducing the new office world API
