"use client";

import { motion } from "framer-motion";
import type { Agent } from "@/types";

const CELL = 64; // px per grid cell

const stateColors: Record<string, string> = {
  idle: "#6ee7b7",
  walking: "#93c5fd",
  working: "#fcd34d",
  typing: "#f9a8d4",
};

const stateLabels: Record<string, string> = {
  idle: "💤",
  walking: "🚶",
  working: "💻",
  typing: "⌨️",
};

export default function PixelAgent({ agent, currentAction }: { agent: Agent; currentAction?: string }) {
  const color = stateColors[agent.state] || "#d1d5db";
  const label = stateLabels[agent.state] || "❓";

  const actionIcons: Record<string, string> = {
    web_fetch: "🌐",
    exec: "💻",
    file_read: "📄",
    file_write: "✍️",
    file_edit: "📝",
    thinking: "🧠",
    tool_call: "🔧",
  };

  return (
    <motion.div
      className="absolute flex flex-col items-center pointer-events-auto"
      animate={{ left: agent.x * CELL, top: agent.y * CELL }}
      transition={{ type: "spring", stiffness: 120, damping: 14 }}
      style={{ width: CELL, height: CELL }}
    >
      {/* action bubble */}
      {currentAction && actionIcons[currentAction] && (
        <span className="absolute -top-3 -right-1 text-sm animate-bounce">
          {actionIcons[currentAction]}
        </span>
      )}
      {/* body */}
      <div
        className="w-10 h-10 rounded-sm border-2 border-gray-800 flex items-center justify-center text-lg"
        style={{ backgroundColor: color, imageRendering: "pixelated" }}
      >
        {label}
      </div>
      {/* name tag */}
      <span className="mt-0.5 text-[10px] text-white bg-gray-900/70 px-1 rounded truncate max-w-[60px]" title={agent.statusText || agent.role}>
        {agent.name}
      </span>
    </motion.div>
  );
}
