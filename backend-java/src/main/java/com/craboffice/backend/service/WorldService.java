package com.craboffice.backend.service;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.LocationEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.RoomRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class WorldService {

    private final RoomRepository roomRepository;
    private final TaskService taskService;
    private final SimulationEventService simulationEventService;
    private final ObjectMapper objectMapper;

    public WorldService(RoomRepository roomRepository,
                        TaskService taskService,
                        SimulationEventService simulationEventService,
                        ObjectMapper objectMapper) {
        this.roomRepository = roomRepository;
        this.taskService = taskService;
        this.simulationEventService = simulationEventService;
        this.objectMapper = objectMapper;
    }

    public WorldResponse getWorld(Long roomId) {
        RoomEntity room = roomRepository.findWithAgentsAndLocationsById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        List<TaskDto> tasks = taskService.getTasks(roomId);

        WorldResponse response = new WorldResponse();
        response.setRoomId(room.getId());
        response.setRoomName(room.getRoomName());
        response.setTheme(room.getTheme());
        response.setLocations(room.getLocations().stream()
                .sorted(java.util.Comparator.comparingInt(LocationEntity::getSortOrder))
                .map(this::toLocationDto)
                .toList());
        response.setAgents(room.getAgents().stream().map(agent -> {
            WorldResponse.AgentDto dto = new WorldResponse.AgentDto();
            dto.setExternalId(agent.getExternalId());
            dto.setName(agent.getName());
            dto.setRole(agent.getRole());
            dto.setSpriteKey(agent.getSpriteKey());
            dto.setLocationId(agent.getLocationId());
            dto.setX(agent.getPosX());
            dto.setY(agent.getPosY());
            dto.setState(agent.getState());
            dto.setStatusText(agent.getStatusText());
            dto.setCurrentTaskId(agent.getCurrentTaskId());
            dto.setTargetAgentExternalId(agent.getTargetAgentExternalId());
            return dto;
        }).toList());
        response.setTasks(tasks);
        response.setRecentEvents(simulationEventService.getRecentEvents(roomId));
        return response;
    }

    private WorldResponse.LocationDto toLocationDto(LocationEntity location) {
        WorldResponse.LocationDto dto = new WorldResponse.LocationDto();
        dto.setId(location.getLocationKey());
        dto.setName(location.getName());
        dto.setKind(location.getKind());
        dto.setWidth(location.getWidth());
        dto.setHeight(location.getHeight());
        dto.setBackgroundPreset(location.getBackgroundPreset());
        dto.setSpawnPoints(parsePoints(location.getSpawnPointsJson()));
        dto.setInteractionPoints(parsePoints(location.getInteractionPointsJson()));
        dto.setSortOrder(location.getSortOrder());
        return dto;
    }

    private List<WorldResponse.PointDto> parsePoints(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse location points", e);
        }
    }
}
