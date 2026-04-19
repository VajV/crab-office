"use client";

import { useEffect, useRef } from "react";
import type { SimulationEvent } from "@/types";

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

function summarize(event: SimulationEvent): string {
  const payload = event.payload ?? {};
  if (event.eventType === "agent.spawned") {
    const agent = payload.agent as Record<string, unknown> | undefined;
    return agent ? `Spawned ${String(agent.name ?? agent.externalId ?? "agent")}` : "Agent spawned";
  }
  if (event.eventType === "agent.state_changed") {
    return String(payload.statusText ?? payload.toState ?? event.state ?? "State updated");
  }
  if (event.eventType === "agent.moved") {
    return String(payload.reason ?? "Agent moved");
  }
  if (event.eventType === "web_research_started") {
    return String(payload.statusText ?? payload.query ?? "Web research started");
  }
  if (event.eventType === "web_research_finished") {
    return String(payload.summary ?? "Web research finished");
  }
  if (event.eventType === "task.completed") {
    return String(payload.resultSummary ?? "Task completed");
  }
  return event.eventType;
}

export default function SimulationEventsPanel({ events }: { events: SimulationEvent[] }) {
  const topRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    topRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [events.length]);

  if (events.length === 0) {
    return (
      <div className="p-3">
        <p className="text-sm text-gray-500">Нет simulation events</p>
      </div>
    );
  }

  return (
    <div className="p-3 space-y-2 max-h-48 overflow-y-auto">
      <div ref={topRef} />
      {events.map((event) => (
        <div key={`${event.id}-${event.timestamp}`} className="border-l-2 border-cyan-700/60 pl-2 text-sm">
          <div className="flex items-center gap-2">
            <span className="font-mono text-cyan-300 text-xs">{event.eventType}</span>
            <span className="text-[10px] text-gray-500 ml-auto">{formatTime(event.timestamp)}</span>
          </div>
          <p className="text-gray-200 text-xs mt-0.5">{summarize(event)}</p>
          {(event.agentExternalId || event.locationId) && (
            <p className="text-[10px] text-gray-500 mt-0.5">
              {[event.agentExternalId, event.locationId].filter(Boolean).join(" • ")}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
