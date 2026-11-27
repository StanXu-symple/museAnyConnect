package com.muse.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.HttpClientFactory;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.repository.RelayActivityBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TelegramRelayService {

    public static final String DEFAULT_BASE_URL = "https://api.telegram.org";

    private final ConnectorService connectorService;
    private final HttpClientFactory httpClientFactory;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayActivityBuffer activityBuffer;

    public TelegramRelayService(
            ConnectorService connectorService,
            HttpClientFactory httpClientFactory,
            AppProperties properties,
            ObjectMapper objectMapper,
            RelayActivityBuffer activityBuffer
    ) {
        this.connectorService = connectorService;
        this.httpClientFactory = httpClientFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.activityBuffer = activityBuffer;
    }

    public RelayResponse sendMessage(
            String profileId,
            String chatId,
            String text,
            String parseMode,
            Boolean disableNotification,
            Long replyToMessageId
    ) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.TELEGRAM);
        ConnectorConfig cfg = profile.getConfig();
        String targetChatId = StringUtils.hasText(chatId) ? chatId.trim() : cfg.getDefaultChatId();
        if (!StringUtils.hasText(targetChatId)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "chatId is required (no defaultChatId configured)");
        }
        if (!StringUtils.hasText(text)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Message text is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", targetChatId);
        payload.put("text", text);
        String effectiveParseMode = StringUtils.hasText(parseMode) ? parseMode.trim() : cfg.getParseMode();
        if (StringUtils.hasText(effectiveParseMode)) {
            payload.put("parse_mode", effectiveParseMode);
        }
        if (disableNotification != null) {
            payload.put("disable_notification", disableNotification);
        }
        if (replyToMessageId != null) {
            payload.put("reply_parameters", Map.of("message_id", replyToMessageId));
        }
        return executeMethod(profile, "sendMessage", payload);
    }

    public RelayResponse getUpdates(String profileId, Long offset, Integer limit, Integer timeoutSeconds) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.TELEGRAM);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (offset != null) {
            payload.put("offset", offset);
        }
        if (limit != null) {
            payload.put("limit", Math.max(1, Math.min(limit, 100)));
        }
        if (timeoutSeconds != null) {
            payload.put("timeout", Math.max(0, Math.min(timeoutSeconds, 30)));
        }
        return executeMethod(profile, "getUpdates", payload);
    }

    public RelayResponse callMethod(String profileId, String method, Map<String, Object> payload) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.TELEGRAM);
        return executeMethod(profile, method, payload);
    }

    public RelayResponse test(ConnectorProfile profile) {
        return executeMethod(profile, "getMe", Map.of());
    }

    private RelayResponse executeMethod(ConnectorProfile profile, String method, Map<String, Object> payload) {
        if (!StringUtils.hasText(method) || !method.matches("^[a-zA-Z0-9_]+$")) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Invalid Telegram Bot API method name");
        }
        long start = System.currentTimeMillis();
        ConnectorConfig cfg = profile.getConfig();
        if (!StringUtils.hasText(cfg.getBotToken())) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Telegram botToken is missing");
        }

        String baseUrl = StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll("/+$", "")
                : DEFAULT_BASE_URL;
        URI uri = URI.create(baseUrl + "/bot" + cfg.getBotToken() + "/" + method);

        try {
            String jsonBody = objectMapper.writeValueAsString(payload != null ? payload : Map.of());
            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - start;

            Object parsed = parseJsonOrText(resp.body());
            boolean apiOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
            if (parsed instanceof JsonNode node && node.has("ok")) {
                apiOk = apiOk && node.get("ok").asBoolean(false);
            }

            activityBuffer.record(
                    profile.getId(), ConnectorType.TELEGRAM,
                    method, apiOk, resp.statusCode(), durationMs,
                    apiOk ? "OK" : ("HTTP " + resp.statusCode())
            );
            return apiOk
                    ? RelayResponse.ok(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs, parsed)
                    : RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs, "Telegram API returned error", parsed);
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(profile.getId(), ConnectorType.TELEGRAM, method, false, 502, durationMs, ex.getMessage());
            return RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, 502, durationMs, "Telegram relay failed: " + ex.getMessage(), null);
        }
    }

    private Object parseJsonOrText(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return body;
        }
    }
}
