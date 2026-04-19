package com.craboffice.backend.dto;

import java.util.List;
import java.util.Map;

public class WorldResponse {
    private Long roomId;
    private String roomName;
    private String theme;
    private List<LocationDto> locations;
    private List<AgentDto> agents;
    private List<TaskDto> tasks;
    private List<SimulationEventDto> recentEvents;

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public List<LocationDto> getLocations() {
        return locations;
    }

    public void setLocations(List<LocationDto> locations) {
        this.locations = locations;
    }

    public List<AgentDto> getAgents() {
        return agents;
    }

    public void setAgents(List<AgentDto> agents) {
        this.agents = agents;
    }

    public List<TaskDto> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskDto> tasks) {
        this.tasks = tasks;
    }

    public List<SimulationEventDto> getRecentEvents() {
        return recentEvents;
    }

    public void setRecentEvents(List<SimulationEventDto> recentEvents) {
        this.recentEvents = recentEvents;
    }

    public static class PointDto {
        private int x;
        private int y;
        private String kind;

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }
    }

    public static class LocationDto {
        private String id;
        private String name;
        private String kind;
        private int width;
        private int height;
        private String backgroundPreset;
        private List<PointDto> spawnPoints;
        private List<PointDto> interactionPoints;
        private int sortOrder;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public String getBackgroundPreset() {
            return backgroundPreset;
        }

        public void setBackgroundPreset(String backgroundPreset) {
            this.backgroundPreset = backgroundPreset;
        }

        public List<PointDto> getSpawnPoints() {
            return spawnPoints;
        }

        public void setSpawnPoints(List<PointDto> spawnPoints) {
            this.spawnPoints = spawnPoints;
        }

        public List<PointDto> getInteractionPoints() {
            return interactionPoints;
        }

        public void setInteractionPoints(List<PointDto> interactionPoints) {
            this.interactionPoints = interactionPoints;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    public static class AgentDto {
        private String externalId;
        private String name;
        private String role;
        private String spriteKey;
        private String locationId;
        private int x;
        private int y;
        private String state;
        private String statusText;
        private Long currentTaskId;
        private String targetAgentExternalId;

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getSpriteKey() {
            return spriteKey;
        }

        public void setSpriteKey(String spriteKey) {
            this.spriteKey = spriteKey;
        }

        public String getLocationId() {
            return locationId;
        }

        public void setLocationId(String locationId) {
            this.locationId = locationId;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getStatusText() {
            return statusText;
        }

        public void setStatusText(String statusText) {
            this.statusText = statusText;
        }

        public Long getCurrentTaskId() {
            return currentTaskId;
        }

        public void setCurrentTaskId(Long currentTaskId) {
            this.currentTaskId = currentTaskId;
        }

        public String getTargetAgentExternalId() {
            return targetAgentExternalId;
        }

        public void setTargetAgentExternalId(String targetAgentExternalId) {
            this.targetAgentExternalId = targetAgentExternalId;
        }
    }

    public static class SimulationEventDto {
        private Long id;
        private String eventType;
        private String locationId;
        private String agentExternalId;
        private String state;
        private Map<String, Object> payload;
        private String correlationId;
        private String runId;
        private String timestamp;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public String getLocationId() {
            return locationId;
        }

        public void setLocationId(String locationId) {
            this.locationId = locationId;
        }

        public String getAgentExternalId() {
            return agentExternalId;
        }

        public void setAgentExternalId(String agentExternalId) {
            this.agentExternalId = agentExternalId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public void setPayload(Map<String, Object> payload) {
            this.payload = payload;
        }

        public String getCorrelationId() {
            return correlationId;
        }

        public void setCorrelationId(String correlationId) {
            this.correlationId = correlationId;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
    }
}
