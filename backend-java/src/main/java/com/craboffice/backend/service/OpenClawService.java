package com.craboffice.backend.service;

import com.craboffice.backend.dto.MessageDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for OpenRouter's OpenAI-compatible chat API.
 * Sends conversation history and receives agent responses.
 */
@Service
@Slf4j
public class OpenClawService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final String aiServiceUrl;

    public OpenClawService(
            ObjectMapper objectMapper,
            @Value("${app.openclaw.url}") String apiUrl,
            @Value("${app.openclaw.token}") String apiKey,
            @Value("${app.openclaw.model}") String model,
            @Value("${app.ai-service-url}") String aiServiceUrl) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.aiServiceUrl = aiServiceUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Send a conversation to OpenClaw and return the assistant's reply.
     *
     * @param history ordered messages for context (oldest first)
     * @param userMessage the latest user message
     * @param systemPrompt optional system prompt override (e.g. agent role)
     * @return the assistant text response, or null on failure
     */
    public String chat(List<MessageDto> history, String userMessage, String systemPrompt) {
        try {
            ObjectNode body = buildRequestBody(history, userMessage, systemPrompt);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenClaw returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            return extractContent(response.body());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to call OpenClaw Gateway", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Route chat through the Python AI service's /chat-stream endpoint.
     * This enables HTTP SSE streaming and publishes AgentAction events to Redis.
     */
    public String chatViaProxy(Long roomId, String agentExternalId,
                               List<MessageDto> history, String userMessage, String systemPrompt) {
        try {
            ObjectNode body = buildRequestBody(history, userMessage, systemPrompt);
            body.put("room_id", roomId);
            body.put("agent_external_id", agentExternalId);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/chat-stream"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Python chat-stream returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            return extractContent(response.body());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to call Python chat-stream", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private ObjectNode buildRequestBody(List<MessageDto> history, String userMessage, String systemPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }

        // Add conversation history
        if (history != null) {
            for (MessageDto msg : history) {
                ObjectNode m = messages.addObject();
                m.put("role", "USER".equals(msg.getSenderType()) ? "user" : "assistant");
                m.put("content", msg.getContent());
            }
        }

        // Add the new user message
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        return body;
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        }
        log.warn("Unexpected OpenClaw response structure: {}", responseBody);
        return null;
    }

    /**
     * Quick health check — tries to reach OpenRouter.
     */
    public boolean isHealthy() {
        try {
            // Derive base URL from configured API endpoint
            URI apiUri = URI.create(apiUrl);
            String baseUrl = apiUri.getScheme() + "://" + apiUri.getAuthority();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("OpenRouter health check failed: {}", e.getMessage());
            return false;
        }
    }
}
