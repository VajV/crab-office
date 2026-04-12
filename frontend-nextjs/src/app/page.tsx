"use client";

import { useState, useCallback } from "react";
import Office from "@/components/Office";
import ChatPanel from "@/components/ChatPanel";
import TaskPanel from "@/components/TaskPanel";
import { useSocket } from "@/hooks/useSocket";
import type { Room, AgentEvent, Message, Task } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

const PRESETS = ["tech", "cozy", "creative"] as const;

export default function Home() {
  const [prompt, setPrompt] = useState("");
  const [preset, setPreset] = useState<string>("tech");
  const [room, setRoom] = useState<Room | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);

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
      setRoom(data);
      // Fetch existing messages
      setMessages([]);
      const msgRes = await fetch(`${API_URL}/api/rooms/${data.id}/messages`);
      if (msgRes.ok) {
        const msgs: Message[] = await msgRes.json();
        setMessages(msgs);
      }
      // Fetch tasks
      setTasks([]);
      const taskRes = await fetch(`${API_URL}/api/rooms/${data.id}/tasks`);
      if (taskRes.ok) {
        const t: Task[] = await taskRes.json();
        setTasks(t);
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen bg-gray-950 text-white flex flex-col items-center py-12 px-4 gap-8">
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

      {room && <Office room={room} />}

      {room && <ChatPanel roomId={room.id} messages={messages} />}

      {room && <TaskPanel tasks={tasks} />}
    </main>
  );
}
