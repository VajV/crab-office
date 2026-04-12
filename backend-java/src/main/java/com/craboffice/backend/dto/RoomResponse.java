package com.craboffice.backend.dto;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoomResponse {
    private Long id;
    private String roomName;
    private String theme;
    private LayoutDto layout;
    private List<AgentDto> agents;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LayoutDto {
        private int width;
        private int height;
        private String backgroundPreset;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AgentDto {
        private String externalId;
        private String name;
        private String role;
        private int x;
        private int y;
        private String state;
    }
}
