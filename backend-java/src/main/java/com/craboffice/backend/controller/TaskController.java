package com.craboffice.backend.controller;

import com.craboffice.backend.dto.TaskDto;
import com.craboffice.backend.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public List<TaskDto> getTasks(@PathVariable Long roomId) {
        return taskService.getTasks(roomId);
    }
}
