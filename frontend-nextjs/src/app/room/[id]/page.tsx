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
