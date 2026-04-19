package com.craboffice.backend.service;

import com.craboffice.backend.dto.AiRoomPayload;
import com.craboffice.backend.dto.CreateRoomRequest;
import com.craboffice.backend.dto.RoomResponse;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.entity.LocationEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final SimulationEventService simulationEventService;
    private final WorldService worldService;

    public RoomService(RoomRepository roomRepository,
                       SimpMessagingTemplate messagingTemplate,
                       ObjectMapper objectMapper,
                       SimulationEventService simulationEventService,
                       WorldService worldService) {
        this.roomRepository = roomRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.simulationEventService = simulationEventService;
        this.worldService = worldService;
    }

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    public RoomResponse createRoom(CreateRoomRequest request) {
        // 1. Call Python AI service
        AiRoomPayload aiPayload = callAiService(request);

        // 2. Persist room and agents
        RoomEntity room = new RoomEntity();
        room.setRoomName(aiPayload.getRoomName());
        room.setTheme(aiPayload.getTheme());
        room.setLayoutWidth(aiPayload.getLayout().getWidth());
        room.setLayoutHeight(aiPayload.getLayout().getHeight());
        room.setBackgroundPreset(aiPayload.getLayout().getBackgroundPreset());

        seedLocations(room);

        for (AiRoomPayload.Agent a : aiPayload.getAgents()) {
            String locationId = defaultLocationForRole(a.getRole());
            AgentEntity agent = new AgentEntity();
            agent.setExternalId(a.getExternalId());
            agent.setName(a.getName());
            agent.setRole(a.getRole());
            agent.setLocationId(locationId);
            agent.setSpriteKey(defaultSpriteForRole(a.getRole()));
            agent.setPosX(a.getPosition().getX());
            agent.setPosY(a.getPosition().getY());
            agent.setState(a.getState());
            agent.setStatusText(defaultStatusForState(a.getState()));
            agent.setRoom(room);
            room.getAgents().add(agent);
        }

        room = roomRepository.save(room);

        // 3. Build response
        RoomResponse response = toResponse(room);

        for (AgentEntity agent : room.getAgents()) {
            simulationEventService.publish(
                    room.getId(),
                    agent.getLocationId(),
                    agent.getExternalId(),
                    "agent.spawned",
                    agent.getState(),
                    Map.of(
                            "agent", Map.of(
                                    "externalId", agent.getExternalId(),
                                    "name", agent.getName(),
                                    "role", agent.getRole(),
                                    "spriteKey", agent.getSpriteKey(),
                                    "locationId", agent.getLocationId(),
                                    "x", agent.getPosX(),
                                    "y", agent.getPosY(),
                                    "state", agent.getState()
                            )
                    ),
                    null,
                    null
            );
        }

        // 4. Notify frontend via WebSocket
        messagingTemplate.convertAndSend("/topic/rooms/" + room.getId(), response);
        WorldResponse worldResponse = worldService.getWorld(room.getId());
        messagingTemplate.convertAndSend("/topic/rooms/" + room.getId() + "/world", worldResponse);

        return response;
    }

    public RoomResponse getRoom(Long id) {
        RoomEntity room = roomRepository.findWithAgentsById(id)
                .orElseThrow(() -> new RuntimeException("Room not found: " + id));
        return toResponse(room);
    }

    private AiRoomPayload callAiService(CreateRoomRequest request) {
        try {
            String jsonBody = objectMapper.writeValueAsString(request);
            log.info("Calling AI service with body: {}", jsonBody);
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("AI service returned " + httpResponse.statusCode() + ": " + httpResponse.body());
            }
            return objectMapper.readValue(httpResponse.body(), AiRoomPayload.class);
        } catch (Exception e) {
            log.warn("AI service call failed, using local fallback room", e);
            return buildFallbackRoom(request);
        }
    }

    private AiRoomPayload buildFallbackRoom(CreateRoomRequest request) {
        String preset = request.getPreset() == null || request.getPreset().isBlank()
                ? "tech"
                : request.getPreset().trim().toLowerCase();

        List<AiRoomPayload.Agent> agents = new ArrayList<>();
        agents.add(new AiRoomPayload.Agent(
                "agent-architect-1",
                "Crab Architect",
                "architect",
                new AiRoomPayload.Position(2, 3),
                "idle"
        ));
        agents.add(new AiRoomPayload.Agent(
                "agent-dev-1",
                "Crab Developer",
                "developer",
                new AiRoomPayload.Position(5, 4),
                "idle"
        ));
        agents.add(new AiRoomPayload.Agent(
                "agent-analyst-1",
                "Crab Analyst",
                "analyst",
                new AiRoomPayload.Position(8, 2),
                "idle"
        ));
        agents.add(new AiRoomPayload.Agent(
                "agent-manager-1",
                "Crab Manager",
                "manager",
                new AiRoomPayload.Position(10, 6),
                "idle"
        ));

        return new AiRoomPayload(
                buildFallbackRoomName(preset),
                preset,
                new AiRoomPayload.Layout(12, 8, preset),
                agents
        );
    }

    private String buildFallbackRoomName(String preset) {
        if (preset == null || preset.isBlank()) {
            return "Fallback Office";
        }
        String normalized = preset.substring(0, 1).toUpperCase() + preset.substring(1);
        return normalized + " Office";
    }

    private void seedLocations(RoomEntity room) {
        room.getLocations().add(buildLocation(
                room,
                "marketing-room",
                "Marketing",
                "marketing",
                12,
                8,
                "marketing-loft",
                jsonPoints(List.of(Map.of("x", 2, "y", 5))),
                jsonPoints(List.of(Map.of("x", 7, "y", 4, "kind", "meeting-desk"))),
                1
        ));
        room.getLocations().add(buildLocation(
                room,
                "engineering-room",
                "Engineering",
                "engineering",
                12,
                8,
                "engineering-lab",
                jsonPoints(List.of(Map.of("x", 2, "y", 4))),
                jsonPoints(List.of(Map.of("x", 8, "y", 3, "kind", "whiteboard"))),
                2
        ));
        room.getLocations().add(buildLocation(
                room,
                "ops-room",
                "Operations",
                "operations",
                12,
                8,
                "ops-hub",
                jsonPoints(List.of(Map.of("x", 3, "y", 6))),
                jsonPoints(List.of(Map.of("x", 9, "y", 2, "kind", "console"))),
                3
        ));
    }

    private LocationEntity buildLocation(RoomEntity room,
                                         String key,
                                         String name,
                                         String kind,
                                         int width,
                                         int height,
                                         String backgroundPreset,
                                         String spawnPointsJson,
                                         String interactionPointsJson,
                                         int sortOrder) {
        LocationEntity location = new LocationEntity();
        location.setLocationKey(key);
        location.setName(name);
        location.setKind(kind);
        location.setWidth(width);
        location.setHeight(height);
        location.setBackgroundPreset(backgroundPreset);
        location.setSpawnPointsJson(spawnPointsJson);
        location.setInteractionPointsJson(interactionPointsJson);
        location.setSortOrder(sortOrder);
        location.setRoom(room);
        return location;
    }

    private String jsonPoints(List<Map<String, Object>> points) {
        try {
            return objectMapper.writeValueAsString(points);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize location points", e);
        }
    }

    private String defaultLocationForRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase();
        return switch (normalized) {
            case "seo", "copywriter", "analyst", "designer" -> "marketing-room";
            case "developer", "architect", "qa" -> "engineering-room";
            default -> "ops-room";
        };
    }

    private String defaultSpriteForRole(String role) {
        String normalized = role == null ? "worker" : role.trim().toLowerCase();
        return normalized + "-sprite";
    }

    private String defaultStatusForState(String state) {
        if (state == null || state.isBlank()) {
            return "Waiting for assignment";
        }
        return switch (state.toLowerCase()) {
            case "thinking" -> "Analyzing task";
            case "walking" -> "Moving through the office";
            case "working" -> "Working on assigned task";
            case "talking" -> "Talking to another agent";
            case "waiting" -> "Waiting for coordination";
            default -> "Waiting for assignment";
        };
    }

    private RoomResponse toResponse(RoomEntity room) {
        RoomResponse.LayoutDto layoutDto = new RoomResponse.LayoutDto();
        layoutDto.setWidth(room.getLayoutWidth());
        layoutDto.setHeight(room.getLayoutHeight());
        layoutDto.setBackgroundPreset(room.getBackgroundPreset());

        List<RoomResponse.AgentDto> agents = room.getAgents().stream().map(a -> {
            RoomResponse.AgentDto dto = new RoomResponse.AgentDto();
            dto.setExternalId(a.getExternalId());
            dto.setName(a.getName());
            dto.setRole(a.getRole());
            dto.setX(a.getPosX());
            dto.setY(a.getPosY());
            dto.setState(a.getState());
            return dto;
        }).toList();

        RoomResponse response = new RoomResponse();
        response.setId(room.getId());
        response.setRoomName(room.getRoomName());
        response.setTheme(room.getTheme());
        response.setLayout(layoutDto);
        response.setAgents(agents);
        return response;
    }
}
