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
