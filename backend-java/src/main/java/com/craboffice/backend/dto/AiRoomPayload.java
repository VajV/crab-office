package com.craboffice.backend.dto;

import lombok.*;
import java.util.List;

/** Mirrors the JSON returned by the Python AI service. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class AiRoomPayload {
    private String roomName;
    private String theme;
    private Layout layout;
    private List<Agent> agents;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Layout {
        private int width;
        private int height;
        private String backgroundPreset;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Agent {
        private String externalId;
        private String name;
        private String role;
        private Position position;
        private String state;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Position {
        private int x;
        private int y;
    }
}
