package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskDto {
    private Long id;
    private Long roomId;
    private String assignedAgentExternalId;
    private String title;
    private String description;
    private String result;
    private String status;
    private String createdAt;
    private String updatedAt;
}
