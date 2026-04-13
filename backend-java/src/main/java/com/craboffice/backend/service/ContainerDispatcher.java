package com.craboffice.backend.service;

import com.craboffice.backend.dto.ContainerEventDto;
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
public class ContainerDispatcher implements MessageListener {

    private final ContainerService containerService;
    private final ObjectMapper objectMapper;

    public ContainerDispatcher(ContainerService containerService, ObjectMapper objectMapper) {
        this.containerService = containerService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ContainerEventDto event = objectMapper.readValue(message.getBody(), ContainerEventDto.class);
            containerService.saveAndBroadcast(event);
        } catch (Exception e) {
            log.error("Failed to process container event from Redis", e);
        }
    }

    @Configuration
    static class ContainerRedisSubscriptionConfig {
        @Bean
        RedisMessageListenerContainer containerRedisContainer(
                RedisConnectionFactory connectionFactory,
                ContainerDispatcher dispatcher) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(dispatcher, new ChannelTopic("crab:container-events"));
            return container;
        }
    }
}
