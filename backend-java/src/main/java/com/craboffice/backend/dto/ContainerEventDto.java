package com.craboffice.backend.dto;

import lombok.*;

/** Mirrors ContainerEvent from the Python container_manager — used for Redis Pub/Sub and WebSocket. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContainerEventDto {
    private Long roomId;
    private String status;      // creating | running | stopped | error
    private String command;
    private Integer exitCode;
    private String stdout;
    private String stderr;
    private Boolean timedOut;
    private String timestamp;
}
