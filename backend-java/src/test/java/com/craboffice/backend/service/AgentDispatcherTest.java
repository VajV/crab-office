package com.craboffice.backend.service;

import com.craboffice.backend.dto.AgentEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentDispatcherTest {

    @Test
    void broadcastsRoomEventToWebSocketTopic() throws Exception {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentDispatcher dispatcher = new AgentDispatcher(messagingTemplate, objectMapper);

        AgentEventDto dto = AgentEventDto.builder()
                .roomId(4L)
                .agentExternalId("agent-dev-1")
                .eventType("AGENT_STATE_CHANGED")
                .state("walking")
                .x(6)
                .y(4)
                .message("move")
                .timestamp("2026-04-13T08:00:00Z")
                .build();

        dispatcher.onMessage(
                new DefaultMessage(new byte[0], objectMapper.writeValueAsBytes(dto)),
                null
        );

        ArgumentCaptor<AgentEventDto> eventCaptor = ArgumentCaptor.forClass(AgentEventDto.class);

        verify(messagingTemplate).convertAndSend(eq("/topic/rooms/4"), eventCaptor.capture());

        AgentEventDto event = eventCaptor.getValue();
        assertNotNull(event);
        assertEquals(4L, event.getRoomId());
        assertEquals("agent-dev-1", event.getAgentExternalId());
        assertEquals("AGENT_STATE_CHANGED", event.getEventType());
        assertEquals("walking", event.getState());
        assertEquals(6, event.getX());
        assertEquals(4, event.getY());
        assertEquals("move", event.getMessage());
    }
}