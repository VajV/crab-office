package com.craboffice.backend.service;

import com.craboffice.backend.dto.AiRoomPayload;
import com.craboffice.backend.dto.CreateRoomRequest;
import com.craboffice.backend.dto.RoomResponse;
import com.craboffice.backend.entity.AgentEntity;
import com.craboffice.backend.entity.RoomEntity;
import com.craboffice.backend.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private final RoomRepository roomRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Value("${app.ai-service-url}")
    private String aiServiceUrl;

    public RoomResponse createRoom(CreateRoomRequest request) {
        // 1. Call Python AI service
        AiRoomPayload aiPayload = callAiService(request);

        // 2. Persist room and agents
        RoomEntity room = RoomEntity.builder()
                .roomName(aiPayload.getRoomName())
                .theme(aiPayload.getTheme())
                .layoutWidth(aiPayload.getLayout().getWidth())
                .layoutHeight(aiPayload.getLayout().getHeight())
                .backgroundPreset(aiPayload.getLayout().getBackgroundPreset())
                .build();

        for (AiRoomPayload.Agent a : aiPayload.getAgents()) {
            AgentEntity agent = AgentEntity.builder()
                    .externalId(a.getExternalId())
                    .name(a.getName())
                    .role(a.getRole())
                    .posX(a.getPosition().getX())
                    .posY(a.getPosition().getY())
                    .state(a.getState())
                    .room(room)
                    .build();
            room.getAgents().add(agent);
        }

        room = roomRepository.save(room);

        // Cache agent info in Redis for the Python agent brain
        try {
            List<Map<String, Object>> agentList = room.getAgents().stream()
                    .map(a -> Map.<String, Object>of(
                            "externalId", a.getExternalId(),
                            "name", a.getName(),
                            "role", a.getRole()))
                    .toList();
            redisTemplate.opsForValue().set(
                    "crab:room:" + room.getId() + ":agents",
                    objectMapper.writeValueAsString(agentList),
                    java.time.Duration.ofMinutes(60));
        } catch (Exception e) {
            log.warn("Failed to cache agents in Redis", e);
        }

        // 3. Build response
        RoomResponse response = toResponse(room);

        // 4. Notify frontend via WebSocket
        messagingTemplate.convertAndSend("/topic/rooms/" + room.getId(), response);

        return response;
    }

    public RoomResponse getRoom(Long id) {
        RoomEntity room = roomRepository.findWithAgentsById(id)
                .orElseThrow(() -> new RuntimeException("Room not found: " + id));
        return toResponse(room);
    }

    private AiRoomPayload callAiService(CreateRoomRequest request) {
        try {
            String jsonBody = objectMapper.writeValueAsString(request);
            log.info("Calling AI service with body: {}", jsonBody);
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("AI service returned " + httpResponse.statusCode() + ": " + httpResponse.body());
            }
            return objectMapper.readValue(httpResponse.body(), AiRoomPayload.class);
        } catch (Exception e) {
            log.error("AI service call failed", e);
            throw new RuntimeException("Failed to generate room via AI service", e);
        }
    }

    private RoomResponse toResponse(RoomEntity room) {
        return RoomResponse.builder()
                .id(room.getId())
                .roomName(room.getRoomName())
                .theme(room.getTheme())
                .layout(RoomResponse.LayoutDto.builder()
                        .width(room.getLayoutWidth())
                        .height(room.getLayoutHeight())
                        .backgroundPreset(room.getBackgroundPreset())
                        .build())
                .agents(room.getAgents().stream().map(a -> RoomResponse.AgentDto.builder()
                        .externalId(a.getExternalId())
                        .name(a.getName())
                        .role(a.getRole())
                        .x(a.getPosX())
                        .y(a.getPosY())
                        .state(a.getState())
                        .build()).toList())
                .build();
    }
}
