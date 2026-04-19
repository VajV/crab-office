# OpenClaw Visual Office Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a visual AI office where OpenClaw runs in Docker as the real agent runtime, can use the internet and approved tools, and drives pixel agents across three fixed locations while the UI shows their behavior in real time.

**Architecture:** Keep Java/Spring Boot as the frontend-facing source of truth for office world state. Model one office room with three fixed locations, persistent agents, tasks, movements, and interaction history. Run OpenClaw in Docker with controlled network and tool access. OpenClaw emits structured commands and runtime activity, the backend validates and persists state transitions, and the Next.js frontend renders a live pixel office from backend snapshots plus WebSocket deltas.

**Tech Stack:** Java 21 / Spring Boot 4 / PostgreSQL / Redis / Python 3.12 / FastAPI / OpenClaw in Docker / Next.js 16 / STOMP / Framer Motion

---

## Target Architecture

### 1. OpenClaw Runtime in Docker

- Run OpenClaw as an isolated containerized runtime with outbound internet access and sandboxed file/tool capabilities.
- Restrict runtime behavior through explicit capabilities such as `web.read`, `browser.navigate`, `file.read`, `file.write`, and approved command execution.
- Emit structured commands and runtime telemetry instead of direct UI mutations.
- Preserve runtime observability with `runId`, `correlationId`, tool logs, and state-change events.

### 2. Backend as World-State Authority

- Store the office room, three fixed locations, agents, tasks, movements, interactions, and event history in Spring Boot/PostgreSQL.
- Validate all state transitions before mutating the world.
- Persist simulation events and broadcast normalized payloads over WebSocket.
- Expose a world snapshot API so the UI hydrates from authoritative state rather than reconstructing from chat.

### 3. Frontend as Pixel Office Renderer

- Render three fixed office locations with pixel backgrounds and walkable coordinates.
- Show agents as pixel entities with position, state, current activity, and interaction context.
- Animate movement and state changes from backend-confirmed events.
- Provide an inspector/workbench view for current task, recent actions, and activity history.

### 4. Event-Driven Realtime Flow

- OpenClaw emits commands and runtime activity.
- Python AI-service can remain the initial bridge for OpenClaw HTTP/WebSocket traffic while the runtime protocol stabilizes.
- Backend persists and rebroadcasts normalized office events to STOMP topics.
- Frontend consumes a world snapshot first and then applies live deltas.

---

## Minimal Domain Model

### `Location`

- `id`
- `roomId`
- `key`
- `name`
- `kind`
- `width`
- `height`
- `backgroundPreset`
- `spawnPoints`
- `interactionPoints`
- `sortOrder`

Initial fixed locations:

- `marketing-room`
- `engineering-room`
- `ops-room`

### `Agent`

- `id`
- `roomId`
- `externalId`
- `name`
- `role`
- `spriteKey`
- `locationId`
- `x`
- `y`
- `state`
- `statusText`
- `currentTaskId`
- `targetAgentId`
- `createdAt`
- `updatedAt`

### `AgentState`

Canonical visible states for MVP:

- `idle`
- `thinking`
- `walking`
- `talking`
- `working`
- `waiting`

### `Task`

- `id`
- `roomId`
- `title`
- `description`
- `status`
- `assignedAgentId`
- `requestedBy`
- `source`
- `locationHint`
- `createdAt`
- `updatedAt`

### `AgentEvent`

- `id`
- `roomId`
- `locationId`
- `agentId`
- `eventType`
- `state`
- `payloadJson`
- `correlationId`
- `runId`
- `timestamp`

### `AgentMovement`

- `id`
- `agentId`
- `fromLocationId`
- `toLocationId`
- `fromX`
- `fromY`
- `toX`
- `toY`
- `reason`
- `startedAt`
- `completedAt`

### `AgentInteraction`

- `id`
- `roomId`
- `initiatorAgentId`
- `targetAgentId`
- `interactionType`
- `status`
- `taskId`
- `summary`
- `startedAt`
- `endedAt`

---

## Event Contract

Use a normalized envelope for all office simulation events.

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

### Required event types

#### `agent.spawned`

```json
{
  "eventType": "agent.spawned",
  "payload": {
    "agent": {
      "externalId": "agent-seo-1",
      "name": "SEO-1",
      "role": "seo",
      "spriteKey": "seo-blue",
      "locationId": "marketing-room",
      "x": 3,
      "y": 5,
      "state": "idle"
    }
  }
}
```

#### `agent.state_changed`

```json
{
  "eventType": "agent.state_changed",
  "payload": {
    "fromState": "idle",
    "toState": "thinking",
    "statusText": "Analyzing search intent"
  }
}
```

#### `agent.moved`

```json
{
  "eventType": "agent.moved",
  "payload": {
    "from": { "locationId": "marketing-room", "x": 3, "y": 5 },
    "to": { "locationId": "marketing-room", "x": 7, "y": 5 },
    "reason": "Going to copywriter"
  }
}
```

#### `agent.task_assigned`

```json
{
  "eventType": "agent.task_assigned",
  "payload": {
    "taskId": 42,
    "title": "Prepare SEO brief",
    "description": "Draft keyword plan for landing page"
  }
}
```

#### `agent.interaction_started`

```json
{
  "eventType": "agent.interaction_started",
  "payload": {
    "targetAgentExternalId": "agent-copy-1",
    "interactionType": "discussion",
    "summary": "Discussing landing page keywords"
  }
}
```

#### `agent.interaction_finished`

```json
{
  "eventType": "agent.interaction_finished",
  "payload": {
    "targetAgentExternalId": "agent-copy-1",
    "interactionType": "discussion",
    "summary": "Keyword alignment completed"
  }
}
```

#### `web_research_started`

```json
{
  "eventType": "web_research_started",
  "payload": {
    "query": "best SEO structure for B2B landing page",
    "tool": "browser.navigate",
    "statusText": "Researching competitors"
  }
}
```

#### `web_research_finished`

```json
{
  "eventType": "web_research_finished",
  "payload": {
    "query": "best SEO structure for B2B landing page",
    "sourcesVisited": 5,
    "summary": "Collected structure patterns and target keywords"
  }
}
```

#### `task.completed`

```json
{
  "eventType": "task.completed",
  "payload": {
    "taskId": 42,
    "resultSummary": "SEO brief created",
    "outputRef": "artifact://task/42/brief.md"
  }
}
```

Recommendation for implementation:

- Keep the current low-level `AgentActionDto` stream for raw tool telemetry.
- Add a separate user-facing simulation event stream for office behavior.
- Not every tool call should become a top-level visible office event.

---

## API and WebSocket Surface

### REST APIs

#### `GET /api/rooms/{roomId}/world`

Return the full office snapshot:

- room metadata
- all three locations
- all current agents
- current tasks
- recent simulation events

#### `POST /api/rooms/{roomId}/agents`

```json
{
  "name": "SEO-1",
  "role": "seo",
  "locationId": "marketing-room"
}
```

#### `POST /api/rooms/{roomId}/agents/{agentId}/state`

```json
{
  "state": "thinking",
  "statusText": "Analyzing SERP"
}
```

#### `POST /api/rooms/{roomId}/agents/{agentId}/move`

```json
{
  "locationId": "marketing-room",
  "x": 7,
  "y": 5,
  "reason": "Going to analyst desk"
}
```

#### `POST /api/rooms/{roomId}/agents/{agentId}/interactions`

```json
{
  "targetAgentId": "agent-copy-1",
  "interactionType": "discussion",
  "summary": "Discussing headline direction"
}
```

#### `POST /api/rooms/{roomId}/openclaw/commands`

```json
{
  "commandType": "spawn_agent",
  "payload": {
    "role": "seo"
  }
}
```

Use this as the control-plane ingress if backend remains the gatekeeper for runtime requests.

### WebSocket Topics

Add simulation-specific topics:

- `/topic/rooms/{roomId}/world`
- `/topic/rooms/{roomId}/agents`
- `/topic/rooms/{roomId}/events`

Keep current topics for supporting surfaces:

- `/topic/rooms/{roomId}`
- `/topic/rooms/{roomId}/chat`
- `/topic/rooms/{roomId}/tasks`
- `/topic/rooms/{roomId}/actions`
- `/topic/rooms/{roomId}/chat-stream`
- `/topic/rooms/{roomId}/container`

Recommendation:

- `world` or `agents` should carry authoritative simulation updates.
- `actions` should remain low-level telemetry for tool usage and workbench debugging.

---

## OpenClaw Command Protocol

OpenClaw should emit structured runtime commands, while backend validates and persists the resulting state transitions.

### `spawn_agent`

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

Expected backend result:

- create the agent
- assign the location
- emit `agent.spawned`
- emit `agent.state_changed` to `idle` if needed

### `set_agent_state`

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

### `move_agent`

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

### `start_web_research`

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

### `finish_web_research`

```json
{
  "commandType": "finish_web_research",
  "payload": {
    "agentExternalId": "agent-seo-1",
    "query": "best landing page SEO structure",
    "sourcesVisited": 5,
    "summary": "Collected SERP patterns"
  }
}
```

### `complete_task`

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

Implementation rule:

- OpenClaw should send commands.
- Backend should emit office events after validation and persistence.
- Frontend should render backend-confirmed outcomes only.

---

## Docker and Runtime Requirements

### Network

- Outbound internet access from the OpenClaw container.
- Configurable allow or deny policy for external domains.
- DNS resolution and connection timeout policy.
- No unnecessary inbound exposure.

### Sandbox

- Dedicated workspace mount per room or per run.
- Restricted visible filesystem scope.
- Separate artifact output directory for task results.
- Cleanup strategy for stale runs.

### Browser and Web Access

- Lightweight HTTP fetch support.
- Headless browser support for richer web tasks.
- Explicit tool wrappers rather than unbounded shell browsing.
- Logged page visits and tool invocations.

### Restrictions and Safety

- Explicit per-run capability grants.
- Command execution allowlist if shell access exists.
- Blocked filesystem areas.
- Tool duration limits and network timeouts.
- Sanitized and truncated tool output for UI-safe display.

### Observability

- `runId` and `correlationId` carried through runtime, backend, and UI.
- Structured logs for command start, command finish, tool invocation, and error handling.
- Metrics for runtime health, command latency, task completion, and browser failures.

---

## UX Priorities

The office view must answer these questions at a glance:

- who exists
- where they are
- what they are doing
- why they are doing it
- what just happened

MVP UX requirements:

- every agent always has a visible current state
- non-trivial work creates a visible icon or bubble
- movement is animated enough to imply intention
- the inspector explains task, target, and recent activity
- raw tool traces stay available in a workbench/log panel, but the office view shows a simplified behavioral summary

---

## Phased Plan

**Phase 1 Contract Source:** `docs/plans/2026-04-17-openclaw-visual-office-phase-1-contract.md`

### Phase 1: Freeze the Office Simulation Contract

**Goal:** Define the world model, event vocabulary, and ownership rules before adding more runtime behavior.

**Deliverables:**

- documented `Location`, `Agent`, `Task`, `AgentEvent`, `Movement`, and `Interaction` schemas
- fixed list of three locations and their placement rules
- canonical visible state machine for agents
- normalized event envelope and required event types
- decision on control flow between UI, backend, AI-service, and OpenClaw

**Modules:**

- `docs/plans/`
- `backend-java`
- `frontend-nextjs`
- `ai-service-python`
- `openclaw` runtime/config docs

**Risks:**

- backend and OpenClaw may both try to own state changes
- event names may drift if not fixed up front
- current single-room model may blur with the new three-location office model

**Done Criteria:**

- payload shapes are fixed
- simulation state ownership is explicit
- implementation can begin without inventing new contracts mid-stream

---

### Phase 2: Extend the Backend into a World-State Engine

**Goal:** Make Spring Boot the authoritative owner of office locations, positions, tasks, interactions, and event history.

**Deliverables:**

- new entities and Flyway migrations for locations, interactions, and event history
- richer agent model including `locationId`, `statusText`, and active task linkage
- world snapshot API for room hydration
- commands/endpoints for agent spawn, movement, state update, and interaction lifecycle
- simulation event dispatchers over STOMP/WebSocket

**Modules:**

- `backend-java/src/main/java/com/craboffice/backend/entity`
- `repository`
- `service`
- `controller`
- `src/main/resources/db`

**Risks:**

- event persistence and broadcast ordering can diverge
- too much logic may remain embedded in chat-oriented services
- existing room payloads may need compatibility handling

**Done Criteria:**

- backend can serve an office snapshot with three locations and active agents
- backend can validate and persist movement, interaction, and task-related state
- frontend no longer has to infer office behavior from chat alone

---

### Phase 3: Add the OpenClaw Runtime Protocol

**Goal:** Turn OpenClaw from a text-only backend dependency into a structured runtime that drives visible office behavior.

**Deliverables:**

- command protocol for spawn/state/move/interact/research/complete flows
- runtime event payloads carrying `runId`, `correlationId`, and metadata
- translation layer from user intent such as `создай SEO` into backend-validated commands
- bridge rules for AI-service if Python remains the initial runtime adapter

**Modules:**

- `openclaw`
- `ai-service-python`
- `backend-java`

**Risks:**

- free-form runtime text may remain mixed with structured protocol data
- command semantics may be too coupled to current proxy behavior
- runtime may emit noisy low-level activity unsuited for direct UI display

**Done Criteria:**

- one user command can create a visible agent end-to-end
- OpenClaw can report stateful work without the UI guessing from prose
- backend and runtime share a stable command vocabulary

---

### Phase 4: Dockerize OpenClaw with Controlled Capabilities

**Goal:** Run OpenClaw as a real isolated runtime with internet and tool access under explicit restrictions.

**Deliverables:**

- dedicated OpenClaw image and compose wiring
- outbound network policy and runtime health checks
- sandbox workspace mount and artifact path strategy
- optional headless browser support
- capability model and audit logging for tool execution

**Modules:**

- Docker/compose files
- `openclaw`
- `backend-java` orchestration hooks
- `ai-service-python` bridge if still used for runtime ingress

**Risks:**

- unrestricted network/tool access creates security problems
- browser automation increases runtime complexity and latency
- container restart behavior can lose context if not externalized

**Done Criteria:**

- OpenClaw runs reproducibly in Docker
- it can perform a basic research task with logged, bounded tool access
- backend can correlate runtime activity with office events

---

### Phase 5: Build the Realtime Simulation Pipeline

**Goal:** Connect OpenClaw activity to backend-normalized events and frontend animation.

**Deliverables:**

- backend ingestion path for simulation commands and runtime activity
- normalized event dispatch over dedicated WebSocket topics
- world snapshot plus incremental update strategy
- ordering and deduplication rules for deltas
- reconnect-safe frontend state handling

**Modules:**

- `ai-service-python/main.py`
- Redis/pub-sub bridges
- `backend-java` dispatchers and services
- `frontend-nextjs/src/hooks/useSocket.ts`

**Risks:**

- duplicate events across chat, actions, and office streams
- stale UI positions after reconnect
- too many granular updates can flood the browser

**Done Criteria:**

- agents spawn, move, think, research, and finish tasks live in the UI
- frontend state follows backend-confirmed deltas
- reconnect does not corrupt office state

---

### Phase 6: Expand the Frontend into a Three-Location Pixel Office

**Goal:** Convert the current room view into a multi-location pixel office where agent work is understandable visually.

**Deliverables:**

- rendering model for three fixed locations
- per-location backgrounds and walkable coordinates
- agent sprites with state-driven visuals and movement animation
- location navigation or layout strategy
- inspector panel for role, state, task, target, and recent events
- summarized office cues for thinking, walking, interaction, and web research

**Modules:**

- `frontend-nextjs/src/app/room/[id]/page.tsx`
- `frontend-nextjs/src/components/Office.tsx`
- `frontend-nextjs/src/components/PixelAgent.tsx`
- `frontend-nextjs/src/types.ts`

**Risks:**

- a single-scene UI may not scale cleanly to three locations
- too much telemetry in the main office view reduces clarity
- movement animation can feel fake if event cadence is inconsistent

**Done Criteria:**

- the user can watch three office locations and understand what each agent is doing
- a new role-based agent appears in the right location after creation
- movement and interaction are visible enough to feel alive in MVP

---

### Phase 7: Add Task-Driven Agent Orchestration

**Goal:** Tie user requests and runtime behavior into persistent, observable agent work.

**Deliverables:**

- user flow for `создай SEO` and similar role-based requests
- role-aware default placement and sprite assignment
- task assignment flow linked to visible state transitions
- agent-to-agent collaboration events and inspector history
- output artifact references for completed work

**Modules:**

- `backend-java`
- `openclaw`
- `ai-service-python`
- `frontend-nextjs`

**Risks:**

- task state and visual state may drift apart
- role routing may feel arbitrary without explicit defaults
- user trust drops if agents appear but do not do purposeful work

**Done Criteria:**

- a user can create a role-specific agent, assign work, and observe it complete a visible flow
- task panel and office scene remain consistent

---

### Phase 8: Add Observability, Safety, and Failure UX

**Goal:** Make the office runtime understandable and safe once OpenClaw has real internet and tool access.

**Deliverables:**

- end-to-end `correlationId` propagation
- structured logs for command lifecycle and tool execution
- agent audit timeline in the UI or workbench
- timeout and failure policies for runtime and tools
- visible fallback states such as blocked, failed research, or runtime unavailable

**Modules:**

- `openclaw`
- `ai-service-python`
- `backend-java`
- `frontend-nextjs`

**Risks:**

- failures may look like silent inactivity without explicit state handling
- unrestricted telemetry can overwhelm the UI
- lack of audit data makes runtime errors impossible to explain

**Done Criteria:**

- every visible action is traceable to runtime and backend events
- errors surface clearly in logs and UI
- runtime permissions are explicit rather than implied

---

## First Tasks for the Next Sprint

1. Freeze the simulation schema for locations, agents, movements, interactions, and events.
2. Define the three fixed locations and role-to-location placement defaults.
3. Add a dedicated office event envelope and the first required event types.
4. Design and implement `GET /api/rooms/{roomId}/world` in backend.
5. Decide whether backend or AI-service is the primary ingress for OpenClaw runtime commands.
6. Define the OpenClaw command contract for spawn, state, move, research, and task completion.
7. Split low-level `AgentAction` telemetry from user-facing office simulation events.
8. Expand frontend types and state handling from single-room layout to three fixed locations.
9. Specify Docker runtime policy for OpenClaw: network, browser support, sandbox, logging, and timeouts.
10. Build the first vertical demo scenario: `создай SEO` -> agent appears -> thinks -> researches -> moves -> interacts -> completes task.

---

## Open Questions and Assumptions

1. Keep `room` as the top-level office container and model the three fixed locations inside it for MVP.
2. Prefer backend as the control plane, with OpenClaw acting as runtime executor rather than direct world-state owner.
3. Keep Python AI-service as the initial OpenClaw bridge if that reduces integration cost, but do not let it become the source of truth for office state.
4. Favor command-driven visible behavior before adding long-running autonomous background behavior.
5. Start with simple coordinate or waypoint movement instead of full pathfinding.
6. Show summarized research and tool activity in the office view, and keep raw traces in the workbench.
7. Treat created agents as persistent within the room unless later product direction requires ephemeral session-only workers.
8. Allow outbound internet access from day one only with capability flags, logging, and strict timeout controls.
