# Room Routing, Fetch-on-Mount & Agent Prompts — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give every room a shareable URL (`/room/{id}`), auto-load room data when navigating to that URL, and rewrite agent prompts so they stop giving vague answers and start acting like employees who want to keep their jobs.

**Architecture:** Next.js App Router dynamic segment `src/app/room/[id]/page.tsx` replaces the current monolithic `page.tsx`. The landing page (`/`) keeps only room creation and redirects to `/room/{id}` after success. Agent prompts get a full rewrite with strict behavioral constraints.

**Tech Stack:** Next.js 16 App Router, TypeScript, Python (FastAPI agent prompts)

---

### Task 1: Create the dynamic room page

**Files:**
- Create: `frontend-nextjs/src/app/room/[id]/page.tsx`

**Step 1: Create the room page component**

This page reads the `id` from the URL params, fetches the room + messages + tasks on mount, and renders Office/Chat/Task panels.

```typescript
"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import Office from "@/components/Office";
import ChatPanel from "@/components/ChatPanel";
import TaskPanel from "@/components/TaskPanel";
import { useSocket } from "@/hooks/useSocket";
import type { Room, AgentEvent, Message, Task } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function RoomPage() {
  const params = useParams<{ id: string }>();
  const roomId = Number(params.id);

  const [room, setRoom] = useState<Room | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!roomId || isNaN(roomId)) {
      setError("Invalid room ID");
      setLoading(false);
      return;
    }

    let cancelled = false;

    async function load() {
      try {
        const roomRes = await fetch(`${API_URL}/api/rooms/${roomId}`);
        if (!roomRes.ok) throw new Error(`Room not found (${roomRes.status})`);
        const roomData: Room = await roomRes.json();
        if (cancelled) return;
        setRoom(roomData);

        const [msgRes, taskRes] = await Promise.all([
          fetch(`${API_URL}/api/rooms/${roomId}/messages`),
          fetch(`${API_URL}/api/rooms/${roomId}/tasks`),
        ]);

        if (!cancelled) {
          if (msgRes.ok) setMessages(await msgRes.json());
          if (taskRes.ok) setTasks(await taskRes.json());
        }
      } catch (e: unknown) {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load room");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, [roomId]);

  const handleEvent = useCallback((event: AgentEvent) => {
    setRoom((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        agents: prev.agents.map((a) =>
          a.externalId === event.agentExternalId
            ? { ...a, x: event.x, y: event.y, state: event.state }
            : a
        ),
      };
    });
  }, []);

  const handleMessage = useCallback((msg: Message) => {
    setMessages((prev) => [...prev, msg]);
  }, []);

  useSocket(room?.id ?? null, handleEvent, handleMessage);

  if (loading) {
    return (
      <main className="min-h-screen bg-gray-950 text-white flex items-center justify-center">
        <p className="text-gray-400 text-lg">Загрузка комнаты…</p>
      </main>
    );
  }

  if (error || !room) {
    return (
      <main className="min-h-screen bg-gray-950 text-white flex flex-col items-center justify-center gap-4">
        <p className="text-red-400 text-lg">{error || "Комната не найдена"}</p>
        <a href="/" className="text-orange-400 hover:underline">← На главную</a>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-gray-950 text-white flex flex-col items-center py-12 px-4 gap-8">
      <a href="/" className="text-sm text-gray-500 hover:text-gray-300 self-start">← Все комнаты</a>
      <Office room={room} />
      <ChatPanel roomId={room.id} messages={messages} />
      <TaskPanel tasks={tasks} />
    </main>
  );
}
```

**Step 2: Verify file exists**

Run: `Test-Path "c:\Users\Kukyo\crab-office\frontend-nextjs\src\app\room\[id]\page.tsx"`
Expected: `True`

---

### Task 2: Refactor the landing page to redirect after room creation

**Files:**
- Modify: `frontend-nextjs/src/app/page.tsx`

**Step 1: Rewrite page.tsx**

Strip out Office/Chat/Task rendering. After room creation, use `router.push` to navigate to the new room URL. Import `useRouter` from `next/navigation`.

Replace the full file content with:

```typescript
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { Room } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const PRESETS = ["tech", "cozy", "creative"] as const;

export default function Home() {
  const router = useRouter();
  const [prompt, setPrompt] = useState("");
  const [preset, setPreset] = useState<string>("tech");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const createRoom = async () => {
    if (!prompt.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`${API_URL}/api/rooms`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt, preset }),
      });
      if (!res.ok) throw new Error(`Server error: ${res.status}`);
      const data: Room = await res.json();
      router.push(`/room/${data.id}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen bg-gray-950 text-white flex flex-col items-center justify-center py-12 px-4 gap-8">
      <h1 className="text-4xl font-bold tracking-tight">🦀 Crab Office</h1>
      <p className="text-gray-400 max-w-md text-center">
        Опишите, какой офис вы хотите построить, и Краб-Архитектор создаст его для вас.
      </p>

      <div className="flex flex-col gap-3 w-full max-w-lg">
        <input
          className="rounded-lg bg-gray-800 border border-gray-700 px-4 py-3 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-orange-500"
          placeholder="Что построить? Например: уютный офис для разработчиков..."
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && createRoom()}
        />

        <div className="flex gap-2">
          {PRESETS.map((p) => (
            <button
              key={p}
              onClick={() => setPreset(p)}
              className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
                preset === p
                  ? "bg-orange-600 text-white"
                  : "bg-gray-800 text-gray-300 hover:bg-gray-700"
              }`}
            >
              {p}
            </button>
          ))}
        </div>

        <button
          onClick={createRoom}
          disabled={loading || !prompt.trim()}
          className="bg-orange-600 hover:bg-orange-500 disabled:opacity-40 text-white font-medium py-3 rounded-lg transition"
        >
          {loading ? "Строим..." : "Построить офис 🦀"}
        </button>
      </div>

      {error && <p className="text-red-400 text-sm">{error}</p>}
    </main>
  );
}
```

---

### Task 3: Rewrite agent prompts to make them actually work

**Files:**
- Modify: `ai-service-python/agent_prompts.py`

**Step 1: Replace entire file**

The current prompts are soft suggestions. New prompts enforce behavior with strict rules, output format constraints, and a "boss energy" tone so the crabs stop being philosophical and start doing their jobs.

```python
"""Per-role system prompt builder for agent brain.

The crabs are typical startapers: one wants to cook, another wants to tinker,
but nobody wants to write a weekly plan. Time to be a strict boss.
"""

from __future__ import annotations

ROLE_PROMPTS: dict[str, str] = {
    "architect": (
        "You are the Crab Architect — the chief technical brain.\n"
        "YOUR DUTIES:\n"
        "- Propose concrete architecture: name services, pick protocols, draw data flows.\n"
        "- When asked 'how to build X', answer with a numbered build plan (max 5 steps).\n"
        "- If information is missing, list exactly what you need before you can proceed.\n"
        "FORBIDDEN:\n"
        "- Saying 'it depends' without listing the exact options.\n"
        "- Generic advice like 'consider scalability'. Specify: HOW and WHERE.\n"
        "- Deferring to the user ('what do you think?'). You are the architect, DECIDE.\n"
        "OUTPUT FORMAT: structured text with numbered steps or bullet points. No prose."
    ),
    "developer": (
        "You are the Crab Developer — the hands-on engineer.\n"
        "YOUR DUTIES:\n"
        "- Write real code snippets (not pseudocode), specify language and file path.\n"
        "- When debugging, state: hypothesis → check → fix. Always all three.\n"
        "- Estimate complexity: 'This is ~20 lines in file X, takes about Y'.\n"
        "FORBIDDEN:\n"
        "- Saying 'you could try…' — state what YOU will do.\n"
        "- Incomplete snippets without imports or context.\n"
        "- Ignoring error handling — always include the unhappy path.\n"
        "OUTPUT FORMAT: language-tagged code blocks. Brief explanation above each block."
    ),
    "analyst": (
        "You are the Crab Analyst — the data and requirements person.\n"
        "YOUR DUTIES:\n"
        "- Break user requests into acceptance criteria (Given/When/Then or checklist).\n"
        "- Identify edge cases and risks; rank them by severity (critical/major/minor).\n"
        "- When asked to review, provide a structured table: Issue | Severity | Suggestion.\n"
        "FORBIDDEN:\n"
        "- Vague warnings ('be careful with…'). Specify the exact failure scenario.\n"
        "- Repeating the user's question back to them. Analyze, don't echo.\n"
        "- Long paragraphs. Use tables, lists, checklists.\n"
        "OUTPUT FORMAT: bullet lists, checklists, or markdown tables. Never walls of text."
    ),
    "manager": (
        "You are the Crab Manager — the coordinator and decision-maker.\n"
        "YOUR DUTIES:\n"
        "- After any discussion, produce a summary: decisions made, action items, owners.\n"
        "- When the team is stuck, make a call and state your reasoning in 2 sentences.\n"
        "- Track scope: if a request grows beyond the original ask, flag it explicitly.\n"
        "FORBIDDEN:\n"
        "- Saying 'great question!' or any filler. Get to the point.\n"
        "- Ending messages with open-ended questions unless assigning a specific task.\n"
        "- Agreeing with everyone. If two approaches conflict, pick one and defend it.\n"
        "OUTPUT FORMAT: action items as checkboxes (- [ ] task). Summaries as numbered lists."
    ),
}

BASE_CONTEXT = (
    "You are an AI worker in Crab Office — a virtual pixel-art office.\n"
    "You are employed here. This is your job. Act like a professional.\n\n"
    "RULES:\n"
    "1. Respond in the SAME language the user writes in.\n"
    "2. Be concise: 1-4 sentences for simple questions, structured output for complex ones.\n"
    "3. Never refuse a task that falls within your role. If it's outside your role, "
    "say so in ONE sentence and suggest which colleague should handle it.\n"
    "4. No corporate fluff. No 'I hope this helps'. No emoji in technical content.\n"
    "5. If you need clarification, ask ONE specific question, not three.\n"
    "6. When given a direct command, your FIRST line must be the action or result, "
    "not an acknowledgment like 'Sure!' or 'Of course!'.\n"
)


def build_system_prompt(role: str, agent_name: str, room_name: str) -> str:
    """Build a full system prompt for an agent."""
    role_part = ROLE_PROMPTS.get(role, f"You are a {role} in the team. Follow the rules above.")
    return (
        f"{BASE_CONTEXT}\n"
        f"Your name: {agent_name}\n"
        f"Your role: {role}\n"
        f"Room: {room_name}\n\n"
        f"{role_part}\n\n"
        "Respond ONLY with your message text. Do not prefix with your name or role."
    )
```

---

### Task 4: TypeScript type-check & commit

**Step 1: Run TypeScript check**

Run: `cd c:\Users\Kukyo\crab-office\frontend-nextjs ; npx tsc --noEmit`
Expected: no errors

**Step 2: Run Python syntax check**

Run: `& c:\Users\Kukyo\crab-office\ai-service-python\.venv\Scripts\python.exe -c "import ast; ast.parse(open(r'c:\Users\Kukyo\crab-office\ai-service-python\agent_prompts.py').read()); print('OK')"`
Expected: `OK`

**Step 3: Commit all changes**

```bash
git -C c:\Users\Kukyo\crab-office add .
git -C c:\Users\Kukyo\crab-office commit -m "feat: room URL routing, fetch-on-mount, strict agent prompts"
```
