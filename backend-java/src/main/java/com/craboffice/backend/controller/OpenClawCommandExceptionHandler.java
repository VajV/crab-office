package com.craboffice.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = OpenClawCommandController.class)
public class OpenClawCommandExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadCommand(IllegalArgumentException ex) {
        return Map.of(
                "error", "invalid_command",
                "message", ex.getMessage()
        );
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleRuntimeError(RuntimeException ex) {
        return Map.of(
                "error", "command_execution_failed",
                "message", ex.getMessage()
        );
    }
}
