package com.craboffice.backend.controller;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms/{roomId}/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public List<TaskDto> getTasks(@PathVariable Long roomId) {
        return taskService.getTasks(roomId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(@PathVariable Long roomId, @RequestBody TaskDto taskDto) {
        return taskService.createTask(
                roomId,
                taskDto.getTitle(),
                taskDto.getDescription(),
                taskDto.getAssignedAgentExternalId()
        );
    }

    @PatchMapping("/{taskId}/status")
    public TaskDto updateTaskStatus(@PathVariable Long roomId,
                                    @PathVariable Long taskId,
                                    @RequestBody Map<String, String> body) {
        return taskService.updateStatus(taskId, body.get("status"));
    }

    @PatchMapping("/{taskId}/result")
    public TaskDto updateTaskResult(@PathVariable Long roomId,
                                    @PathVariable Long taskId,
                                    @RequestBody Map<String, String> body) {
        return taskService.updateResult(taskId, body.get("result"));
    }
}
