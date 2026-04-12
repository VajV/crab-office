# Crab Office — Agent Chat & Orchestration System

> 4-stage plan: Chat Layer → Agent Brain → Telegram → Orchestration

---

## Stage 1: Chat Layer

### Task 1.1 — Create MessageEntity

Create the JPA entity for storing chat messages in the database.

**File:** `backend-java/src/main/java/com/craboffice/backend/entity/MessageEntity.java`

```java
package com.craboffice.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    /** null for user messages */
    private String agentExternalId;

    @Column(nullable = false)
    private String senderType; // USER or AGENT

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
```

**Verify:** Backend compiles — `cd backend-java && mvnw.cmd compile`

---

### Task 1.2 — Create MessageRepository

**File:** `backend-java/src/main/java/com/craboffice/backend/repository/MessageRepository.java`

```java
package com.craboffice.backend.repository;

import com.craboffice.backend.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByRoomIdOrderByCreatedAtAsc(Long roomId);
}
```

**Verify:** Backend compiles

---

### Task 1.3 — Create MessageDto

**File:** `backend-java/src/main/java/com/craboffice/backend/dto/MessageDto.java`

```java
package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageDto {
    private Long id;
    private Long roomId;
    private String agentExternalId;
    private String senderType;
    private String content;
    private String createdAt;
}
```

**File:** `backend-java/src/main/java/com/craboffice/backend/dto/SendMessageRequest.java`

```java
package com.craboffice.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SendMessageRequest {
    @NotBlank
    private String content;
}
```

**Verify:** Backend compiles

---

### Task 1.4 — Create ChatService

Saves user messages to DB, publishes to Redis for the agent brain to pick up, sends to WebSocket for frontend.

**File:** `backend-java/src/main/java/com/craboffice/backend/service/ChatService.java`

```java
package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.craboffice.backend.dto.SendMessageRequest;
import com.craboffice.backend.entity.MessageEntity;
import com.craboffice.backend.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_CHAT_CHANNEL = "crab:chat-inbound";

    public MessageDto sendUserMessage(Long roomId, SendMessageRequest request) {
        MessageEntity entity = MessageEntity.builder()
                .roomId(roomId)
                .senderType("USER")
                .content(request.getContent())
                .createdAt(Instant.now())
                .build();
        entity = messageRepository.save(entity);

        MessageDto dto = toDto(entity);

        // Notify frontend via WebSocket
        messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/chat", dto);

        // Publish to Redis for the Python agent brain
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.convertAndSend(REDIS_CHAT_CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish message to Redis", e);
        }

        return dto;
    }

    public void saveAndBroadcastAgentMessage(MessageDto agentMsg) {
        MessageEntity entity = MessageEntity.builder()
                .roomId(agentMsg.getRoomId())
                .agentExternalId(agentMsg.getAgentExternalId())
                .senderType("AGENT")
                .content(agentMsg.getContent())
                .createdAt(Instant.now())
                .build();
        entity = messageRepository.save(entity);

        MessageDto dto = toDto(entity);
        messagingTemplate.convertAndSend("/topic/rooms/" + dto.getRoomId() + "/chat", dto);
    }

    public List<MessageDto> getMessages(Long roomId) {
        return messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private MessageDto toDto(MessageEntity entity) {
        return MessageDto.builder()
                .id(entity.getId())
                .roomId(entity.getRoomId())
                .agentExternalId(entity.getAgentExternalId())
                .senderType(entity.getSenderType())
                .content(entity.getContent())
                .createdAt(entity.getCreatedAt().toString())
                .build();
    }
}
```

**Verify:** Backend compiles

---

### Task 1.5 — Create ChatController

REST endpoints for sending and retrieving messages.

**File:** `backend-java/src/main/java/com/craboffice/backend/controller/ChatController.java`

```java
package com.craboffice.backend.controller;

import com.craboffice.backend.dto.MessageDto;
import com.craboffice.backend.dto.SendMessageRequest;
import com.craboffice.backend.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto sendMessage(
            @PathVariable Long roomId,
            @Valid @RequestBody SendMessageRequest request) {
        return chatService.sendUserMessage(roomId, request);
    }

    @GetMapping
    public List<MessageDto> getMessages(@PathVariable Long roomId) {
        return chatService.getMessages(roomId);
    }
}
```

**Verify:** Backend compiles. Test: `curl -s http://localhost:8080/api/rooms/1/messages`

---

### Task 1.6 — Create ChatDispatcher (Redis → WebSocket for agent responses)

Listens to `crab:chat-outbound` Redis channel for agent replies, saves them to DB and pushes to WebSocket.

**File:** `backend-java/src/main/java/com/craboffice/backend/service/ChatDispatcher.java`

```java
package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatDispatcher implements MessageListener {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatDispatcher(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MessageDto agentMsg = objectMapper.readValue(message.getBody(), MessageDto.class);
            chatService.saveAndBroadcastAgentMessage(agentMsg);
            log.info("Agent message saved & broadcast for room {}", agentMsg.getRoomId());
        } catch (Exception e) {
            log.error("Failed to process agent chat response from Redis", e);
        }
    }

    @Configuration
    static class ChatRedisSubscriptionConfig {
        @Bean
        RedisMessageListenerContainer chatRedisContainer(
                RedisConnectionFactory connectionFactory,
                ChatDispatcher dispatcher) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(dispatcher, new ChannelTopic("crab:chat-outbound"));
            return container;
        }
    }
}
```

**Verify:** Backend compiles and starts

---

### Task 1.7 — Add Message type to frontend

**Edit:** `frontend-nextjs/src/types.ts` — add `Message` interface:

```typescript
export interface Message {
  id: number;
  roomId: number;
  agentExternalId: string | null;
  senderType: "USER" | "AGENT";
  content: string;
  createdAt: string;
}
```

**Verify:** `cd frontend-nextjs && npx tsc --noEmit`

---

### Task 1.8 — Update useSocket to handle chat messages

**Edit:** `frontend-nextjs/src/hooks/useSocket.ts`

Add a second callback parameter `onMessage` and subscribe to `/topic/rooms/{roomId}/chat`.

```typescript
"use client";

import { useEffect, useRef } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { AgentEvent, Message } from "@/types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL || "http://localhost:8080/ws";

export function useSocket(
  roomId: number | null,
  onEvent: (event: AgentEvent) => void,
  onMessage?: (msg: Message) => void,
) {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  useEffect(() => {
    if (roomId == null) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL) as unknown as WebSocket,
      reconnectDelay: 5000,
      onConnect: () => {
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
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [roomId]);
}
```

**Verify:** TypeScript compiles

---

### Task 1.9 — Create ChatPanel component

**File:** `frontend-nextjs/src/components/ChatPanel.tsx`

```tsx
"use client";

import { useState, useRef, useEffect } from "react";
import type { Message } from "@/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface ChatPanelProps {
  roomId: number;
  messages: Message[];
}

export default function ChatPanel({ roomId, messages }: ChatPanelProps) {
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

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
            className={`text-sm px-3 py-2 rounded-lg max-w-[85%] ${
              m.senderType === "USER"
                ? "bg-orange-600/20 text-orange-200 ml-auto"
                : "bg-gray-800 text-gray-200"
            }`}
          >
            {m.senderType === "AGENT" && (
              <span className="text-xs text-gray-400 block mb-0.5">
                🤖 {m.agentExternalId}
              </span>
            )}
            {m.content}
          </div>
        ))}
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
```

**Verify:** TypeScript compiles

---

### Task 1.10 — Wire ChatPanel into page.tsx

**Edit:** `frontend-nextjs/src/app/page.tsx`

Changes:
1. Import `ChatPanel` and `Message`
2. Add `messages` state
3. Add `handleMessage` callback
4. Pass `onMessage` to `useSocket`
5. Fetch existing messages when room loads
6. Render `<ChatPanel>` below the office

After edit, the page should have chat functionality alongside the office view.

```diff
+ import ChatPanel from "@/components/ChatPanel";
+ import type { Room, AgentEvent, Message } from "@/types";

+ const [messages, setMessages] = useState<Message[]>([]);

+ const handleMessage = useCallback((msg: Message) => {
+   setMessages((prev) => [...prev, msg]);
+ }, []);

- useSocket(room?.id ?? null, handleEvent);
+ useSocket(room?.id ?? null, handleEvent, handleMessage);

  // After setRoom(data) in createRoom():
+ // Fetch existing messages
+ const msgRes = await fetch(`${API_URL}/api/rooms/${data.id}/messages`);
+ if (msgRes.ok) {
+   const msgs: Message[] = await msgRes.json();
+   setMessages(msgs);
+ }

  // In JSX, after {room && <Office room={room} />}:
+ {room && <ChatPanel roomId={room.id} messages={messages} />}
```

**Verify:** Page renders with chat panel below office

---

## Stage 2: Agent Brain

### Task 2.1 — Add ChatMessage model to Python

**Edit:** `ai-service-python/models.py` — add:

```python
class ChatMessage(BaseModel):
    id: int | None = None
    roomId: int
    agentExternalId: str | None = None
    senderType: str  # USER or AGENT
    content: str
    createdAt: str | None = None

class AgentResponse(BaseModel):
    """Response from the agent brain — text + optional action."""
    text: str
    action: str | None = None  # e.g. "move", "change_state"
    actionParams: dict | None = None
```

**Verify:** Python imports work — `cd ai-service-python && .venv\Scripts\python -c "from models import ChatMessage, AgentResponse; print('ok')"`

---

### Task 2.2 — Create agent system prompts

System prompt builder per agent role. Each agent has unique personality and capabilities.

**File:** `ai-service-python/agent_prompts.py`

```python
"""Per-role system prompt builder for agent brain."""

from __future__ import annotations

ROLE_PROMPTS: dict[str, str] = {
    "architect": (
        "You are the Crab Architect. You specialize in system design, architecture decisions, "
        "and high-level planning. You think in diagrams and abstractions. "
        "You communicate in a clear, structured manner."
    ),
    "developer": (
        "You are the Crab Developer. You are a hands-on coder who loves writing clean, "
        "efficient code. You suggest implementations, debug issues, and write pseudocode. "
        "You're practical and detail-oriented."
    ),
    "analyst": (
        "You are the Crab Analyst. You analyze requirements, identify edge cases, "
        "and ask clarifying questions. You focus on data, metrics, and correctness. "
        "You're thorough and methodical."
    ),
    "manager": (
        "You are the Crab Manager. You coordinate between team members, track progress, "
        "and ensure deadlines are met. You summarize discussions and make decisions. "
        "You're organized and communicative."
    ),
}

BASE_CONTEXT = (
    "You are an AI agent in a virtual pixel-art office called Crab Office. "
    "You work in a team with other agents. When a user sends a message to the room, "
    "you may respond if the message is relevant to your role. "
    "Keep responses concise (1-3 sentences). "
    "You can respond in the same language the user writes in."
)


def build_system_prompt(role: str, agent_name: str, room_name: str) -> str:
    """Build a full system prompt for an agent."""
    role_part = ROLE_PROMPTS.get(role, f"You are a {role} in the team.")
    return (
        f"{BASE_CONTEXT}\n\n"
        f"Your name: {agent_name}\n"
        f"Your role: {role}\n"
        f"Room: {room_name}\n\n"
        f"{role_part}\n\n"
        "Respond ONLY with your message text. Do not include your name or role prefix."
    )
```

**Verify:** `cd ai-service-python && .venv\Scripts\python -c "from agent_prompts import build_system_prompt; print(build_system_prompt('developer', 'Crab Dev', 'Dev Room'))"`

---

### Task 2.3 — Create agent brain loop

The brain listens to `crab:chat-inbound` Redis channel, picks a relevant agent from the room, generates a response via LLM, and publishes the response to `crab:chat-outbound` Redis channel.

**File:** `ai-service-python/agent_brain.py`

```python
"""Agent brain — listens for user messages, generates agent responses via LLM."""

from __future__ import annotations

import asyncio
import json
import logging
import os

import redis.asyncio as aioredis
from openai import AsyncOpenAI

from agent_prompts import build_system_prompt
from models import ChatMessage

logger = logging.getLogger(__name__)

REDIS_INBOUND = "crab:chat-inbound"
REDIS_OUTBOUND = "crab:chat-outbound"
AGENT_INFO_KEY = "crab:room:{room_id}:agents"


def _get_redis() -> aioredis.Redis:
    return aioredis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"))


def _get_client() -> AsyncOpenAI:
    from architect import _get_client as get_openai_client
    return get_openai_client()


def _get_model() -> str:
    from architect import _get_model_name
    return _get_model_name()


async def _fetch_room_agents(room_id: int) -> list[dict]:
    """Fetch agent info from Redis cache or Java backend."""
    r = _get_redis()
    cached = await r.get(f"crab:room:{room_id}:agents")
    await r.aclose()

    if cached:
        return json.loads(cached)

    # Fallback: ask Java backend
    import httpx
    backend_url = os.getenv("BACKEND_URL", "http://localhost:8080")
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.get(f"{backend_url}/api/rooms/{room_id}")
            resp.raise_for_status()
            room_data = resp.json()
            agents = room_data.get("agents", [])
            # Cache in Redis for 10 minutes
            r = _get_redis()
            await r.setex(f"crab:room:{room_id}:agents", 600, json.dumps(agents))
            await r.aclose()
            return agents
    except Exception:
        logger.exception("Failed to fetch room agents from backend")
        return []


async def _pick_responding_agent(agents: list[dict], user_message: str) -> dict | None:
    """Pick the most relevant agent to respond. For now: the first agent (simple round-robin)."""
    if not agents:
        return None
    # Simple heuristic: manager responds to general questions,
    # developer responds to code-related, etc.
    # For MVP: cycle through agents or pick first one
    return agents[0]


async def _generate_agent_reply(
    agent: dict,
    user_message: str,
    room_name: str,
    history: list[dict] | None = None,
) -> str:
    """Call the LLM to generate a reply from the agent's perspective."""
    api_key = os.getenv("OPENROUTER_API_KEY", "")
    if not api_key:
        return f"[{agent.get('name', 'Agent')}] (mock) Received your message: {user_message}"

    system_prompt = build_system_prompt(
        role=agent.get("role", "developer"),
        agent_name=agent.get("name", "Agent"),
        room_name=room_name,
    )

    messages = [{"role": "system", "content": system_prompt}]
    if history:
        for h in history[-10:]:  # last 10 messages for context
            role = "assistant" if h.get("senderType") == "AGENT" else "user"
            messages.append({"role": role, "content": h.get("content", "")})
    messages.append({"role": "user", "content": user_message})

    try:
        client = _get_client()
        model = _get_model()
        resp = await client.chat.completions.create(
            model=model,
            messages=messages,
            temperature=0.8,
            max_tokens=512,
        )
        return resp.choices[0].message.content or "(no response)"
    except Exception:
        logger.exception("LLM call failed for agent %s", agent.get("name"))
        return f"Sorry, I'm having trouble thinking right now. Try again in a moment."


async def handle_inbound_message(raw_data: str) -> None:
    """Process a single inbound user message."""
    try:
        msg = json.loads(raw_data)
        chat_msg = ChatMessage(**msg)
    except Exception:
        logger.exception("Failed to parse inbound message")
        return

    if chat_msg.senderType != "USER":
        return  # Only respond to user messages

    room_id = chat_msg.roomId
    agents = await _fetch_room_agents(room_id)
    if not agents:
        logger.warning("No agents found for room %d", room_id)
        return

    agent = await _pick_responding_agent(agents, chat_msg.content)
    if not agent:
        return

    # Fetch recent history from Redis or just use the current message
    reply_text = await _generate_agent_reply(
        agent=agent,
        user_message=chat_msg.content,
        room_name=f"Room {room_id}",  # Could be enriched from room data
    )

    # Build outbound message
    outbound = {
        "roomId": room_id,
        "agentExternalId": agent.get("externalId", "unknown"),
        "senderType": "AGENT",
        "content": reply_text,
    }

    r = _get_redis()
    await r.publish(REDIS_OUTBOUND, json.dumps(outbound))
    await r.aclose()
    logger.info("Agent %s replied in room %d", agent.get("name"), room_id)


async def brain_loop() -> None:
    """Main loop — subscribe to Redis and process messages."""
    logger.info("Agent brain starting — listening on %s", REDIS_INBOUND)
    r = _get_redis()
    pubsub = r.pubsub()
    await pubsub.subscribe(REDIS_INBOUND)

    try:
        async for raw_message in pubsub.listen():
            if raw_message["type"] != "message":
                continue
            data = raw_message["data"]
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            asyncio.create_task(handle_inbound_message(data))
    except asyncio.CancelledError:
        logger.info("Agent brain shutting down")
    finally:
        await pubsub.unsubscribe(REDIS_INBOUND)
        await r.aclose()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from pathlib import Path
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().with_name(".env"))
    asyncio.run(brain_loop())
```

**Verify:** `cd ai-service-python && .venv\Scripts\python -c "from agent_brain import brain_loop; print('import ok')"`

---

### Task 2.4 — Add brain startup to FastAPI lifespan

The agent brain loop should start automatically when the FastAPI server starts.

**Edit:** `ai-service-python/main.py`

Add lifespan context manager to start/stop the brain loop:

```python
"""FastAPI entry point for Crab Office AI service."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException

from agent_brain import brain_loop
from agent_logic import move_agent
from architect import generate_room, get_llm_status
from models import AgentEvent, GenerateRequest, Position, RoomOut

load_dotenv(Path(__file__).resolve().with_name(".env"))

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start the agent brain loop on startup, cancel on shutdown."""
    task = asyncio.create_task(brain_loop())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="Crab Office AI Service", version="0.2.0", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", **get_llm_status()}


@app.post("/generate", response_model=RoomOut)
async def generate(req: GenerateRequest):
    """Generate an office room based on user prompt."""
    room = await generate_room(req.prompt, req.preset)
    return room


@app.post("/agents/event", response_model=AgentEvent)
async def agent_event(
    room_id: int,
    agent_external_id: str,
    current_state: str,
    target_state: str,
    x: int,
    y: int,
    message: str = "",
):
    """Trigger an agent state change and publish it via Redis."""
    result = await move_agent(
        room_id=room_id,
        agent_external_id=agent_external_id,
        current_state=current_state,
        target_state=target_state,
        new_position=Position(x=x, y=y),
        message=message,
    )
    if result is None:
        raise HTTPException(status_code=400, detail=f"Invalid transition {current_state} -> {target_state}")
    return result
```

**Verify:** Restart AI service: `cd ai-service-python && .venv\Scripts\python -m uvicorn main:app --host 0.0.0.0 --port 8000 --app-dir .` — log should show "Agent brain starting"

---

### Task 2.5 — Add BACKEND_URL to ai-service .env

**Edit:** `ai-service-python/.env` — add:

```
BACKEND_URL=http://localhost:8080
```

**Edit:** `ai-service-python/.env.example` — add:

```
BACKEND_URL=http://localhost:8080
```

**Verify:** Value is loaded

---

### Task 2.6 — Cache room agents in Redis on room creation (Java side)

When a room is created, cache the agent list in Redis so the Python brain can pick agents without calling the backend.

**Edit:** `backend-java/src/main/java/com/craboffice/backend/service/RoomService.java`

Add `StringRedisTemplate` injection and cache agents after save:

```java
// Add field:
private final StringRedisTemplate redisTemplate;

// In createRoom(), after `room = roomRepository.save(room);`:
try {
    List<Map<String, Object>> agentList = room.getAgents().stream()
            .map(a -> Map.<String, Object>of(
                    "externalId", a.getExternalId(),
                    "name", a.getName(),
                    "role", a.getRole()))
            .toList();
    redisTemplate.opsForValue().set(
            "crab:room:" + room.getId() + ":agents",
            objectMapper.writeValueAsString(agentList),
            java.time.Duration.ofMinutes(60));
} catch (Exception e) {
    log.warn("Failed to cache agents in Redis", e);
}
```

**Verify:** Backend compiles. Create a new room, check Redis: `redis-cli GET crab:room:1:agents`

---

### Task 2.7 — End-to-end chat test

1. Create a room via the frontend
2. Open the chat panel
3. Type "Hello, what are you working on?"
4. Verify: message appears in the chat → agent responds via LLM → response appears in the chat

**Verify manually:** Full round trip works: User → Frontend → Java → Redis → Python brain → LLM → Redis → Java → DB → WebSocket → Frontend

---

## Stage 3: Telegram Bot

### Task 3.1 — Add aiogram to requirements.txt

**Edit:** `ai-service-python/requirements.txt` — add:

```
aiogram==3.20.*
```

**Run:** `cd ai-service-python && .venv\Scripts\pip install -r requirements.txt`

**Verify:** `cd ai-service-python && .venv\Scripts\python -c "import aiogram; print(aiogram.__version__)"`

---

### Task 3.2 — Add Telegram config to .env

**Edit:** `ai-service-python/.env` — add:

```
TELEGRAM_BOT_TOKEN=
TELEGRAM_DEFAULT_ROOM_ID=1
```

**Edit:** `ai-service-python/.env.example` — add:

```
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_DEFAULT_ROOM_ID=1
```

---

### Task 3.3 — Create Telegram bot

**File:** `ai-service-python/telegram_bot.py`

```python
"""Telegram bot — bridges Telegram ↔ Crab Office chat via Redis."""

from __future__ import annotations

import asyncio
import json
import logging
import os

import redis.asyncio as aioredis
from aiogram import Bot, Dispatcher, types
from aiogram.filters import CommandStart

logger = logging.getLogger(__name__)

REDIS_INBOUND = "crab:chat-inbound"
REDIS_OUTBOUND = "crab:chat-outbound"

# Map telegram chat_id → room_id (simple mapping for now)
_chat_room_map: dict[int, int] = {}


def _get_redis() -> aioredis.Redis:
    return aioredis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"))


def _get_default_room_id() -> int:
    return int(os.getenv("TELEGRAM_DEFAULT_ROOM_ID", "1"))


bot: Bot | None = None
dp = Dispatcher()


@dp.message(CommandStart())
async def cmd_start(message: types.Message) -> None:
    room_id = _get_default_room_id()
    _chat_room_map[message.chat.id] = room_id
    await message.answer(
        f"🦀 Crab Office подключён!\n"
        f"Вы в комнате #{room_id}. Пишите сообщения — агенты ответят."
    )


@dp.message()
async def handle_message(message: types.Message) -> None:
    if not message.text:
        return

    chat_id = message.chat.id
    room_id = _chat_room_map.get(chat_id, _get_default_room_id())

    # Publish user message to Redis (same channel as Java backend)
    outbound = {
        "roomId": room_id,
        "senderType": "USER",
        "content": message.text,
        "createdAt": message.date.isoformat() if message.date else None,
    }

    r = _get_redis()
    await r.publish(REDIS_INBOUND, json.dumps(outbound))
    await r.aclose()


async def _relay_agent_responses(bot_instance: Bot) -> None:
    """Subscribe to crab:chat-outbound and relay agent responses to Telegram."""
    r = _get_redis()
    pubsub = r.pubsub()
    await pubsub.subscribe(REDIS_OUTBOUND)

    try:
        async for raw in pubsub.listen():
            if raw["type"] != "message":
                continue
            data = raw["data"]
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            try:
                msg = json.loads(data)
                room_id = msg.get("roomId")
                agent_name = msg.get("agentExternalId", "Agent")
                content = msg.get("content", "")

                # Find all Telegram chats linked to this room
                for chat_id, rid in _chat_room_map.items():
                    if rid == room_id:
                        await bot_instance.send_message(
                            chat_id,
                            f"🤖 *{agent_name}*:\n{content}",
                            parse_mode="Markdown",
                        )
            except Exception:
                logger.exception("Failed to relay agent response to Telegram")
    except asyncio.CancelledError:
        pass
    finally:
        await pubsub.unsubscribe(REDIS_OUTBOUND)
        await r.aclose()


async def run_telegram_bot() -> None:
    """Start the Telegram bot with polling."""
    token = os.getenv("TELEGRAM_BOT_TOKEN", "")
    if not token:
        logger.warning("TELEGRAM_BOT_TOKEN not set — Telegram bot disabled")
        return

    global bot
    bot = Bot(token=token)
    relay_task = asyncio.create_task(_relay_agent_responses(bot))

    try:
        logger.info("Telegram bot starting (polling mode)")
        await dp.start_polling(bot)
    finally:
        relay_task.cancel()
        try:
            await relay_task
        except asyncio.CancelledError:
            pass
        await bot.session.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from pathlib import Path
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().with_name(".env"))
    asyncio.run(run_telegram_bot())
```

**Verify:** `cd ai-service-python && .venv\Scripts\python -c "from telegram_bot import run_telegram_bot; print('import ok')"`

---

### Task 3.4 — Add Telegram bot to FastAPI lifespan

**Edit:** `ai-service-python/main.py`

Add Telegram bot startup alongside the agent brain in the lifespan:

```python
from telegram_bot import run_telegram_bot

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start the agent brain and Telegram bot on startup."""
    brain_task = asyncio.create_task(brain_loop())
    telegram_task = asyncio.create_task(run_telegram_bot())
    yield
    brain_task.cancel()
    telegram_task.cancel()
    for task in [brain_task, telegram_task]:
        try:
            await task
        except asyncio.CancelledError:
            pass
```

**Verify:** Restart AI service — logs should show both "Agent brain starting" and either "Telegram bot starting" or "TELEGRAM_BOT_TOKEN not set"

---

### Task 3.5 — Test Telegram integration

1. Create a bot via @BotFather, get the token
2. Set `TELEGRAM_BOT_TOKEN` in `.env`
3. Restart AI service
4. Send `/start` to the bot in Telegram
5. Send a message — verify agent responds

**Verify:** Bidirectional: Telegram message → appears in frontend chat; frontend chat → does NOT relay to Telegram (by design, only agent responses relay)

---

## Stage 4: Orchestration

### Task 4.1 — Create TaskEntity

**File:** `backend-java/src/main/java/com/craboffice/backend/entity/TaskEntity.java`

```java
package com.craboffice.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "tasks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    private String assignedAgentExternalId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String status; // PENDING, IN_PROGRESS, DONE, FAILED

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;
}
```

**Verify:** Backend compiles

---

### Task 4.2 — Create TaskRepository and TaskDto

**File:** `backend-java/src/main/java/com/craboffice/backend/repository/TaskRepository.java`

```java
package com.craboffice.backend.repository;

import com.craboffice.backend.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {
    List<TaskEntity> findByRoomIdOrderByCreatedAtDesc(Long roomId);
}
```

**File:** `backend-java/src/main/java/com/craboffice/backend/dto/TaskDto.java`

```java
package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskDto {
    private Long id;
    private Long roomId;
    private String assignedAgentExternalId;
    private String title;
    private String description;
    private String status;
    private String createdAt;
    private String updatedAt;
}
```

**Verify:** Backend compiles

---

### Task 4.3 — Create TaskService and TaskController

**File:** `backend-java/src/main/java/com/craboffice/backend/service/TaskService.java`

```java
package com.craboffice.backend.service;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.entity.TaskEntity;
import com.craboffice.backend.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskDto createTask(Long roomId, String title, String description, String agentExternalId) {
        TaskEntity entity = TaskEntity.builder()
                .roomId(roomId)
                .title(title)
                .description(description)
                .assignedAgentExternalId(agentExternalId)
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        entity = taskRepository.save(entity);
        TaskDto dto = toDto(entity);

        messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/tasks", dto);
        return dto;
    }

    public TaskDto updateStatus(Long taskId, String status) {
        TaskEntity entity = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));
        entity.setStatus(status);
        entity.setUpdatedAt(Instant.now());
        entity = taskRepository.save(entity);
        TaskDto dto = toDto(entity);

        messagingTemplate.convertAndSend("/topic/rooms/" + entity.getRoomId() + "/tasks", dto);
        return dto;
    }

    public List<TaskDto> getTasks(Long roomId) {
        return taskRepository.findByRoomIdOrderByCreatedAtDesc(roomId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private TaskDto toDto(TaskEntity e) {
        return TaskDto.builder()
                .id(e.getId())
                .roomId(e.getRoomId())
                .assignedAgentExternalId(e.getAssignedAgentExternalId())
                .title(e.getTitle())
                .description(e.getDescription())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt().toString())
                .updatedAt(e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null)
                .build();
    }
}
```

**File:** `backend-java/src/main/java/com/craboffice/backend/controller/TaskController.java`

```java
package com.craboffice.backend.controller;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/tasks")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public List<TaskDto> getTasks(@PathVariable Long roomId) {
        return taskService.getTasks(roomId);
    }
}
```

**Verify:** Backend compiles

---

### Task 4.4 — Create Router agent in Python

The router decides which agent should respond to a given message based on content analysis.

**File:** `ai-service-python/agent_router.py`

```python
"""Router agent — decides which agent handles a given message."""

from __future__ import annotations

import json
import logging
import os

from openai import AsyncOpenAI

logger = logging.getLogger(__name__)

ROUTER_PROMPT = """\
You are a message router in Crab Office. Given a user message and a list of available agents with their roles, decide which agent should respond.

Available agents:
{agents_json}

Reply with ONLY a JSON object: {{"agentExternalId": "<id>", "reason": "<short reason>"}}
If the message is general and any agent could respond, pick the manager.
"""


async def route_message(user_message: str, agents: list[dict]) -> dict | None:
    """Pick the best agent to respond to the user message."""
    if not agents:
        return None

    if len(agents) == 1:
        return agents[0]

    api_key = os.getenv("OPENROUTER_API_KEY", "")
    if not api_key:
        # Without LLM, just return the first agent
        return agents[0]

    agents_summary = [
        {"externalId": a.get("externalId"), "name": a.get("name"), "role": a.get("role")}
        for a in agents
    ]

    try:
        from architect import _get_client, _get_model_name
        client = _get_client()
        model = _get_model_name()

        resp = await client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": ROUTER_PROMPT.format(agents_json=json.dumps(agents_summary))},
                {"role": "user", "content": user_message},
            ],
            temperature=0.3,
            max_tokens=128,
        )
        raw = (resp.choices[0].message.content or "").strip()
        raw = raw.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
        decision = json.loads(raw)
        chosen_id = decision.get("agentExternalId")
        for a in agents:
            if a.get("externalId") == chosen_id:
                logger.info("Router picked %s (reason: %s)", a.get("name"), decision.get("reason"))
                return a
    except Exception:
        logger.exception("Router LLM call failed — defaulting to first agent")

    return agents[0]
```

**Verify:** `cd ai-service-python && .venv\Scripts\python -c "from agent_router import route_message; print('import ok')"`

---

### Task 4.5 — Integrate router into agent brain

**Edit:** `ai-service-python/agent_brain.py`

Replace `_pick_responding_agent` with the router:

```python
from agent_router import route_message

async def _pick_responding_agent(agents: list[dict], user_message: str) -> dict | None:
    """Use the router agent to pick the best responder."""
    return await route_message(user_message, agents)
```

**Verify:** Restart AI service, send a message — the router should pick the best agent and log its decision

---

### Task 4.6 — Add Task type to frontend

**Edit:** `frontend-nextjs/src/types.ts` — add:

```typescript
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
```

**Verify:** TypeScript compiles

---

### Task 4.7 — Create TaskPanel component

**File:** `frontend-nextjs/src/components/TaskPanel.tsx`

```tsx
"use client";

import type { Task } from "@/types";

const statusColors: Record<string, string> = {
  PENDING: "bg-gray-600",
  IN_PROGRESS: "bg-blue-600",
  DONE: "bg-green-600",
  FAILED: "bg-red-600",
};

const statusLabels: Record<string, string> = {
  PENDING: "⏳ Ожидание",
  IN_PROGRESS: "🔄 В работе",
  DONE: "✅ Готово",
  FAILED: "❌ Ошибка",
};

interface TaskPanelProps {
  tasks: Task[];
}

export default function TaskPanel({ tasks }: TaskPanelProps) {
  if (tasks.length === 0) return null;

  return (
    <div className="w-full max-w-lg bg-gray-900 border border-gray-700 rounded-lg overflow-hidden">
      <div className="px-3 py-2 bg-gray-800 text-sm font-medium text-gray-300 border-b border-gray-700">
        📋 Задачи
      </div>
      <div className="p-3 space-y-2 max-h-48 overflow-y-auto">
        {tasks.map((t) => (
          <div key={t.id} className="flex items-center gap-2 text-sm">
            <span className={`px-2 py-0.5 rounded text-xs text-white ${statusColors[t.status] || "bg-gray-600"}`}>
              {statusLabels[t.status] || t.status}
            </span>
            <span className="text-gray-200 truncate">{t.title}</span>
            {t.assignedAgentExternalId && (
              <span className="text-xs text-gray-500 ml-auto">{t.assignedAgentExternalId}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
```

**Verify:** TypeScript compiles

---

### Task 4.8 — Wire TaskPanel into page.tsx

**Edit:** `frontend-nextjs/src/app/page.tsx`

1. Import `TaskPanel` and `Task`
2. Add `tasks` state
3. Subscribe to `/topic/rooms/{roomId}/tasks` in `useSocket` (or fetch via API)
4. Render `<TaskPanel>` below `<ChatPanel>`

```diff
+ import TaskPanel from "@/components/TaskPanel";
+ const [tasks, setTasks] = useState<Task[]>([]);

  // In useSocket or after room creation, fetch tasks:
+ const taskRes = await fetch(`${API_URL}/api/rooms/${data.id}/tasks`);
+ if (taskRes.ok) setTasks(await taskRes.json());

  // In JSX:
+ {room && <TaskPanel tasks={tasks} />}
```

**Verify:** Page renders, task panel shows (empty initially until agents create tasks)

---

## Execution Order Summary

| Order | Task | Service | Type |
|-------|------|---------|------|
| 1 | 1.1 MessageEntity | Java | Create |
| 2 | 1.2 MessageRepository | Java | Create |
| 3 | 1.3 MessageDto + SendMessageRequest | Java | Create |
| 4 | 1.4 ChatService | Java | Create |
| 5 | 1.5 ChatController | Java | Create |
| 6 | 1.6 ChatDispatcher | Java | Create |
| 7 | 1.7 Message type | Frontend | Edit |
| 8 | 1.8 useSocket update | Frontend | Edit |
| 9 | 1.9 ChatPanel | Frontend | Create |
| 10 | 1.10 Wire ChatPanel into page | Frontend | Edit |
| 11 | 2.1 ChatMessage model | Python | Edit |
| 12 | 2.2 Agent prompts | Python | Create |
| 13 | 2.3 Agent brain loop | Python | Create |
| 14 | 2.4 Brain startup in lifespan | Python | Edit |
| 15 | 2.5 BACKEND_URL in .env | Python | Edit |
| 16 | 2.6 Cache agents in Redis | Java | Edit |
| 17 | 2.7 E2E chat test | All | Test |
| 18 | 3.1 aiogram dependency | Python | Edit |
| 19 | 3.2 Telegram .env config | Python | Edit |
| 20 | 3.3 Telegram bot | Python | Create |
| 21 | 3.4 Telegram in lifespan | Python | Edit |
| 22 | 3.5 Telegram test | All | Test |
| 23 | 4.1 TaskEntity | Java | Create |
| 24 | 4.2 TaskRepository + TaskDto | Java | Create |
| 25 | 4.3 TaskService + TaskController | Java | Create |
| 26 | 4.4 Router agent | Python | Create |
| 27 | 4.5 Integrate router into brain | Python | Edit |
| 28 | 4.6 Task type frontend | Frontend | Edit |
| 29 | 4.7 TaskPanel component | Frontend | Create |
| 30 | 4.8 Wire TaskPanel into page | Frontend | Edit |
