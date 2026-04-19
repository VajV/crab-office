"use client";

import { useState, useEffect, useRef } from "react";
import type { Task, SandboxFileEntry, ContainerEvent, AgentAction, SimulationEvent } from "@/types";
import FilesPanel from "./FilesPanel";
import TerminalPanel from "./TerminalPanel";
import ActionsPanel from "./ActionsPanel";
import SimulationEventsPanel from "./SimulationEventsPanel";

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

type Tab = "tasks" | "files" | "terminal" | "actions" | "events";

interface WorkbenchPanelProps {
  tasks: Task[];
  files: SandboxFileEntry[];
  selectedPath: string | null;
  fileContent: string;
  filesLoading: boolean;
  filesError: string | null;
  containerLogs: ContainerEvent[];
  agentActions: AgentAction[];
  recentEvents: SimulationEvent[];
  onSelectFile: (path: string) => void;
  onRefreshFiles: () => void;
}

export default function WorkbenchPanel({
  tasks,
  files,
  selectedPath,
  fileContent,
  filesLoading,
  filesError,
  containerLogs,
  agentActions,
  recentEvents,
  onSelectFile,
  onRefreshFiles,
}: WorkbenchPanelProps) {
  const [activeTab, setActiveTab] = useState<Tab>("tasks");
  const prevLogsLen = useRef(containerLogs.length);
  const [newLogCount, setNewLogCount] = useState(0);
  const prevActionsLen = useRef(agentActions.length);
  const [newActionCount, setNewActionCount] = useState(0);
  const prevEventsLen = useRef(recentEvents.length);
  const [newEventCount, setNewEventCount] = useState(0);

  // Auto-switch to terminal on error, track new log badge count
  useEffect(() => {
    if (containerLogs.length > prevLogsLen.current) {
      const newest = containerLogs[containerLogs.length - 1];
      if (newest.status === "error") {
        setActiveTab("terminal");
        setNewLogCount(0);
      } else if (activeTab !== "terminal") {
        setNewLogCount((c) => c + (containerLogs.length - prevLogsLen.current));
      }
    }
    prevLogsLen.current = containerLogs.length;
  }, [containerLogs, activeTab]);

  // Track new agent actions badge
  useEffect(() => {
    if (agentActions.length > prevActionsLen.current) {
      if (activeTab !== "actions") {
        setNewActionCount((c) => c + (agentActions.length - prevActionsLen.current));
      }
    }
    prevActionsLen.current = agentActions.length;
  }, [agentActions, activeTab]);

  useEffect(() => {
    if (recentEvents.length > prevEventsLen.current) {
      if (activeTab !== "events") {
        setNewEventCount((c) => c + (recentEvents.length - prevEventsLen.current));
      }
    }
    prevEventsLen.current = recentEvents.length;
  }, [recentEvents, activeTab]);

  return (
    <div className="w-full max-w-lg bg-gray-900 border border-gray-700 rounded-lg overflow-hidden">
      {/* Tab bar */}
      <div className="flex border-b border-gray-700 bg-gray-800">
        <button
          onClick={() => setActiveTab("tasks")}
          className={`flex-1 px-3 py-2 text-sm font-medium ${
            activeTab === "tasks"
              ? "text-orange-400 border-b-2 border-orange-400"
              : "text-gray-400 hover:text-gray-200"
          }`}
        >
          📋 Задачи
        </button>
        <button
          onClick={() => setActiveTab("files")}
          className={`flex-1 px-3 py-2 text-sm font-medium ${
            activeTab === "files"
              ? "text-orange-400 border-b-2 border-orange-400"
              : "text-gray-400 hover:text-gray-200"
          }`}
        >
          📁 Файлы
        </button>
        <button
          onClick={() => { setActiveTab("terminal"); setNewLogCount(0); }}
          className={`flex-1 px-3 py-2 text-sm font-medium relative ${
            activeTab === "terminal"
              ? "text-orange-400 border-b-2 border-orange-400"
              : "text-gray-400 hover:text-gray-200"
          }`}
        >
          🖥️ Терминал
          {containerLogs.some((l) => l.status === "running") && (
            <span className="ml-1 inline-block w-2 h-2 rounded-full bg-blue-400 animate-pulse" />
          )}
          {newLogCount > 0 && activeTab !== "terminal" && (
            <span className="absolute -top-1 -right-1 bg-orange-500 text-white text-[10px] rounded-full w-4 h-4 flex items-center justify-center">
              {newLogCount}
            </span>
          )}
        </button>
        <button
          onClick={() => { setActiveTab("actions"); setNewActionCount(0); }}
          className={`flex-1 px-3 py-2 text-sm font-medium relative ${
            activeTab === "actions"
              ? "text-orange-400 border-b-2 border-orange-400"
              : "text-gray-400 hover:text-gray-200"
          }`}
        >
          🔧 Действия
          {agentActions.some((a) => a.status === "started") && (
            <span className="ml-1 inline-block w-2 h-2 rounded-full bg-yellow-400 animate-pulse" />
          )}
          {newActionCount > 0 && activeTab !== "actions" && (
            <span className="absolute -top-1 -right-1 bg-orange-500 text-white text-[10px] rounded-full w-4 h-4 flex items-center justify-center">
              {newActionCount}
            </span>
          )}
        </button>
        <button
          onClick={() => { setActiveTab("events"); setNewEventCount(0); }}
          className={`flex-1 px-3 py-2 text-sm font-medium relative ${
            activeTab === "events"
              ? "text-orange-400 border-b-2 border-orange-400"
              : "text-gray-400 hover:text-gray-200"
          }`}
        >
          🧭 События
          {newEventCount > 0 && activeTab !== "events" && (
            <span className="absolute -top-1 -right-1 bg-orange-500 text-white text-[10px] rounded-full w-4 h-4 flex items-center justify-center">
              {newEventCount}
            </span>
          )}
        </button>
      </div>

      {/* Tab content */}
      {activeTab === "tasks" && (
        <div className="p-3 space-y-2 max-h-48 overflow-y-auto">
          {tasks.length === 0 ? (
            <p className="text-sm text-gray-500">Нет задач</p>
          ) : (
            tasks.map((t) => (
              <div key={t.id} className="flex items-center gap-2 text-sm">
                <span
                  className={`px-2 py-0.5 rounded text-xs text-white ${
                    statusColors[t.status] || "bg-gray-600"
                  }`}
                >
                  {statusLabels[t.status] || t.status}
                </span>
                <span className="text-gray-200 truncate">{t.title}</span>
                {t.assignedAgentExternalId && (
                  <span className="text-xs text-gray-500 ml-auto">
                    {t.assignedAgentExternalId}
                  </span>
                )}
              </div>
            ))
          )}
        </div>
      )}

      {activeTab === "files" && (
        <FilesPanel
          files={files}
          selectedPath={selectedPath}
          fileContent={fileContent}
          loading={filesLoading}
          error={filesError}
          onSelect={onSelectFile}
          onRefresh={onRefreshFiles}
        />
      )}

      {activeTab === "terminal" && (
        <TerminalPanel logs={containerLogs} />
      )}

      {activeTab === "actions" && (
        <ActionsPanel actions={agentActions} />
      )}

      {activeTab === "events" && (
        <SimulationEventsPanel events={recentEvents} />
      )}
    </div>
  );
}
