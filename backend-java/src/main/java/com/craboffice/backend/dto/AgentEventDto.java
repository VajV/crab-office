package com.craboffice.backend.dto;

import lombok.*;

/** Mirrors AgentEvent from the Python service — used for Redis Pub/Sub and WebSocket. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentEventDto {
    private Long roomId;
    private String agentExternalId;
    private String eventType;
    private String state;
    private int x;
    private int y;
    private String message;
    private String timestamp;
}
