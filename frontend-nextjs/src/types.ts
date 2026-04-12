/** Shared TypeScript types matching Java/Python contracts. */

export interface Position {
  x: number;
  y: number;
}

export interface Agent {
  externalId: string;
  name: string;
  role: string;
  x: number;
  y: number;
  state: string;
}

export interface Layout {
  width: number;
  height: number;
  backgroundPreset: string;
}

export interface Room {
  id: number;
  roomName: string;
  theme: string;
  layout: Layout;
  agents: Agent[];
}

export interface AgentEvent {
  roomId: number;
  agentExternalId: string;
  eventType: string;
  state: string;
  x: number;
  y: number;
  message: string;
  timestamp: string;
}

export interface Message {
  id: number;
  roomId: number;
  agentExternalId: string | null;
  senderType: "USER" | "AGENT" | "SYSTEM";
  content: string;
  createdAt: string;
}

export interface Task {
  id: number;
  roomId: number;
  assignedAgentExternalId: string | null;
  title: string;
  description: string | null;
  status: "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED";
  createdAt: string;
  updatedAt: string | null;
}
