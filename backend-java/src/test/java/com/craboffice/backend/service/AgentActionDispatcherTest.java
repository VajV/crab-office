package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentActionDto;
import com.craboffice.backend.repository.AgentActionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentActionDispatcherTest {

    @Test
    void dispatchesActionToCorrectStompTopic() throws Exception {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentActionRepository actionRepository = mock(AgentActionRepository.class);
        AgentActionDispatcher dispatcher = new AgentActionDispatcher(messagingTemplate, objectMapper, actionRepository);

        AgentActionDto dto = AgentActionDto.builder()
                .roomId(4L)
                .agentExternalId("agent-dev-1")
                .actionType("tool_call")
                .toolName("write_file")
                .status("started")
                .params(Map.of("path", "hello.py"))
                .timestamp("2026-04-13T10:00:00Z")
                .build();

        dispatcher.onMessage(
                new DefaultMessage(new byte[0], objectMapper.writeValueAsBytes(dto)),
                null
        );

        ArgumentCaptor<AgentActionDto> captor = ArgumentCaptor.forClass(AgentActionDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/rooms/4/actions"), captor.capture());

        AgentActionDto captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(4L, captured.getRoomId());
        assertEquals("write_file", captured.getToolName());
        assertEquals("started", captured.getStatus());
        assertEquals("agent-dev-1", captured.getAgentExternalId());
    }
}
