package com.craboffice.backend.service;

import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.entity.SimulationEventEntity;
import com.craboffice.backend.repository.SimulationEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class SimulationEventService {

    private static final Logger log = LoggerFactory.getLogger(SimulationEventService.class);

    private final SimulationEventRepository simulationEventRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public SimulationEventService(SimulationEventRepository simulationEventRepository,
                                  SimpMessagingTemplate messagingTemplate,
                                  ObjectMapper objectMapper) {
        this.simulationEventRepository = simulationEventRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    public WorldResponse.SimulationEventDto publish(Long roomId,
                                                    String locationId,
                                                    String agentExternalId,
                                                    String eventType,
                                                    String state,
                                                    Map<String, Object> payload,
                                                    String correlationId,
                                                    String runId) {
        try {
            SimulationEventEntity entity = new SimulationEventEntity();
            entity.setRoomId(roomId);
            entity.setLocationId(locationId);
            entity.setAgentExternalId(agentExternalId);
            entity.setEventType(eventType);
            entity.setState(state);
            entity.setPayloadJson(objectMapper.writeValueAsString(payload == null ? Collections.emptyMap() : payload));
            entity.setCorrelationId(correlationId);
            entity.setRunId(runId);
            entity.setCreatedAt(Instant.now());
            entity = simulationEventRepository.save(entity);

            WorldResponse.SimulationEventDto dto = toDto(entity);
            messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/events", dto);
            return dto;
        } catch (Exception e) {
            log.error("Failed to persist simulation event {} for room {}", eventType, roomId, e);
            throw new RuntimeException("Failed to persist simulation event", e);
        }
    }

    public List<WorldResponse.SimulationEventDto> getRecentEvents(Long roomId) {
        return simulationEventRepository.findTop30ByRoomIdOrderByCreatedAtDesc(roomId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private WorldResponse.SimulationEventDto toDto(SimulationEventEntity entity) {
        try {
            Map<String, Object> payload = objectMapper.readValue(entity.getPayloadJson(), new TypeReference<>() {});
            WorldResponse.SimulationEventDto dto = new WorldResponse.SimulationEventDto();
            dto.setId(entity.getId());
            dto.setEventType(entity.getEventType());
            dto.setLocationId(entity.getLocationId());
            dto.setAgentExternalId(entity.getAgentExternalId());
            dto.setState(entity.getState());
            dto.setPayload(payload);
            dto.setCorrelationId(entity.getCorrelationId());
            dto.setRunId(entity.getRunId());
            dto.setTimestamp(entity.getCreatedAt().toString());
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize simulation event payload", e);
        }
    }
}
