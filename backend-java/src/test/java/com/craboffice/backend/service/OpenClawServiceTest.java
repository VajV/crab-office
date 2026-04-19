package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenClawServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void healthCheckReturnsFalseWhenGatewayUnreachable() {
        OpenClawService service = new OpenClawService(
                objectMapper, "http://localhost:19999", "test-token",
                "openclaw", "http://localhost:8000");
        assertFalse(service.isHealthy());
    }

    @Test
    void chatReturnsNullWhenGatewayUnreachable() {
        OpenClawService service = new OpenClawService(
                objectMapper, "http://localhost:19999", "test-token",
                "openclaw", "http://localhost:8000");
        List<MessageDto> history = List.of();
        String result = service.chat(history, "hello", null);
        assertNull(result);
    }
}
