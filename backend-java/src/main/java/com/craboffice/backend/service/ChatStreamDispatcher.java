package com.craboffice.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges Redis Pub/Sub channel {@code crab:chat-stream} → STOMP
 * topic {@code /topic/rooms/{roomId}/chat-stream} so the frontend
 * can render assistant text progressively.
 *
 * Payload shape: { roomId, agentExternalId, chunk, timestamp }
 */
@Slf4j
@Component
public class ChatStreamDispatcher implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public ChatStreamDispatcher(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = objectMapper.readTree(message.getBody());
            Long roomId = node.path("roomId").asLong();
            String destination = "/topic/rooms/" + roomId + "/chat-stream";
            messagingTemplate.convertAndSend(destination, node);
            log.debug("Chat stream chunk → {}", destination);
        } catch (Exception e) {
            log.error("Failed to process chat-stream from Redis", e);
        }
    }
}
