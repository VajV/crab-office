"use client";

import type { Room, AgentAction } from "@/types";
import PixelAgent from "./PixelAgent";

const CELL = 64;

const bgColors: Record<string, string> = {
  tech: "#1e293b",
  cozy: "#3b2f2f",
  creative: "#2e1065",
};

export default function Office({ room, agentActions }: { room: Room; agentActions?: AgentAction[] }) {
  const bg = bgColors[room.theme] || "#1e293b";

  return (
    <div className="flex flex-col items-center gap-4">
      <h2 className="text-xl font-bold text-white">{room.roomName}</h2>

      <div
        className="relative border-2 border-gray-600 rounded-lg overflow-hidden"
        style={{
          width: room.layout.width * CELL,
          height: room.layout.height * CELL,
          backgroundColor: bg,
          imageRendering: "pixelated",
        }}
      >
        {/* grid lines */}
        {Array.from({ length: room.layout.width * room.layout.height }).map((_, i) => {
          const x = i % room.layout.width;
          const y = Math.floor(i / room.layout.width);
          return (
            <div
              key={i}
              className="absolute border border-white/5"
              style={{ left: x * CELL, top: y * CELL, width: CELL, height: CELL }}
            />
          );
        })}

        {/* agents */}
        {room.agents.map((agent) => {
          const latestAction = agentActions
            ?.filter((a) => a.agentExternalId === agent.externalId && a.status === "started")
            .at(-1);
          return (
            <PixelAgent
              key={agent.externalId}
              agent={agent}
              currentAction={latestAction?.actionType}
            />
          );
        })}
      </div>
    </div>
  );
}
