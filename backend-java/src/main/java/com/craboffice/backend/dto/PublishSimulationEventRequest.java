package com.craboffice.backend.dto;

import lombok.*;

import java.util.Map;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PublishSimulationEventRequest {
    private String eventType;
    private String locationId;
    private String agentExternalId;
    private String state;
    @Builder.Default
    private Map<String, Object> payload = Map.of();
    private String correlationId;
    private String runId;
}
