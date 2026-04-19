package com.craboffice.backend.controller;

import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.service.RuntimeSimulationEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SimulationEventController.class)
class SimulationEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeSimulationEventService runtimeSimulationEventService;

    @Test
    void acceptsWebResearchEvent() throws Exception {
        WorldResponse.SimulationEventDto event = new WorldResponse.SimulationEventDto();
        event.setId(77L);
        event.setEventType("web_research_started");
        event.setAgentExternalId("agent-seo-1");
        event.setState("thinking");
        event.setPayload(Map.of("statusText", "Researching the web"));
        event.setTimestamp("2026-04-17T12:00:00Z");

        when(runtimeSimulationEventService.publish(eq(4L), any())).thenReturn(
                event
        );

        mockMvc.perform(post("/api/rooms/4/simulation-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "web_research_started",
                                  "agentExternalId": "agent-seo-1",
                                  "state": "thinking",
                                  "payload": {
                                    "statusText": "Researching the web"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventType").value("web_research_started"))
                .andExpect(jsonPath("$.agentExternalId").value("agent-seo-1"));
    }
}
