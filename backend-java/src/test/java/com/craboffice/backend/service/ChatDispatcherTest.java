package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatDispatcherTest {

    @Test
    void forwardsAgentMessageToChatService() throws Exception {
        ChatService chatService = mock(ChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ChatDispatcher dispatcher = new ChatDispatcher(chatService, objectMapper);

        MessageDto dto = MessageDto.builder()
                .roomId(4L)
                .agentExternalId("agent-dev-1")
                .senderType("AGENT")
                .content("hello")
                .build();

        dispatcher.onMessage(
                new DefaultMessage(new byte[0], objectMapper.writeValueAsBytes(dto)),
                null
        );

        verify(chatService).saveAndBroadcastAgentMessage(argThat(message ->
                message != null
                        && Long.valueOf(4L).equals(message.getRoomId())
                        && "agent-dev-1".equals(message.getAgentExternalId())
                        && "AGENT".equals(message.getSenderType())
                        && "hello".equals(message.getContent())
        ));
    }
}