"use client";

import { useState } from "react";
import type { Task, SandboxFileEntry } from "@/types";
import FilesPanel from "./FilesPanel";

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

type Tab = "tasks" | "files";

interface WorkbenchPanelProps {
  tasks: Task[];
  files: SandboxFileEntry[];
  selectedPath: string | null;
  fileContent: string;
  filesLoading: boolean;
  filesError: string | null;
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
  onSelectFile,
  onRefreshFiles,
}: WorkbenchPanelProps) {
  const [activeTab, setActiveTab] = useState<Tab>("tasks");

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
    </div>
  );
}
