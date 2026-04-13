package com.craboffice.backend.controller;

import com.craboffice.backend.dto.ContainerEventDto;
import com.craboffice.backend.service.ContainerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/container")
@RequiredArgsConstructor
public class ContainerController {

    private final ContainerService containerService;

    @GetMapping("/logs")
    public List<ContainerEventDto> getLogs(@PathVariable Long roomId) {
        return containerService.getLogs(roomId);
    }
}
