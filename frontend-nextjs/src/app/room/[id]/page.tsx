"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import WorldOffice from "@/components/WorldOffice";
import ChatPanel from "@/components/ChatPanel";
import WorkbenchPanel from "@/components/WorkbenchPanel";
import { useSocket } from "@/hooks/useSocket";
import type { Agent, AgentEvent, Message, SandboxFileEntry, SandboxFileListResponse, SandboxFileContentResponse, ContainerEvent, AgentAction, ChatStreamChunk, SimulationEvent, World } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function RoomPage() {
  const params = useParams<{ id: string }>();
  const roomId = Number(params.id);

  const [world, setWorld] = useState<World | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [files, setFiles] = useState<SandboxFileEntry[]>([]);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState("");
  const [filesLoading, setFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [containerLogs, setContainerLogs] = useState<ContainerEvent[]>([]);
  const [agentActions, setAgentActions] = useState<AgentAction[]>([]);
  const [streamingText, setStreamingText] = useState<string>("");

  const upsertAgent = useCallback((agents: Agent[], nextAgent: Agent) => {
    const existing = agents.find((agent) => agent.externalId === nextAgent.externalId);
    if (!existing) {
      return [...agents, nextAgent];
    }
    return agents.map((agent) => agent.externalId === nextAgent.externalId ? { ...agent, ...nextAgent } : agent);
  }, []);

  useEffect(() => {
    if (!roomId || isNaN(roomId)) {
      setError("Invalid room ID");
      setLoading(false);
      return;
    }

    let cancelled = false;

    async function load() {
      try {
        const worldRes = await fetch(`${API_URL}/api/rooms/${roomId}/world`);
        if (!worldRes.ok) throw new Error(`Room not found (${worldRes.status})`);
        const worldData: World = await worldRes.json();
        if (cancelled) return;
        setWorld(worldData);

        const [msgRes, containerRes, actionsRes] = await Promise.all([
          fetch(`${API_URL}/api/rooms/${roomId}/messages`),
          fetch(`${API_URL}/api/rooms/${roomId}/container/logs`),
          fetch(`${API_URL}/api/rooms/${roomId}/actions`),
        ]);

        if (!cancelled) {
          if (msgRes.ok) setMessages(await msgRes.json());
          if (containerRes.ok) setContainerLogs(await containerRes.json());
          if (actionsRes.ok) setAgentActions(await actionsRes.json());
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
    setWorld((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        agents: prev.agents.map((a) =>
            a.externalId === event.agentExternalId
            ? { ...a, x: event.x, y: event.y, state: event.state, statusText: event.message || a.statusText }
            : a
        ),
      };
    });
  }, []);

  const handleWorld = useCallback((nextWorld: World) => {
    setWorld(nextWorld);
  }, []);

  const handleSimulationEvent = useCallback((event: SimulationEvent) => {
    setWorld((prev) => {
      if (!prev) return prev;

      const recentEvents = [event, ...prev.recentEvents.filter((item) => item.id !== event.id)].slice(0, 30);

      if (event.eventType === "agent.spawned") {
        const agentPayload = event.payload.agent;
        if (!agentPayload || typeof agentPayload !== "object") {
          return { ...prev, recentEvents };
        }
        const payload = agentPayload as Record<string, unknown>;
        return {
          ...prev,
          recentEvents,
          agents: upsertAgent(prev.agents, {
            externalId: String(payload.externalId ?? "unknown-agent"),
            name: String(payload.name ?? "Agent"),
            role: String(payload.role ?? "worker"),
            spriteKey: payload.spriteKey ? String(payload.spriteKey) : undefined,
            locationId: payload.locationId ? String(payload.locationId) : undefined,
            x: Number(payload.x ?? 0),
            y: Number(payload.y ?? 0),
            state: String(payload.state ?? "idle"),
            statusText: payload.statusText ? String(payload.statusText) : "Waiting for assignment",
          }),
        };
      }

      if (event.eventType === "agent.state_changed") {
        const payload = event.payload as Record<string, unknown>;
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  state: String(payload.toState ?? event.state ?? agent.state),
                  statusText: payload.statusText ? String(payload.statusText) : agent.statusText,
                }
              : agent,
          ),
        };
      }

      if (event.eventType === "agent.moved") {
        const payload = event.payload as Record<string, unknown>;
        const to = (payload.to ?? {}) as Record<string, unknown>;
        const targetAgentExternalId = payload.targetAgentExternalId ? String(payload.targetAgentExternalId) : null;
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  locationId: to.locationId ? String(to.locationId) : agent.locationId,
                  x: Number(to.x ?? agent.x),
                  y: Number(to.y ?? agent.y),
                  state: String(event.state ?? "walking"),
                  statusText: payload.reason ? String(payload.reason) : agent.statusText,
                  targetAgentExternalId,
                }
              : agent,
          ),
        };
      }

      if (event.eventType === "agent.task_assigned") {
        const payload = event.payload as Record<string, unknown>;
        const taskId = payload.taskId == null ? null : Number(payload.taskId);
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  state: "working",
                  currentTaskId: taskId,
                  statusText: payload.title ? String(payload.title) : agent.statusText,
                }
              : agent,
          ),
        };
      }

      if (event.eventType === "task.completed") {
        const payload = event.payload as Record<string, unknown>;
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  state: "idle",
                  currentTaskId: null,
                  statusText: payload.resultSummary ? String(payload.resultSummary) : "Task completed",
                }
              : agent,
          ),
        };
      }

      if (event.eventType === "web_research_started") {
        const payload = event.payload as Record<string, unknown>;
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  state: "thinking",
                  statusText: payload.statusText ? String(payload.statusText) : "Researching the web",
                }
              : agent,
          ),
        };
      }

      if (event.eventType === "web_research_finished") {
        const payload = event.payload as Record<string, unknown>;
        return {
          ...prev,
          recentEvents,
          agents: prev.agents.map((agent) =>
            agent.externalId === event.agentExternalId
              ? {
                  ...agent,
                  state: "working",
                  statusText: payload.summary ? String(payload.summary) : "Research completed",
                }
              : agent,
          ),
        };
      }

      return { ...prev, recentEvents };
    });
  }, [upsertAgent]);

  const handleMessage = useCallback((msg: Message) => {
    setMessages((prev) => [...prev, msg]);
    if (msg.senderType === "AGENT") {
      setStreamingText("");
    }
    if (msg.senderType === "SYSTEM" && msg.content.startsWith("🔧")) {
      loadFiles();
    }
  }, [loadFiles]);

  const handleContainerEvent = useCallback((event: ContainerEvent) => {
    setContainerLogs((prev) => [...prev, event]);
  }, []);

  const handleAgentAction = useCallback((action: AgentAction) => {
    setAgentActions((prev) => [...prev, action]);
    if (action.actionType === "file_write" && action.status === "completed") {
      loadFiles();
    }
  }, [loadFiles]);

  const handleChatStream = useCallback((chunk: ChatStreamChunk) => {
    setStreamingText((prev) => prev + chunk.chunk);
  }, []);

  useSocket(
    world?.roomId ?? null,
    handleEvent,
    handleWorld,
    handleSimulationEvent,
    handleMessage,
    handleContainerEvent,
    handleAgentAction,
    handleChatStream,
  );

  if (loading) {
    return (
      <main className="min-h-screen bg-gray-950 text-white flex items-center justify-center">
        <p className="text-gray-400 text-lg">Загрузка комнаты…</p>
      </main>
    );
  }

  if (error || !world) {
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
      <WorldOffice world={world} agentActions={agentActions} />
      <ChatPanel roomId={world.roomId} messages={messages} streamingText={streamingText} />
      <WorkbenchPanel
        tasks={world.tasks}
        files={files}
        selectedPath={selectedPath}
        fileContent={fileContent}
        filesLoading={filesLoading}
        filesError={filesError}
        containerLogs={containerLogs}
        agentActions={agentActions}
        recentEvents={world.recentEvents}
        onSelectFile={handleSelectFile}
        onRefreshFiles={loadFiles}
      />
    </main>
  );
}
