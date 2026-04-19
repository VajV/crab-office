package com.craboffice.backend.service;

import com.craboffice.backend.dto.OpenClawCommandRequest;
import com.craboffice.backend.dto.OpenClawCommandResponse;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.AgentRepository;
import com.craboffice.backend.repository.LocationRepository;
import com.craboffice.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OpenClawCommandService {

    private final RoomRepository roomRepository;
    private final AgentRepository agentRepository;
    private final LocationRepository locationRepository;
    private final SimulationEventService simulationEventService;
    private final WorldService worldService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskService taskService;

    @Transactional
    public OpenClawCommandResponse execute(Long roomId, OpenClawCommandRequest request) {
        String commandType = requireText(request.getCommandType(), "commandType is required");

        return switch (commandType) {
            case "spawn_agent" -> executeSpawnAgent(roomId, request);
            case "set_agent_state" -> executeSetAgentState(roomId, request);
            case "move_agent" -> executeMoveAgent(roomId, request);
            case "start_interaction" -> executeStartInteraction(roomId, request);
            case "finish_interaction" -> executeFinishInteraction(roomId, request);
            case "assign_task" -> executeAssignTask(roomId, request);
            default -> throw new IllegalArgumentException("Unsupported commandType: " + commandType);
        };
    }

    private OpenClawCommandResponse executeSpawnAgent(Long roomId, OpenClawCommandRequest request) {
        RoomEntity room = roomRepository.findWithAgentsAndLocationsById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));

        Map<String, Object> payload = safePayload(request);
        String role = requireText(asString(payload.get("role")), "spawn_agent requires payload.role");
        String name = asString(payload.get("name"));
        String locationId = asString(payload.get("preferredLocationId"));
        if (locationId == null || locationId.isBlank()) {
            locationId = defaultLocationForRole(role);
        }
        validateLocation(roomId, locationId);

        String externalId = nextExternalId(roomId, role);
        AgentEntity agent = AgentEntity.builder()
                .externalId(externalId)
                .name(name == null || name.isBlank() ? defaultNameForRole(role, externalId) : name)
                .role(role)
                .spriteKey(defaultSpriteForRole(role))
                .locationId(locationId)
                .posX(defaultSpawnX(locationId))
                .posY(defaultSpawnY(locationId))
                .state("idle")
                .statusText("Waiting for assignment")
                .room(room)
                .build();

        agent = agentRepository.save(agent);

        simulationEventService.publish(
                roomId,
                locationId,
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
                                "state", agent.getState(),
                                "statusText", agent.getStatusText()
                        )
                ),
                request.getCorrelationId(),
                request.getRunId()
        );

        return successResponse("spawn_agent", "Agent created", roomId);
    }

    private OpenClawCommandResponse executeSetAgentState(Long roomId, OpenClawCommandRequest request) {
        Map<String, Object> payload = safePayload(request);
        String agentExternalId = requireText(asString(payload.get("agentExternalId")), "set_agent_state requires payload.agentExternalId");
        String state = requireText(asString(payload.get("state")), "set_agent_state requires payload.state");
        String statusText = asString(payload.get("statusText"));

        AgentEntity agent = agentRepository.findByRoomIdAndExternalId(roomId, agentExternalId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentExternalId));

        String fromState = agent.getState();
        agent.setState(state);
        if (statusText != null && !statusText.isBlank()) {
            agent.setStatusText(statusText);
        }
        agentRepository.save(agent);

        simulationEventService.publish(
                roomId,
                agent.getLocationId(),
                agent.getExternalId(),
                "agent.state_changed",
                state,
                Map.of(
                        "fromState", fromState == null ? "" : fromState,
                        "toState", state,
                        "statusText", agent.getStatusText() == null ? "" : agent.getStatusText()
                ),
                request.getCorrelationId(),
                request.getRunId()
        );

        publishLegacyAgentEvent(roomId, agent, state, agent.getPosX(), agent.getPosY(), agent.getStatusText());
        return successResponse("set_agent_state", "Agent state updated", roomId);
    }

    private OpenClawCommandResponse executeMoveAgent(Long roomId, OpenClawCommandRequest request) {
        Map<String, Object> payload = safePayload(request);
        String agentExternalId = requireText(asString(payload.get("agentExternalId")), "move_agent requires payload.agentExternalId");
        String locationId = requireText(asString(payload.get("locationId")), "move_agent requires payload.locationId");
        Integer x = asInteger(payload.get("x"));
        Integer y = asInteger(payload.get("y"));
        String reason = asString(payload.get("reason"));
        String targetAgentExternalId = asString(payload.get("targetAgentExternalId"));

        if (x == null || y == null) {
            throw new IllegalArgumentException("move_agent requires numeric payload.x and payload.y");
        }

        validateLocation(roomId, locationId);

        AgentEntity agent = agentRepository.findByRoomIdAndExternalId(roomId, agentExternalId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentExternalId));

        String fromLocationId = agent.getLocationId();
        int fromX = agent.getPosX();
        int fromY = agent.getPosY();

        agent.setLocationId(locationId);
        agent.setPosX(x);
        agent.setPosY(y);
        agent.setState("walking");
        if (reason != null && !reason.isBlank()) {
            agent.setStatusText(reason);
        }
        agent.setTargetAgentExternalId(targetAgentExternalId);
        agentRepository.save(agent);

        simulationEventService.publish(
                roomId,
                locationId,
                agent.getExternalId(),
                "agent.moved",
                "walking",
                Map.of(
                        "from", Map.of("locationId", fromLocationId == null ? "" : fromLocationId, "x", fromX, "y", fromY),
                        "to", Map.of("locationId", locationId, "x", x, "y", y),
                        "reason", reason == null ? "" : reason,
                        "targetAgentExternalId", targetAgentExternalId == null ? "" : targetAgentExternalId
                ),
                request.getCorrelationId(),
                request.getRunId()
        );

        publishLegacyAgentEvent(roomId, agent, "walking", x, y, reason);
        return successResponse("move_agent", "Agent moved", roomId);
    }

    private OpenClawCommandResponse executeStartInteraction(Long roomId, OpenClawCommandRequest request) {
        Map<String, Object> payload = safePayload(request);
        String initiatorAgentExternalId = requireText(asString(payload.get("agentExternalId")), "start_interaction requires payload.agentExternalId");
        String targetAgentExternalId = requireText(asString(payload.get("targetAgentExternalId")), "start_interaction requires payload.targetAgentExternalId");
        String interactionType = requireText(asString(payload.get("interactionType")), "start_interaction requires payload.interactionType");
        String summary = asString(payload.get("summary"));

        AgentEntity initiator = agentRepository.findByRoomIdAndExternalId(roomId, initiatorAgentExternalId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + initiatorAgentExternalId));
        agentRepository.findByRoomIdAndExternalId(roomId, targetAgentExternalId)
                .orElseThrow(() -> new RuntimeException("Target agent not found: " + targetAgentExternalId));

        initiator.setState("talking");
        initiator.setTargetAgentExternalId(targetAgentExternalId);
        initiator.setStatusText(summary == null || summary.isBlank() ? "Talking to another agent" : summary);
        agentRepository.save(initiator);

        simulationEventService.publish(
                roomId,
                initiator.getLocationId(),
                initiator.getExternalId(),
                "agent.interaction_started",
                "talking",
                Map.of(
                        "targetAgentExternalId", targetAgentExternalId,
                        "interactionType", interactionType,
                        "summary", summary == null ? "" : summary
                ),
                request.getCorrelationId(),
                request.getRunId()
        );

        publishLegacyAgentEvent(roomId, initiator, "talking", initiator.getPosX(), initiator.getPosY(), initiator.getStatusText());
        return successResponse("start_interaction", "Interaction started", roomId);
    }

    private OpenClawCommandResponse executeFinishInteraction(Long roomId, OpenClawCommandRequest request) {
        Map<String, Object> payload = safePayload(request);
        String initiatorAgentExternalId = requireText(asString(payload.get("agentExternalId")), "finish_interaction requires payload.agentExternalId");
        String targetAgentExternalId = requireText(asString(payload.get("targetAgentExternalId")), "finish_interaction requires payload.targetAgentExternalId");
        String interactionType = requireText(asString(payload.get("interactionType")), "finish_interaction requires payload.interactionType");
        String summary = asString(payload.get("summary"));

        AgentEntity initiator = agentRepository.findByRoomIdAndExternalId(roomId, initiatorAgentExternalId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + initiatorAgentExternalId));

        initiator.setState("working");
        initiator.setTargetAgentExternalId(null);
        initiator.setStatusText(summary == null || summary.isBlank() ? "Interaction completed" : summary);
        agentRepository.save(initiator);

        simulationEventService.publish(
                roomId,
                initiator.getLocationId(),
                initiator.getExternalId(),
                "agent.interaction_finished",
                "working",
                Map.of(
                        "targetAgentExternalId", targetAgentExternalId,
                        "interactionType", interactionType,
                        "summary", summary == null ? "" : summary
                ),
                request.getCorrelationId(),
                request.getRunId()
        );

        publishLegacyAgentEvent(roomId, initiator, "working", initiator.getPosX(), initiator.getPosY(), initiator.getStatusText());
        return successResponse("finish_interaction", "Interaction finished", roomId);
    }

    private OpenClawCommandResponse executeAssignTask(Long roomId, OpenClawCommandRequest request) {
        Map<String, Object> payload = safePayload(request);
        String title = requireText(asString(payload.get("title")), "assign_task requires payload.title");
        String description = asString(payload.get("description"));
        String agentExternalId = asString(payload.get("agentExternalId"));
        String role = asString(payload.get("role"));

        AgentEntity agent = resolveOrCreateAgentForTask(roomId, agentExternalId, role, request);
        var task = taskService.createTask(roomId, title, description, agent.getExternalId());

        agent.setCurrentTaskId(task.getId());
        agent.setState("working");
        agent.setStatusText(title);
        agentRepository.save(agent);

        publishLegacyAgentEvent(roomId, agent, "working", agent.getPosX(), agent.getPosY(), title);
        return successResponse("assign_task", "Task assigned", roomId);
    }

    private OpenClawCommandResponse successResponse(String commandType, String message, Long roomId) {
        WorldResponse world = worldService.getWorld(roomId);
        messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/world", world);
        return OpenClawCommandResponse.builder()
                .commandType(commandType)
                .status("accepted")
                .message(message)
                .world(world)
                .build();
    }

    private void publishLegacyAgentEvent(Long roomId,
                                         AgentEntity agent,
                                         String state,
                                         int x,
                                         int y,
                                         String message) {
        messagingTemplate.convertAndSend(
                "/topic/rooms/" + roomId,
                com.craboffice.backend.dto.AgentEventDto.builder()
                        .roomId(roomId)
                        .agentExternalId(agent.getExternalId())
                        .eventType("AGENT_STATE_CHANGED")
                        .state(state)
                        .x(x)
                        .y(y)
                        .message(message)
                        .timestamp(java.time.Instant.now().toString())
                        .build()
        );
    }

    private Map<String, Object> safePayload(OpenClawCommandRequest request) {
        return request.getPayload() == null ? Map.of() : request.getPayload();
    }

    private void validateLocation(Long roomId, String locationId) {
        boolean exists = locationRepository.findByRoomIdOrderBySortOrderAsc(roomId).stream()
                .anyMatch(location -> location.getLocationKey().equals(locationId));
        if (!exists) {
            throw new IllegalArgumentException("Unknown locationId: " + locationId);
        }
    }

    private AgentEntity resolveOrCreateAgentForTask(Long roomId,
                                                    String agentExternalId,
                                                    String role,
                                                    OpenClawCommandRequest request) {
        if (agentExternalId != null && !agentExternalId.isBlank()) {
            return agentRepository.findByRoomIdAndExternalId(roomId, agentExternalId)
                    .orElseThrow(() -> new RuntimeException("Agent not found: " + agentExternalId));
        }

        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("assign_task requires payload.agentExternalId or payload.role");
        }

        return agentRepository.findByRoomIdOrderByIdAsc(roomId).stream()
                .filter(agent -> role.equalsIgnoreCase(agent.getRole()))
                .findFirst()
                .orElseGet(() -> {
                    executeSpawnAgent(roomId, OpenClawCommandRequest.builder()
                            .commandType("spawn_agent")
                            .payload(Map.of("role", role))
                            .correlationId(request.getCorrelationId())
                            .runId(request.getRunId())
                            .build());
                    return agentRepository.findByRoomIdOrderByIdAsc(roomId).stream()
                            .filter(agent -> role.equalsIgnoreCase(agent.getRole()))
                            .reduce((first, second) -> second)
                            .orElseThrow(() -> new RuntimeException("Failed to create agent for role: " + role));
                });
    }

    private String nextExternalId(Long roomId, String role) {
        String normalizedRole = role.trim().toLowerCase().replace(' ', '-');
        long count = agentRepository.findByRoomIdOrderByIdAsc(roomId).stream()
                .filter(agent -> normalizedRole.equals(agent.getRole() == null ? "" : agent.getRole().trim().toLowerCase()))
                .count();
        return "agent-" + normalizedRole + "-" + (count + 1) + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String defaultNameForRole(String role, String externalId) {
        String normalized = role.substring(0, 1).toUpperCase() + role.substring(1).toLowerCase();
        return normalized + " " + externalId.substring(Math.max(0, externalId.length() - 3)).toUpperCase();
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

    private int defaultSpawnX(String locationId) {
        return switch (locationId) {
            case "marketing-room" -> 2;
            case "engineering-room" -> 2;
            default -> 3;
        };
    }

    private int defaultSpawnY(String locationId) {
        return switch (locationId) {
            case "marketing-room" -> 5;
            case "engineering-room" -> 4;
            default -> 6;
        };
    }

    private String requireText(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s);
        }
        return null;
    }
}
