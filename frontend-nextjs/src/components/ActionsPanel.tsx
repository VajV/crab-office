"use client";

import { useEffect, useRef } from "react";
import type { AgentAction } from "@/types";

const actionIcons: Record<string, string> = {
  web_fetch: "🌐",
  exec: "💻",
  file_read: "📄",
  file_write: "✍️",
  file_edit: "📝",
  thinking: "🧠",
  tool_call: "🔧",
  browser: "🖥️",
  memory: "💾",
};

const statusStyles: Record<string, string> = {
  started: "text-yellow-400",
  completed: "text-green-400",
  failed: "text-red-400",
};

const statusLabels: Record<string, string> = {
  started: "⏳",
  completed: "✅",
  failed: "❌",
};

function formatParams(params: Record<string, unknown>): string {
  const entries = Object.entries(params);
  if (entries.length === 0) return "";
  return entries
    .map(([k, v]) => {
      const val = typeof v === "string" ? v : JSON.stringify(v);
      return `${k}: ${val.length > 60 ? val.slice(0, 57) + "…" : val}`;
    })
    .join(", ");
}

function formatTime(ts: string): string {
  if (!ts) return "";
  try {
    return new Date(ts).toLocaleTimeString("ru-RU", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  } catch {
    return ts;
  }
}

export default function ActionsPanel({ actions }: { actions: AgentAction[] }) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [actions.length]);

  if (actions.length === 0) {
    return (
      <div className="p-3">
        <p className="text-sm text-gray-500">Нет действий агента</p>
      </div>
    );
  }

  return (
    <div className="p-3 space-y-1.5 max-h-48 overflow-y-auto">
      {actions.map((a, i) => (
        <div
          key={`${a.timestamp}-${i}`}
          className="flex items-start gap-2 text-sm border-l-2 border-gray-700 pl-2"
        >
          <span className="text-base leading-none mt-0.5">
            {actionIcons[a.actionType] || "🔧"}
          </span>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5">
              <span className={`font-medium ${statusStyles[a.status] || ""}`}>
                {statusLabels[a.status] || a.status}
              </span>
              <span className="text-gray-200 font-mono text-xs">
                {a.toolName}
              </span>
              <span className="text-gray-600 text-[10px] ml-auto">
                {formatTime(a.timestamp)}
              </span>
            </div>
            {Object.keys(a.params).length > 0 && (
              <p className="text-gray-500 text-xs truncate">
                {formatParams(a.params)}
              </p>
            )}
            {a.result && (
              <p className="text-gray-400 text-xs truncate mt-0.5">
                → {a.result.length > 80 ? a.result.slice(0, 77) + "…" : a.result}
              </p>
            )}
          </div>
          {a.status === "started" && (
            <span className="inline-block w-2 h-2 rounded-full bg-yellow-400 animate-pulse mt-1.5" />
          )}
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}
