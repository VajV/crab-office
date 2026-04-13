package com.craboffice.backend;

import com.craboffice.backend.dto.ContainerEventDto;
import com.craboffice.backend.service.ContainerDispatcher;
import com.craboffice.backend.service.ContainerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContainerDispatcherTest {

    private final ContainerService containerService = mock(ContainerService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContainerDispatcher dispatcher = new ContainerDispatcher(containerService, objectMapper);

    @Test
    void onMessage_parsesAndDelegates() throws Exception {
        ContainerEventDto event = ContainerEventDto.builder()
                .roomId(1L)
                .status("stopped")
                .command("python hello.py")
                .exitCode(0)
                .stdout("Hello!")
                .stderr("")
                .timedOut(false)
                .timestamp("2026-04-13T10:00:00Z")
                .build();

        byte[] body = objectMapper.writeValueAsBytes(event);
        Message message = new DefaultMessage("crab:container-events".getBytes(), body);

        dispatcher.onMessage(message, null);

        verify(containerService).saveAndBroadcast(any(ContainerEventDto.class));
    }

    @Test
    void onMessage_badJson_doesNotThrow() {
        Message message = new DefaultMessage("crab:container-events".getBytes(), "bad json".getBytes());

        dispatcher.onMessage(message, null);

        verify(containerService, never()).saveAndBroadcast(any());
    }
}
