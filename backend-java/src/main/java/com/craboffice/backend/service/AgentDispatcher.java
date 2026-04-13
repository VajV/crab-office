package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentDispatcher implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public AgentDispatcher(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            AgentEventDto event = objectMapper.readValue(message.getBody(), AgentEventDto.class);
            String destination = "/topic/rooms/" + event.getRoomId();
            messagingTemplate.convertAndSend(destination, event);
            log.info("Dispatched agent event to {}: {}", destination, event.getEventType());
        } catch (Exception e) {
            log.error("Failed to process agent event from Redis", e);
        }
    }

    @Configuration
    static class RedisSubscriptionConfig {
        @Bean
        RedisMessageListenerContainer redisContainer(
                org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory,
                AgentDispatcher dispatcher) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(dispatcher, new ChannelTopic("crab:agent-events"));
            return container;
        }
    }
}
