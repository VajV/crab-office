package com.craboffice.backend.controller;

import com.craboffice.backend.dto.OpenClawCommandRequest;
import com.craboffice.backend.dto.OpenClawCommandResponse;
import com.craboffice.backend.service.OpenClawCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/openclaw/commands")
public class OpenClawCommandController {

    private final OpenClawCommandService openClawCommandService;

    public OpenClawCommandController(OpenClawCommandService openClawCommandService) {
        this.openClawCommandService = openClawCommandService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OpenClawCommandResponse execute(@PathVariable Long roomId,
                                           @RequestBody OpenClawCommandRequest request) {
        return openClawCommandService.execute(roomId, request);
    }
}
