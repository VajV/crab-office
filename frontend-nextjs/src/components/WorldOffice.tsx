"use client";

import type { Agent, AgentAction, World, WorldLocation } from "@/types";
import PixelAgent from "./PixelAgent";

const CELL = 48;

const bgColors: Record<string, string> = {
  marketing: "#3b1f3a",
  engineering: "#10233f",
  operations: "#24321a",
};

function agentsForLocation(world: World, locationId: string) {
  return world.agents.filter((agent) => agent.locationId === locationId);
}

function currentActionForAgent(agent: Agent, actions?: AgentAction[]) {
  return actions
    ?.filter((action) => action.agentExternalId === agent.externalId && action.status === "started")
    .at(-1)?.actionType;
}

function LocationScene({
  location,
  agents,
  agentActions,
}: {
  location: WorldLocation;
  agents: Agent[];
  agentActions?: AgentAction[];
}) {
  const bg = bgColors[location.kind] || "#1e293b";

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-white">{location.name}</h3>
        <span className="text-xs uppercase tracking-wide text-gray-400">{location.kind}</span>
      </div>
      <div
        className="relative border-2 border-gray-700 rounded-lg overflow-hidden"
        style={{
          width: location.width * CELL,
          height: location.height * CELL,
          backgroundColor: bg,
          imageRendering: "pixelated",
        }}
      >
        {Array.from({ length: location.width * location.height }).map((_, i) => {
          const x = i % location.width;
          const y = Math.floor(i / location.width);
          return (
            <div
              key={i}
              className="absolute border border-white/5"
              style={{ left: x * CELL, top: y * CELL, width: CELL, height: CELL }}
            />
          );
        })}

        {location.interactionPoints.map((point, idx) => (
          <div
            key={`${location.id}-point-${idx}`}
            className="absolute rounded-sm border border-yellow-300/40 bg-yellow-300/10"
            style={{ left: point.x * CELL + 8, top: point.y * CELL + 8, width: CELL - 16, height: CELL - 16 }}
            title={point.kind || "interaction"}
          />
        ))}

        {agents.map((agent) => (
          <PixelAgent
            key={`${location.id}-${agent.externalId}`}
            agent={agent}
            currentAction={currentActionForAgent(agent, agentActions)}
          />
        ))}
      </div>
    </section>
  );
}

export default function WorldOffice({ world, agentActions }: { world: World; agentActions?: AgentAction[] }) {
  // Legacy mode: single office without locations
  if (!world.locations || world.locations.length === 0) {
    const layout = world.layout || { width: 12, height: 8 };
    return (
      <div className="w-full max-w-6xl flex flex-col gap-6">
        <div className="flex flex-col gap-1">
          <h2 className="text-2xl font-bold text-white">{world.roomName}</h2>
          <p className="text-sm text-gray-400">Single office layout</p>
        </div>
        <div
          className="relative border-2 border-gray-700 rounded-lg overflow-hidden"
          style={{
            width: layout.width * CELL,
            height: layout.height * CELL,
            backgroundColor: "#1e293b",
            imageRendering: "pixelated",
          }}
        >
          {Array.from({ length: layout.width * layout.height }).map((_, i) => {
            const x = i % layout.width;
            const y = Math.floor(i / layout.width);
            return (
              <div
                key={i}
                className="absolute border border-white/5"
                style={{ left: x * CELL, top: y * CELL, width: CELL, height: CELL }}
              />
            );
          })}
          {world.agents.map((agent) => (
            <PixelAgent
              key={agent.externalId}
              agent={agent}
              currentAction={currentActionForAgent(agent, agentActions)}
            />
          ))}
        </div>
      </div>
    );
  }

  // New mode: multiple locations
  return (
    <div className="w-full max-w-6xl flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h2 className="text-2xl font-bold text-white">{world.roomName}</h2>
        <p className="text-sm text-gray-400">Three fixed office locations with backend-owned world state.</p>
      </div>

      <div className="grid gap-6 xl:grid-cols-3 lg:grid-cols-2 grid-cols-1">
        {world.locations.map((location) => (
          <LocationScene
            key={location.id}
            location={location}
            agents={agentsForLocation(world, location.id)}
            agentActions={agentActions}
          />
        ))}
      </div>
    </div>
  );
}
