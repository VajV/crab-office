"use client";

import type { Task } from "@/types";

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

interface TaskPanelProps {
  tasks: Task[];
}

export default function TaskPanel({ tasks }: TaskPanelProps) {
  if (tasks.length === 0) return null;

  return (
    <div className="w-full max-w-lg bg-gray-900 border border-gray-700 rounded-lg overflow-hidden">
      <div className="px-3 py-2 bg-gray-800 text-sm font-medium text-gray-300 border-b border-gray-700">
        📋 Задачи
      </div>
      <div className="p-3 space-y-2 max-h-48 overflow-y-auto">
        {tasks.map((t) => (
          <div key={t.id} className="flex items-center gap-2 text-sm">
            <span className={`px-2 py-0.5 rounded text-xs text-white ${statusColors[t.status] || "bg-gray-600"}`}>
              {statusLabels[t.status] || t.status}
            </span>
            <span className="text-gray-200 truncate">{t.title}</span>
            {t.assignedAgentExternalId && (
              <span className="text-xs text-gray-500 ml-auto">{t.assignedAgentExternalId}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
