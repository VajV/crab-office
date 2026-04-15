"use client";

import { useState, useRef, useEffect } from "react";
import type { Message } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface ChatPanelProps {
  roomId: number;
  messages: Message[];
  streamingText?: string;
}

export default function ChatPanel({ roomId, messages, streamingText }: ChatPanelProps) {
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingText]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    setSending(true);
    setInput("");
    try {
      await fetch(`${API_URL}/api/rooms/${roomId}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: text }),
      });
    } catch {
      // message will not appear if failed
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="flex flex-col w-full max-w-lg h-96 bg-gray-900 border border-gray-700 rounded-lg overflow-hidden">
      <div className="px-3 py-2 bg-gray-800 text-sm font-medium text-gray-300 border-b border-gray-700">
        💬 Чат с агентами
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {messages.map((m) => (
          <div
            key={m.id}
            className={`text-sm px-3 py-2 rounded-lg max-w-[85%] whitespace-pre-wrap ${
              m.senderType === "USER"
                ? "bg-orange-600/20 text-orange-200 ml-auto"
                : m.senderType === "SYSTEM"
                  ? "bg-indigo-900/30 text-indigo-200 border border-indigo-700/50 mx-auto text-center"
                  : "bg-gray-800 text-gray-200"
            }`}
          >
            {m.senderType === "SYSTEM" && (
              <span className="text-xs text-indigo-400 block mb-1">
                {m.content.startsWith("🔧") ? "📁 файловая операция" : "⚙️ система"}
              </span>
            )}
            {m.senderType === "AGENT" && (
              <span className="text-xs text-gray-400 block mb-0.5">
                🤖 {m.agentExternalId}
              </span>
            )}
            {m.content}
          </div>
        ))}
        {streamingText && (
          <div className="text-sm px-3 py-2 rounded-lg max-w-[85%] whitespace-pre-wrap bg-gray-800 text-gray-200 animate-pulse">
            <span className="text-xs text-gray-400 block mb-0.5">🤖 печатает…</span>
            {streamingText}
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="flex gap-2 p-2 border-t border-gray-700">
        <input
          className="flex-1 rounded-md bg-gray-800 border border-gray-600 px-3 py-2 text-sm text-white placeholder:text-gray-500 focus:outline-none focus:ring-1 focus:ring-orange-500"
          placeholder="Написать сообщение..."
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
        />
        <button
          onClick={send}
          disabled={sending || !input.trim()}
          className="px-4 py-2 bg-orange-600 hover:bg-orange-500 disabled:opacity-40 text-white text-sm rounded-md transition"
        >
          →
        </button>
      </div>
    </div>
  );
}
