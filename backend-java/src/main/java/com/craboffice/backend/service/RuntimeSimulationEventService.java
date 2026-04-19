package com.craboffice.backend.service;

import com.craboffice.backend.dto.PublishSimulationEventRequest;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeSimulationEventService {

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "web_research_started",
            "web_research_finished",
            "agent.interaction_started",
            "agent.interaction_finished"
    );

    private final AgentRepository agentRepository;
    private final SimulationEventService simulationEventService;

    @Transactional
    public WorldResponse.SimulationEventDto publish(Long roomId, PublishSimulationEventRequest request) {
        String eventType = requireText(request.getEventType(), "eventType is required");
        if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported simulation event type: " + eventType);
        }

        String agentExternalId = requireText(request.getAgentExternalId(), "agentExternalId is required");
        AgentEntity agent = agentRepository.findByRoomIdAndExternalId(roomId, agentExternalId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentExternalId));

        String state = request.getState();
        if (state != null && !state.isBlank()) {
            agent.setState(state);
        }

        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        Object statusText = payload.get("statusText");
        if (statusText instanceof String text && !text.isBlank()) {
            agent.setStatusText(text);
        }

        return simulationEventService.publish(
                roomId,
                request.getLocationId() != null && !request.getLocationId().isBlank() ? request.getLocationId() : agent.getLocationId(),
                agentExternalId,
                eventType,
                state,
                payload,
                request.getCorrelationId(),
                request.getRunId()
        );
    }

    private String requireText(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }
}
