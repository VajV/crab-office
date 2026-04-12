package com.craboffice.backend.controller;

import com.craboffice.backend.dto.MessageDto;
import com.craboffice.backend.dto.SendMessageRequest;
import com.craboffice.backend.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto sendMessage(
            @PathVariable Long roomId,
            @Valid @RequestBody SendMessageRequest request) {
        return chatService.sendUserMessage(roomId, request);
    }

    @GetMapping
    public List<MessageDto> getMessages(@PathVariable Long roomId) {
        return chatService.getMessages(roomId);
    }
}
