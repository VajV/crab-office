package com.craboffice.backend.controller;

import com.craboffice.backend.dto.SandboxFileContentResponse;
import com.craboffice.backend.dto.SandboxFileListResponse;
import com.craboffice.backend.service.SandboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/files")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxService sandboxService;

    @GetMapping
    public SandboxFileListResponse listFiles(@PathVariable Long roomId) {
        return sandboxService.listFiles(roomId);
    }

    @GetMapping("/content")
    public SandboxFileContentResponse readFile(@PathVariable Long roomId, @RequestParam String path) {
        return sandboxService.readFile(roomId, path);
    }
}
