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
