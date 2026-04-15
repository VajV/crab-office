package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentActionDto;
import com.craboffice.backend.entity.AgentActionEntity;
import com.craboffice.backend.repository.AgentActionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges Redis Pub/Sub channel {@code crab:agent-actions} → STOMP
 * topic {@code /topic/rooms/{roomId}/actions} so the frontend can
 * visualize agent tool invocations in real time.
 * Also persists every action to PostgreSQL.
 */
@Slf4j
@Component
public class AgentActionDispatcher implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final AgentActionRepository actionRepository;

    public AgentActionDispatcher(SimpMessagingTemplate messagingTemplate,
                                 ObjectMapper objectMapper,
                                 AgentActionRepository actionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.actionRepository = actionRepository;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            AgentActionDto action = objectMapper.readValue(message.getBody(), AgentActionDto.class);

            // Persist to DB
            AgentActionEntity entity = AgentActionEntity.builder()
                    .roomId(action.getRoomId())
                    .agentExternalId(action.getAgentExternalId())
                    .actionType(action.getActionType())
                    .toolName(action.getToolName())
                    .status(action.getStatus())
                    .params(action.getParams() != null ? objectMapper.writeValueAsString(action.getParams()) : null)
                    .result(action.getResult())
                    .build();
            actionRepository.save(entity);

            // Broadcast to STOMP
            String destination = "/topic/rooms/" + action.getRoomId() + "/actions";
            messagingTemplate.convertAndSend(destination, action);
            log.info("Dispatched agent action to {}: {} ({})",
                    destination, action.getToolName(), action.getStatus());
        } catch (Exception e) {
            log.error("Failed to process agent action from Redis", e);
        }
    }
}
