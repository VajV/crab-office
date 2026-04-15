"use client";

import { useEffect, useRef } from "react";
import { Client } from "@stomp/stompjs";
import type { AgentEvent, Message, ContainerEvent, AgentAction, ChatStreamChunk } from "@/types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL || "ws://localhost:8080/ws";

export function useSocket(
  roomId: number | null,
  onEvent: (event: AgentEvent) => void,
  onMessage?: (msg: Message) => void,
  onContainerEvent?: (event: ContainerEvent) => void,
  onAgentAction?: (action: AgentAction) => void,
  onChatStream?: (chunk: ChatStreamChunk) => void,
) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;
  const onContainerRef = useRef(onContainerEvent);
  onContainerRef.current = onContainerEvent;
  const onActionRef = useRef(onAgentAction);
  onActionRef.current = onAgentAction;
  const onChatStreamRef = useRef(onChatStream);
  onChatStreamRef.current = onChatStream;

  useEffect(() => {
    if (roomId == null) return;

    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 5000,
      onConnect: () => {
        console.log("[WS] Connected to", WS_URL, "room", roomId);
        client.subscribe(`/topic/rooms/${roomId}`, (msg) => {
          try {
            const event: AgentEvent = JSON.parse(msg.body);
            onEventRef.current(event);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/chat`, (msg) => {
          try {
            const parsed: Message = JSON.parse(msg.body);
            onMessageRef.current?.(parsed);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/container`, (msg) => {
          try {
            const parsed: ContainerEvent = JSON.parse(msg.body);
            onContainerRef.current?.(parsed);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/actions`, (msg) => {
          try {
            const parsed: AgentAction = JSON.parse(msg.body);
            onActionRef.current?.(parsed);
          } catch {
            // ignore
          }
        });
        client.subscribe(`/topic/rooms/${roomId}/chat-stream`, (msg) => {
          try {
            const parsed: ChatStreamChunk = JSON.parse(msg.body);
            onChatStreamRef.current?.(parsed);
          } catch {
            // ignore
          }
        });
      },
      onStompError: (frame) => {
        console.error("[WS] STOMP error:", frame.headers["message"], frame.body);
      },
      onWebSocketError: (event) => {
        console.error("[WS] WebSocket error:", event);
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [roomId]);
}
