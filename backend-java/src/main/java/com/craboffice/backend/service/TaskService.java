package com.craboffice.backend.service;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.entity.TaskEntity;
import com.craboffice.backend.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskDto createTask(Long roomId, String title, String description, String agentExternalId) {
        TaskEntity entity = TaskEntity.builder()
                .roomId(roomId)
                .title(title)
                .description(description)
                .assignedAgentExternalId(agentExternalId)
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        entity = taskRepository.save(entity);
        TaskDto dto = toDto(entity);

        messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/tasks", dto);
        return dto;
    }

    public TaskDto updateStatus(Long taskId, String status) {
        TaskEntity entity = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Task not found: " + taskId));
        entity.setStatus(status);
        entity.setUpdatedAt(Instant.now());
        entity = taskRepository.save(entity);
        TaskDto dto = toDto(entity);

        messagingTemplate.convertAndSend("/topic/rooms/" + entity.getRoomId() + "/tasks", dto);
        return dto;
    }

    public List<TaskDto> getTasks(Long roomId) {
        return taskRepository.findByRoomIdOrderByCreatedAtDesc(roomId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private TaskDto toDto(TaskEntity e) {
        return TaskDto.builder()
                .id(e.getId())
                .roomId(e.getRoomId())
                .assignedAgentExternalId(e.getAssignedAgentExternalId())
                .title(e.getTitle())
                .description(e.getDescription())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt().toString())
                .updatedAt(e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null)
                .build();
    }
}
