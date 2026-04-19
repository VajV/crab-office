package com.craboffice.backend.controller;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.service.WorldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorldController.class)
class WorldControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorldService worldService;

    @Test
    void returnsWorldSnapshot() throws Exception {
        WorldResponse.PointDto spawnPoint = new WorldResponse.PointDto();
        spawnPoint.setX(2);
        spawnPoint.setY(5);

        WorldResponse.PointDto interactionPoint = new WorldResponse.PointDto();
        interactionPoint.setX(7);
        interactionPoint.setY(4);
        interactionPoint.setKind("meeting-desk");

        WorldResponse.LocationDto location = new WorldResponse.LocationDto();
        location.setId("marketing-room");
        location.setName("Marketing");
        location.setKind("marketing");
        location.setWidth(12);
        location.setHeight(8);
        location.setBackgroundPreset("marketing-loft");
        location.setSpawnPoints(List.of(spawnPoint));
        location.setInteractionPoints(List.of(interactionPoint));
        location.setSortOrder(1);

        WorldResponse.AgentDto agent = new WorldResponse.AgentDto();
        agent.setExternalId("agent-seo-1");
        agent.setName("SEO-1");
        agent.setRole("seo");
        agent.setSpriteKey("seo-sprite");
        agent.setLocationId("marketing-room");
        agent.setX(3);
        agent.setY(5);
        agent.setState("thinking");
        agent.setStatusText("Analyzing search intent");

        TaskDto task = TaskDto.builder()
                .id(42L)
                .roomId(4L)
                .assignedAgentExternalId("agent-seo-1")
                .title("Prepare SEO brief")
                .status("IN_PROGRESS")
                .createdAt("2026-04-17T12:05:00Z")
                .build();

        WorldResponse.SimulationEventDto event = new WorldResponse.SimulationEventDto();
        event.setId(501L);
        event.setEventType("agent.state_changed");
        event.setLocationId("marketing-room");
        event.setAgentExternalId("agent-seo-1");
        event.setState("thinking");
        event.setPayload(Map.of("statusText", "Analyzing search intent"));
        event.setTimestamp("2026-04-17T12:06:00Z");

        WorldResponse response = new WorldResponse();
        response.setRoomId(4L);
        response.setRoomName("Creative Office");
        response.setTheme("creative");
        response.setLocations(List.of(location));
        response.setAgents(List.of(agent));
        response.setTasks(List.of(task));
        response.setRecentEvents(List.of(event));

        when(worldService.getWorld(4L)).thenReturn(response);

        mockMvc.perform(get("/api/rooms/4/world"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(4))
                .andExpect(jsonPath("$.locations[0].id").value("marketing-room"))
                .andExpect(jsonPath("$.agents[0].locationId").value("marketing-room"))
                .andExpect(jsonPath("$.tasks[0].title").value("Prepare SEO brief"))
                .andExpect(jsonPath("$.recentEvents[0].eventType").value("agent.state_changed"));
    }
}
