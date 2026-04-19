package com.craboffice.backend.service;

import com.craboffice.backend.dto.OpenClawCommandRequest;
import com.craboffice.backend.dto.OpenClawCommandResponse;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.entity.LocationEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.AgentRepository;
import com.craboffice.backend.repository.LocationRepository;
import com.craboffice.backend.repository.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpenClawCommandServiceTest {

    @Test
    void spawnAgentCreatesAgentAndPublishesWorld() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        LocationRepository locationRepository = mock(LocationRepository.class);
        SimulationEventService simulationEventService = mock(SimulationEventService.class);
        WorldService worldService = mock(WorldService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        TaskService taskService = mock(TaskService.class);

        RoomEntity room = RoomEntity.builder().id(4L).roomName("Creative Office").build();
        when(roomRepository.findWithAgentsAndLocationsById(4L)).thenReturn(Optional.of(room));
        when(locationRepository.findByRoomIdOrderBySortOrderAsc(4L)).thenReturn(List.of(
                LocationEntity.builder().locationKey("marketing-room").build()
        ));
        when(agentRepository.findByRoomIdOrderByIdAsc(4L)).thenReturn(List.of());
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(worldService.getWorld(4L)).thenReturn(emptyWorld(4L));

        OpenClawCommandService service = new OpenClawCommandService(
                roomRepository,
                agentRepository,
                locationRepository,
                simulationEventService,
                worldService,
                messagingTemplate,
                taskService
        );

        OpenClawCommandResponse response = service.execute(4L, OpenClawCommandRequest.builder()
                .commandType("spawn_agent")
                .payload(Map.of("role", "seo", "preferredLocationId", "marketing-room"))
                .build());

        assertEquals("accepted", response.getStatus());
        verify(agentRepository).save(any(AgentEntity.class));
        verify(simulationEventService).publish(eq(4L), eq("marketing-room"), any(), eq("agent.spawned"), eq("idle"), any(), any(), any());
        verify(messagingTemplate).convertAndSend(eq("/topic/rooms/4/world"), any(WorldResponse.class));
    }

    @Test
    void setAgentStateUpdatesExistingAgent() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        LocationRepository locationRepository = mock(LocationRepository.class);
        SimulationEventService simulationEventService = mock(SimulationEventService.class);
        WorldService worldService = mock(WorldService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        TaskService taskService = mock(TaskService.class);

        AgentEntity agent = AgentEntity.builder()
                .externalId("agent-seo-1")
                .locationId("marketing-room")
                .posX(3)
                .posY(5)
                .state("idle")
                .build();

        when(agentRepository.findByRoomIdAndExternalId(4L, "agent-seo-1")).thenReturn(Optional.of(agent));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(worldService.getWorld(4L)).thenReturn(emptyWorld(4L));

        OpenClawCommandService service = new OpenClawCommandService(
                roomRepository,
                agentRepository,
                locationRepository,
                simulationEventService,
                worldService,
                messagingTemplate,
                taskService
        );

        OpenClawCommandResponse response = service.execute(4L, OpenClawCommandRequest.builder()
                .commandType("set_agent_state")
                .payload(Map.of("agentExternalId", "agent-seo-1", "state", "thinking", "statusText", "Analyzing SERP"))
                .build());

        assertEquals("accepted", response.getStatus());
        assertEquals("thinking", agent.getState());
        assertEquals("Analyzing SERP", agent.getStatusText());
        verify(simulationEventService).publish(eq(4L), eq("marketing-room"), eq("agent-seo-1"), eq("agent.state_changed"), eq("thinking"), any(), any(), any());
    }

    @Test
    void moveAgentRejectsUnknownLocation() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        LocationRepository locationRepository = mock(LocationRepository.class);
        SimulationEventService simulationEventService = mock(SimulationEventService.class);
        WorldService worldService = mock(WorldService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        TaskService taskService = mock(TaskService.class);

        when(locationRepository.findByRoomIdOrderBySortOrderAsc(4L)).thenReturn(List.of(
                LocationEntity.builder().locationKey("marketing-room").build()
        ));

        OpenClawCommandService service = new OpenClawCommandService(
                roomRepository,
                agentRepository,
                locationRepository,
                simulationEventService,
                worldService,
                messagingTemplate,
                taskService
        );

        assertThrows(IllegalArgumentException.class, () -> service.execute(4L, OpenClawCommandRequest.builder()
                .commandType("move_agent")
                .payload(Map.of("agentExternalId", "agent-seo-1", "locationId", "unknown-room", "x", 1, "y", 2))
                .build()));
    }

    private WorldResponse emptyWorld(Long roomId) {
        WorldResponse response = new WorldResponse();
        response.setRoomId(roomId);
        response.setLocations(List.of());
        response.setAgents(List.of());
        response.setTasks(List.of());
        response.setRecentEvents(List.of());
        return response;
    }
}
