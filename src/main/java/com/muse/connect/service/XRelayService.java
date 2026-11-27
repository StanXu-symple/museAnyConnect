package com.muse.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.HttpClientFactory;
import com.muse.connect.model.AuthMode;
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
import java.util.stream.Collectors;

@Service
public class XRelayService {

    public static final String DEFAULT_BASE_URL = "https://api.x.com";

    private final ConnectorService connectorService;
    private final HttpClientFactory httpClientFactory;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayActivityBuffer activityBuffer;

    public XRelayService(
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

    public RelayResponse relay(
            String profileId,
            String method,
            String path,
            Map<String, String> queryParams,
            Object body
    ) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        return execute(profile, method, path, queryParams, body);
    }

    public RelayResponse postTweet(String profileId, String text, String replyToTweetId) {
        if (!StringUtils.hasText(text)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Tweet text is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        if (StringUtils.hasText(replyToTweetId)) {
            payload.put("reply", Map.of("in_reply_to_tweet_id", replyToTweetId.trim()));
        }
        return relay(profileId, "POST", "/2/tweets", null, payload);
    }

    public RelayResponse searchRecent(String profileId, String query, int maxResults) {
        if (!StringUtils.hasText(query)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Search query is required");
        }
        int bounded = Math.max(10, Math.min(maxResults, 100));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("max_results", String.valueOf(bounded));
        return relay(profileId, "GET", "/2/tweets/search/recent", params, null);
    }

    public RelayResponse getUserByUsername(String profileId, String username) {
        if (!StringUtils.hasText(username)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        String clean = username.trim().replaceFirst("^@", "");
        return relay(profileId, "GET", "/2/users/by/username/" + OAuth1Signer.percentEncode(clean), null, null);
    }

    public RelayResponse test(ConnectorProfile profile) {
        RelayResponse response = execute(profile, "GET", "/2/users/me", null, null);
        if (!response.success() && response.statusCode() == 403 && profile.getAuthMode() == AuthMode.BEARER_TOKEN) {
            return execute(profile, "GET", "/2/users/by/username/X", null, null);
        }
        return response;
    }

    private RelayResponse execute(
            ConnectorProfile profile,
            String method,
            String path,
            Map<String, String> queryParams,
            Object body
    ) {
        long start = System.currentTimeMillis();
        String httpMethod = StringUtils.hasText(method) ? method.trim().toUpperCase() : "GET";
        String normalizedPath = normalizePath(path);
        ConnectorConfig cfg = profile.getConfig();
        String baseUrl = StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll("/+$", "")
                : DEFAULT_BASE_URL;

        try {
            URI baseUri = URI.create(baseUrl + normalizedPath);
            URI fullUri = appendQueryParams(baseUri, queryParams);

            String authHeader = buildAuthHeader(profile, httpMethod, baseUri, queryParams);
            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(fullUri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json");

            if (body != null && !"GET".equals(httpMethod) && !"DELETE".equals(httpMethod)) {
                String jsonBody = (body instanceof String s) ? s : objectMapper.writeValueAsString(body);
                reqBuilder.header("Content-Type", "application/json");
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
            }

            HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());
            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - start;
            Object parsed = parseJsonOrText(resp.body());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;

            activityBuffer.record(
                    profile.getId(), ConnectorType.X,
                    httpMethod + " " + normalizedPath,
                    ok, resp.statusCode(), durationMs,
                    ok ? "OK" : ("HTTP " + resp.statusCode())
            );
            return ok
                    ? RelayResponse.ok(profile.getId(), ConnectorType.X, resp.statusCode(), durationMs, parsed)
                    : RelayResponse.fail(profile.getId(), ConnectorType.X, resp.statusCode(), durationMs, "X API returned HTTP " + resp.statusCode(), parsed);
        } catch (ConnectorException ex) {
            throw ex;
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(profile.getId(), ConnectorType.X, httpMethod + " " + normalizedPath, false, 502, durationMs, ex.getMessage());
            return RelayResponse.fail(profile.getId(), ConnectorType.X, 502, durationMs, "X relay failed: " + ex.getMessage(), null);
        }
    }

    private String buildAuthHeader(
            ConnectorProfile profile,
            String httpMethod,
            URI baseUri,
            Map<String, String> queryParams
    ) {
        ConnectorConfig cfg = profile.getConfig();
        if (profile.getAuthMode() == AuthMode.BEARER_TOKEN) {
            if (!StringUtils.hasText(cfg.getBearerToken())) {
                throw new ConnectorException(HttpStatus.BAD_REQUEST, "Bearer token is missing");
            }
            return "Bearer " + cfg.getBearerToken();
        }
        if (profile.getAuthMode() == AuthMode.OAUTH1A) {
            return OAuth1Signer.buildAuthorizationHeader(
                    httpMethod,
                    baseUri,
                    queryParams,
                    cfg.getApiKey(),
                    cfg.getApiSecret(),
                    cfg.getAccessToken(),
                    cfg.getAccessTokenSecret()
            );
        }
        throw new ConnectorException(HttpStatus.BAD_REQUEST, "Unsupported auth mode for X: " + profile.getAuthMode());
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "API path is required (e.g. /2/users/me)");
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private URI appendQueryParams(URI baseUri, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUri;
        }
        String query = queryParams.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .map(e -> OAuth1Signer.percentEncode(e.getKey()) + "=" + OAuth1Signer.percentEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        if (query.isEmpty()) {
            return baseUri;
        }
        String sep = baseUri.getRawQuery() == null ? "?" : "&";
        return URI.create(baseUri + sep + query);
    }

    private Object parseJsonOrText(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node;
        } catch (Exception ignored) {
            return body;
        }
    }
}
