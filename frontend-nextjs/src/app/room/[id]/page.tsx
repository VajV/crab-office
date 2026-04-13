"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import Office from "@/components/Office";
import ChatPanel from "@/components/ChatPanel";
import WorkbenchPanel from "@/components/WorkbenchPanel";
import { useSocket } from "@/hooks/useSocket";
import type { Room, AgentEvent, Message, Task, SandboxFileEntry, SandboxFileListResponse, SandboxFileContentResponse, ContainerEvent } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function RoomPage() {
  const params = useParams<{ id: string }>();
  const roomId = Number(params.id);

  const [room, setRoom] = useState<Room | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [files, setFiles] = useState<SandboxFileEntry[]>([]);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState("");
  const [filesLoading, setFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [containerLogs, setContainerLogs] = useState<ContainerEvent[]>([]);

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

        const [msgRes, taskRes, containerRes] = await Promise.all([
          fetch(`${API_URL}/api/rooms/${roomId}/messages`),
          fetch(`${API_URL}/api/rooms/${roomId}/tasks`),
          fetch(`${API_URL}/api/rooms/${roomId}/container/logs`),
        ]);

        if (!cancelled) {
          if (msgRes.ok) setMessages(await msgRes.json());
          if (taskRes.ok) setTasks(await taskRes.json());
          if (containerRes.ok) setContainerLogs(await containerRes.json());
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

  const loadFiles = useCallback(async () => {
    if (!roomId || isNaN(roomId)) return;
    setFilesLoading(true);
    setFilesError(null);
    try {
      const res = await fetch(`${API_URL}/api/rooms/${roomId}/files`);
      if (!res.ok) throw new Error(`Files unavailable (${res.status})`);
      const data: SandboxFileListResponse = await res.json();
      setFiles(data.files);
    } catch (e) {
      setFilesError(e instanceof Error ? e.message : "Failed to load files");
    } finally {
      setFilesLoading(false);
    }
  }, [roomId]);

  const handleSelectFile = useCallback(async (path: string) => {
    setSelectedPath(path);
    setFilesLoading(true);
    try {
      const res = await fetch(`${API_URL}/api/rooms/${roomId}/files/content?path=${encodeURIComponent(path)}`);
      if (!res.ok) throw new Error(`Cannot read file (${res.status})`);
      const data: SandboxFileContentResponse = await res.json();
      setFileContent(data.content);
    } catch {
      setFileContent("Ошибка загрузки файла");
    } finally {
      setFilesLoading(false);
    }
  }, [roomId]);

  useEffect(() => {
    if (roomId && !isNaN(roomId)) loadFiles();
  }, [roomId, loadFiles]);

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
    if (msg.senderType === "SYSTEM" && msg.content.startsWith("🔧")) {
      loadFiles();
    }
  }, [loadFiles]);

  const handleContainerEvent = useCallback((event: ContainerEvent) => {
    setContainerLogs((prev) => [...prev, event]);
  }, []);

  useSocket(room?.id ?? null, handleEvent, handleMessage, handleContainerEvent);

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
      <WorkbenchPanel
        tasks={tasks}
        files={files}
        selectedPath={selectedPath}
        fileContent={fileContent}
        filesLoading={filesLoading}
        filesError={filesError}
        containerLogs={containerLogs}
        onSelectFile={handleSelectFile}
        onRefreshFiles={loadFiles}
      />
    </main>
  );
}
