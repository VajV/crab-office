package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentEventDto;
import com.craboffice.backend.dto.MessageDto;
import com.craboffice.backend.dto.SendMessageRequest;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.entity.MessageEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.MessageRepository;
import com.craboffice.backend.repository.RoomRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatService {

    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final OpenClawService openClawService;
    private final RoomRepository roomRepository;

    public ChatService(MessageRepository messageRepository,
                       SimpMessagingTemplate messagingTemplate,
                       OpenClawService openClawService,
                       RoomRepository roomRepository) {
        this.messageRepository = messageRepository;
        this.messagingTemplate = messagingTemplate;
        this.openClawService = openClawService;
        this.roomRepository = roomRepository;
    }

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

        // Dispatch to OpenClaw in background
        dispatchToOpenClaw(roomId, request.getContent());

        return dto;
    }

    @Async
    public void dispatchToOpenClaw(Long roomId, String userMessage) {
        try {
            List<MessageDto> history = getMessages(roomId);

            // Load room with agents to build context
            RoomEntity room = roomRepository.findWithAgentsById(roomId).orElse(null);

            String systemPrompt = null;
            String managerExternalId = null;
            AgentEntity manager = null;

            if (room != null && !room.getAgents().isEmpty()) {
                String agentDescriptions = room.getAgents().stream()
                        .map(a -> a.getName() + " (" + a.getRole() + ")")
                        .collect(Collectors.joining(", "));

                systemPrompt = "Ты — менеджер офиса '" + room.getRoomName() + "'. " +
                        "Текущая дата: " + Instant.now().toString().substring(0, 10) + ". " +
                        "В комнате работают агенты: " + agentDescriptions + ". " +
                        "Отвечай на вопросы пользователя, координируй работу агентов. " +
                        "Используй актуальную информацию и учитывай текущую дату при ответах.";

                // Find manager agent or fall back to first agent
                manager = room.getAgents().stream()
                        .filter(a -> a.getRole() != null && a.getRole().toLowerCase().contains("manager"))
                        .findFirst()
                        .orElse(room.getAgents().get(0));
                managerExternalId = manager.getExternalId();

                // Agent starts working — move toward center
                broadcastAgentEvent(roomId, manager, "working",
                        clamp(manager.getPosX() + randomStep(), 0, room.getLayoutWidth() - 1),
                        clamp(manager.getPosY() + randomStep(), 0, room.getLayoutHeight() - 1));
            }

            String reply = openClawService.chatViaProxy(roomId, managerExternalId, history, userMessage, systemPrompt);

            if (reply != null && !reply.isBlank()) {
                // Agent is typing the response
                if (manager != null) {
                    broadcastAgentEvent(roomId, manager, "typing", manager.getPosX(), manager.getPosY());
                }

                MessageDto agentMsg = MessageDto.builder()
                        .roomId(roomId)
                        .senderType("AGENT")
                        .agentExternalId(managerExternalId)
                        .content(reply)
                        .build();
                saveAndBroadcastAgentMessage(agentMsg);

                // Agent returns to idle at original position
                if (manager != null) {
                    broadcastAgentEvent(roomId, manager, "idle", manager.getPosX(), manager.getPosY());
                }
            } else {
                log.warn("OpenClaw returned empty response for room {}", roomId);
                if (manager != null) {
                    broadcastAgentEvent(roomId, manager, "idle", manager.getPosX(), manager.getPosY());
                }
            }
        } catch (Exception e) {
            log.error("Failed to dispatch to OpenClaw for room {}", roomId, e);
        }
    }

    private void broadcastAgentEvent(Long roomId, AgentEntity agent, String state, int x, int y) {
        AgentEventDto event = AgentEventDto.builder()
                .roomId(roomId)
                .agentExternalId(agent.getExternalId())
                .eventType("state_change")
                .state(state)
                .x(x)
                .y(y)
                .timestamp(Instant.now().toString())
                .build();
        messagingTemplate.convertAndSend("/topic/rooms/" + roomId, event);
    }

    private int randomStep() {
        return ThreadLocalRandom.current().nextInt(-1, 2); // -1, 0, or 1
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void saveAndBroadcastAgentMessage(MessageDto agentMsg) {
        MessageEntity entity = MessageEntity.builder()
                .roomId(agentMsg.getRoomId())
                .agentExternalId(agentMsg.getAgentExternalId())
                .senderType(agentMsg.getSenderType() != null ? agentMsg.getSenderType() : "AGENT")
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
