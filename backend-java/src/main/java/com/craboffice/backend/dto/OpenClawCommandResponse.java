package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OpenClawCommandResponse {
    private String commandType;
    private String status;
    private String message;
    private WorldResponse world;
}
