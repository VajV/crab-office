package com.craboffice.backend.controller;

import com.craboffice.backend.dto.PublishSimulationEventRequest;
import com.craboffice.backend.dto.WorldResponse;
import com.craboffice.backend.service.RuntimeSimulationEventService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/simulation-events")
public class SimulationEventController {

    private final RuntimeSimulationEventService runtimeSimulationEventService;

    public SimulationEventController(RuntimeSimulationEventService runtimeSimulationEventService) {
        this.runtimeSimulationEventService = runtimeSimulationEventService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorldResponse.SimulationEventDto publish(@PathVariable Long roomId,
                                                    @RequestBody PublishSimulationEventRequest request) {
        return runtimeSimulationEventService.publish(roomId, request);
    }
}
