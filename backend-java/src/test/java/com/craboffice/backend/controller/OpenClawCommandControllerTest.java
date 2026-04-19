package com.craboffice.backend.controller;

import com.craboffice.backend.dto.OpenClawCommandResponse;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.service.OpenClawCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OpenClawCommandController.class)
class OpenClawCommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OpenClawCommandService openClawCommandService;

    @Test
    void acceptsSpawnAgentCommand() throws Exception {
        when(openClawCommandService.execute(any(), any())).thenReturn(response("spawn_agent", "Agent created"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "spawn_agent",
                                  "payload": {
                                    "role": "seo",
                                    "name": "SEO-1",
                                    "preferredLocationId": "marketing-room"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("spawn_agent"))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.world.roomId").value(4));
    }

    @Test
    void acceptsSetAgentStateCommand() throws Exception {
        when(openClawCommandService.execute(any(), any())).thenReturn(response("set_agent_state", "Agent state updated"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "set_agent_state",
                                  "payload": {
                                    "agentExternalId": "agent-seo-1",
                                    "state": "thinking",
                                    "statusText": "Analyzing SERP"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("set_agent_state"))
                .andExpect(jsonPath("$.message").value("Agent state updated"));
    }

    @Test
    void acceptsMoveAgentCommand() throws Exception {
        when(openClawCommandService.execute(any(), any())).thenReturn(response("move_agent", "Agent moved"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "move_agent",
                                  "payload": {
                                    "agentExternalId": "agent-seo-1",
                                    "locationId": "marketing-room",
                                    "x": 7,
                                    "y": 5,
                                    "reason": "Going to analyst desk"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("move_agent"))
                .andExpect(jsonPath("$.message").value("Agent moved"));
    }

    @Test
    void returnsBadRequestForInvalidCommand() throws Exception {
        when(openClawCommandService.execute(any(), any()))
                .thenThrow(new IllegalArgumentException("Unsupported commandType: explode"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "explode",
                                  "payload": {}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_command"))
                .andExpect(jsonPath("$.message").value("Unsupported commandType: explode"));
    }

    @Test
    void acceptsStartInteractionCommand() throws Exception {
        when(openClawCommandService.execute(any(), any())).thenReturn(response("start_interaction", "Interaction started"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "start_interaction",
                                  "payload": {
                                    "agentExternalId": "agent-seo-1",
                                    "targetAgentExternalId": "agent-copy-1",
                                    "interactionType": "discussion",
                                    "summary": "Discussing keywords"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("start_interaction"));
    }

    @Test
    void acceptsAssignTaskCommand() throws Exception {
        when(openClawCommandService.execute(any(), any())).thenReturn(response("assign_task", "Task assigned"));

        mockMvc.perform(post("/api/rooms/4/openclaw/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commandType": "assign_task",
                                  "payload": {
                                    "role": "seo",
                                    "title": "Prepare SEO brief",
                                    "description": "Draft keyword plan"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("assign_task"));
    }

    private OpenClawCommandResponse response(String commandType, String message) {
        WorldResponse world = new WorldResponse();
        world.setRoomId(4L);
        world.setRoomName("Creative Office");
        world.setTheme("creative");
        world.setLocations(List.of());
        world.setAgents(List.of());
        world.setTasks(List.of());
        world.setRecentEvents(List.of());

        OpenClawCommandResponse response = new OpenClawCommandResponse();
        response.setCommandType(commandType);
        response.setStatus("accepted");
        response.setMessage(message);
        response.setWorld(world);
        return response;
    }
}
