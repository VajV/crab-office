package com.craboffice.backend.dto;

import lombok.*;

import java.util.Map;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OpenClawCommandRequest {
    private String commandType;
    @Builder.Default
    private Map<String, Object> payload = Map.of();
    private String correlationId;
    private String runId;
}
