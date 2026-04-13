package com.craboffice.backend.service;

import com.craboffice.backend.dto.SandboxFileContentResponse;
import com.craboffice.backend.dto.SandboxFileListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class SandboxService {

    private final String aiServiceUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SandboxService(
            @Value("${app.ai-service-url}") String aiServiceUrl,
            ObjectMapper objectMapper) {
        this.aiServiceUrl = aiServiceUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public SandboxFileListResponse listFiles(Long roomId) {
        String url = aiServiceUrl + "/rooms/" + roomId + "/files";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.statusCode()),
                        "AI service error: " + response.body());
            }
            return objectMapper.readValue(response.body(), SandboxFileListResponse.class);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list sandbox files for room {}", roomId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable");
        }
    }

    public SandboxFileContentResponse readFile(Long roomId, String path) {
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String url = aiServiceUrl + "/rooms/" + roomId + "/files/content?path=" + encodedPath;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.statusCode()),
                        "AI service error: " + response.body());
            }
            return objectMapper.readValue(response.body(), SandboxFileContentResponse.class);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read sandbox file {} for room {}", path, roomId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable");
        }
    }
}
