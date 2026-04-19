package com.craboffice.backend.dto;

import lombok.*;

import java.util.Map;

/** Mirrors AgentAction from the Python service — tool invocation events for UI visualization. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentActionDto {
    private Long roomId;
    private String agentExternalId;
    private String actionType;
    private String toolName;
    private String status;
    private Map<String, Object> params;
    private String result;
    private String timestamp;
}
