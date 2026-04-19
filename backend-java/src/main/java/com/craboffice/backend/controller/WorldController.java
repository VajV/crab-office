package com.craboffice.backend.controller;

import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.service.WorldService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/world")
public class WorldController {

    private final WorldService worldService;

    public WorldController(WorldService worldService) {
        this.worldService = worldService;
    }

    @GetMapping
    public WorldResponse getWorld(@PathVariable Long roomId) {
        return worldService.getWorld(roomId);
    }
}
