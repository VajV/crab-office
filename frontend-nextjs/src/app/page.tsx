"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { Room } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const PRESETS = ["tech", "cozy", "creative"] as const;

export default function Home() {
  const router = useRouter();
  const [prompt, setPrompt] = useState("");
  const [preset, setPreset] = useState<string>("tech");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
      const payload = await res.json().catch(() => null);
      if (!res.ok) {
        const message =
          payload && typeof payload === "object" && "message" in payload && typeof payload.message === "string"
            ? payload.message
            : payload && typeof payload === "object" && "error" in payload && typeof payload.error === "string"
              ? payload.error
              : `Server error: ${res.status}`;
        throw new Error(message);
      }
      const data = payload as Room;
      router.push(`/room/${data.id}`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="min-h-screen bg-gray-950 text-white flex flex-col items-center justify-center py-12 px-4 gap-8">
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
    </main>
  );
}
