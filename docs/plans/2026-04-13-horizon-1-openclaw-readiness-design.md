# Horizon 1 Closed Loop + Workbench Design

**Date:** 2026-04-13
**Status:** Proposed
**Scope Decision:** The `Files` tab is read-only in Horizon 1.

## Goal

Close the loop between agent execution and the UI so the next step can add OpenClaw on top of stable room, event, and workspace primitives instead of patching raw Redis and sandbox internals.

Horizon 1 should deliver three outcomes:

- agent replies published to `crab:chat-outbound` are persisted and broadcast back to the room timeline;
- agent movement events published to `crab:agent-events` animate agents in the office reliably;
- room sandbox files become visible in the UI through a read-only `Files` tab next to tasks.

## Current State

### Already present

- Java already has `ChatDispatcher`, which listens to `crab:chat-outbound` and forwards agent replies into `ChatService`.
- Java already has `AgentDispatcher`, which listens to `crab:agent-events` and broadcasts WebSocket events.
- Frontend already subscribes to room WebSocket channels in `useSocket.ts`.
- `PixelAgent.tsx` already animates `left/top` changes with Framer Motion.
- Python already has `file_tools.py` and a per-room sandbox rooted at `crab_sandbox/room_{id}`.

### Gaps that still block OpenClaw-ready flow

- The `AgentEvent` contract is inconsistent: Python currently models `position: { x, y }`, while Java DTOs and TypeScript expect top-level `x` and `y`.
- The Redis bridges are implemented but not locked by focused tests, so regressions are easy to reintroduce.
- The sandbox exists only inside Python; there is no frontend-facing API surface for listing or reading files.
- The room UI has `TaskPanel` but no `Files` workbench surface.
- File visibility is not tied to room workflows yet, so agents can create artifacts that remain invisible to the user.

## Approaches Considered

### Option 1: Direct frontend-to-Python file access

Expose sandbox endpoints from FastAPI and let Next.js call Python directly.

**Pros**
- Fastest path for the `Files` tab.
- Minimal Java work.

**Cons**
- Splits the frontend contract across Java and Python.
- Reintroduces CORS and origin drift across two backends.
- Makes later auth, auditing, and OpenClaw orchestration harder.

### Option 2: Java gateway + read-only workbench

Keep Java as the only frontend-facing API. Python owns sandbox storage and read APIs; Java proxies those APIs to the UI. The frontend adds a tabbed workbench with `Tasks` and `Files`.

**Pros**
- Preserves a single frontend backend boundary.
- Reuses existing Java orchestration role.
- Keeps Horizon 1 small while leaving room for OpenClaw session/workspace features later.

**Cons**
- Slightly more work now because both Python and Java need file endpoints.
- File updates are not realtime unless explicitly refreshed.

### Option 3: Full realtime workspace bus now

Add file change events, dedicated workspace channels, and richer workspace metadata before OpenClaw.

**Pros**
- Strong long-term architecture.
- Closest to a future multi-agent IDE runtime.

**Cons**
- Overbuild for Horizon 1.
- Delays visible product value and increases cross-stack risk.

## Recommendation

Choose **Option 2: Java gateway + read-only workbench**.

This keeps the existing architecture intact:

- Python remains the owner of agent execution and sandbox files.
- Java remains the single API and WebSocket façade for the frontend.
- Next.js gets a simple workbench surface without introducing a second backend dependency.

This is the smallest step that makes OpenClaw integration tractable, because OpenClaw can later plug into one stable room/workspace API boundary in Java instead of two independent surfaces.

## Target Architecture

### 1. Chat closed loop

`agent_brain.py` publishes agent replies to `crab:chat-outbound`.

Java path:

`Redis -> ChatDispatcher -> ChatService.saveAndBroadcastAgentMessage() -> PostgreSQL + /topic/rooms/{roomId}/chat`

Horizon 1 action:

- keep this flow as-is;
- add targeted tests so it cannot silently regress.

### 2. Agent movement closed loop

Python should publish a flat event payload:

```json
{
  "roomId": 4,
  "agentExternalId": "agent-dev-1",
  "eventType": "AGENT_STATE_CHANGED",
  "state": "walking",
  "x": 6,
  "y": 4,
  "message": "agent-dev-1 moved to desk",
  "timestamp": "2026-04-13T08:00:00Z"
}
```

Java path:

`Redis -> AgentDispatcher -> /topic/rooms/{roomId}`

Frontend path:

`useSocket -> RoomPage.handleEvent() -> Office -> PixelAgent`

Horizon 1 action:

- normalize Python to match Java and TypeScript;
- validate this contract with tests and a smoke check.

### 3. Sandbox workbench surface

Python will expose read-only sandbox endpoints:

- `GET /rooms/{roomId}/files`
- `GET /rooms/{roomId}/files/content?path=...`

Java will proxy them as room-scoped frontend APIs:

- `GET /api/rooms/{roomId}/files`
- `GET /api/rooms/{roomId}/files/content?path=...`

Frontend will replace the standalone task card with a small workbench panel:

- `Tasks` tab
- `Files` tab

`Files` behavior in Horizon 1:

- show file tree for the room sandbox;
- show selected file content in a read-only viewer;
- expose manual refresh;
- optionally auto-refresh after a file-related SYSTEM message.

## Deferred on purpose

These are explicitly out of Horizon 1:

- manual file upload/edit/delete from the browser;
- realtime file diff streaming;
- OpenClaw session state, approvals, or execution traces;
- multi-room workspace indexing;
- auth around sandbox access.

## Error Handling

- Invalid sandbox path returns `400` from Python and Java proxy.
- Missing file returns `404`.
- Empty sandbox returns an empty list and a calm empty-state UI, not an error.
- Bad `AgentEvent` payloads are logged once and ignored.
- File proxy failures should surface a room-local error message in the `Files` tab without breaking chat or office rendering.

## Testing Strategy

- Python: contract test for flat `AgentEvent` payload and unit tests for structured sandbox listing/reading helpers.
- Java: unit tests for `ChatDispatcher` and `AgentDispatcher`, plus MVC/service tests for file proxy endpoints.
- Frontend: `npm run lint`, `npm run build`, and a manual smoke of room creation, agent response, movement animation, and files visibility.

## OpenClaw Readiness Outcome

After Horizon 1, OpenClaw should be able to assume four stable room primitives:

- chat history arrives through Java and is persisted once;
- agent movement events arrive in a single event shape and animate in the UI;
- sandbox files are visible through room-scoped APIs;
- the room page already has a workbench surface where richer OpenClaw controls can land next.