"use client";

import { useEffect, useRef } from "react";
import type { ContainerEvent } from "@/types";

interface TerminalPanelProps {
  logs: ContainerEvent[];
}

export default function TerminalPanel({ logs }: TerminalPanelProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [logs]);

  if (logs.length === 0) {
    return (
      <div className="p-3 text-sm text-gray-500">
        Нет выполненных команд
      </div>
    );
  }

  return (
    <div className="bg-gray-950 font-mono text-xs max-h-64 overflow-y-auto p-3 space-y-2">
      {logs.map((log, i) => (
        <div key={i} className="border-b border-gray-800 pb-2">
          {log.status === "creating" && (
            <div className="text-blue-400">⏳ Создание контейнера…</div>
          )}
          {log.status === "running" && log.command && (
            <div className="text-yellow-400 flex items-center gap-2">
              <span className="inline-block w-2 h-2 rounded-full bg-blue-400 animate-pulse" />
              <span>$ {log.command}</span>
            </div>
          )}
          {log.status === "stopped" && (
            <>
              {log.command && (
                <div className="text-green-400">$ {log.command}</div>
              )}
              {log.stdout && (
                <pre className="text-gray-300 whitespace-pre-wrap mt-1">{log.stdout}</pre>
              )}
              <div className="text-green-600 text-[10px] mt-1">
                exit {log.exitCode}
              </div>
            </>
          )}
          {log.status === "error" && (
            <>
              {log.command && (
                <div className="text-red-400">$ {log.command}</div>
              )}
              {log.stdout && (
                <pre className="text-red-300 whitespace-pre-wrap mt-1">{log.stdout}</pre>
              )}
              {log.stderr && (
                <pre className="text-red-300 whitespace-pre-wrap mt-1">{log.stderr}</pre>
              )}
              <div className="text-red-600 text-[10px] mt-1">
                exit {log.exitCode}{log.timedOut ? " (timeout)" : ""}
              </div>
            </>
          )}
        </div>
      ))}
      <div ref={bottomRef} />
    </div>
  );
}
