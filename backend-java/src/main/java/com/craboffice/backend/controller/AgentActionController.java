package com.craboffice.backend.controller;

import com.craboffice.backend.dto.AgentActionDto;
import com.craboffice.backend.entity.AgentActionEntity;
import com.craboffice.backend.repository.AgentActionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rooms/{roomId}/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final AgentActionRepository actionRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<AgentActionDto> getActions(@PathVariable Long roomId) {
        return actionRepository.findByRoomIdOrderByCreatedAtAsc(roomId).stream()
                .map(this::toDto)
                .toList();
    }

    private AgentActionDto toDto(AgentActionEntity entity) {
        Map<String, Object> params = null;
        if (entity.getParams() != null) {
            try {
                params = objectMapper.readValue(entity.getParams(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to parse params JSON for action {}", entity.getId(), e);
            }
        }
        return AgentActionDto.builder()
                .roomId(entity.getRoomId())
                .agentExternalId(entity.getAgentExternalId())
                .actionType(entity.getActionType())
                .toolName(entity.getToolName())
                .status(entity.getStatus())
                .params(params)
                .result(entity.getResult())
                .timestamp(entity.getCreatedAt().toString())
                .build();
    }
}
