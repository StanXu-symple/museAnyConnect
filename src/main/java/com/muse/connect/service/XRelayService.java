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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class XRelayService {

    public static final String DEFAULT_BASE_URL = "https://api.x.com";
    public static final String DEFAULT_WEB_BASE_URL = "https://x.com";

    /** Public bearer token embedded in the official X web client bundle (used alongside auth_token + ct0). */
    public static final String DEFAULT_WEB_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";

    private static final String DEFAULT_USER_AGENT = XClientTransactionIdProvider.DEFAULT_WEB_USER_AGENT;

    private static final Map<String, String> DEFAULT_GRAPHQL_QUERY_IDS = Map.of(
            "CreateTweet", "l4hk8uVGvR-a8kzQ4nOhPA",
            "SearchTimeline", "vLHjqbDv7TPFMBkIIO1nOA",
            "UserByScreenName", "AMIBMjtxEEATh4z8V9GtRg"
    );

    private final ConnectorService connectorService;
    private final HttpClientFactory httpClientFactory;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayActivityBuffer activityBuffer;
    private final XClientTransactionIdProvider transactionIdProvider;

    public XRelayService(
            ConnectorService connectorService,
            HttpClientFactory httpClientFactory,
            AppProperties properties,
            ObjectMapper objectMapper,
            RelayActivityBuffer activityBuffer,
            XClientTransactionIdProvider transactionIdProvider
    ) {
        this.connectorService = connectorService;
        this.httpClientFactory = httpClientFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.activityBuffer = activityBuffer;
        this.transactionIdProvider = transactionIdProvider;
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
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return postTweetViaCookie(profile, text, replyToTweetId);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        if (StringUtils.hasText(replyToTweetId)) {
            payload.put("reply", Map.of("in_reply_to_tweet_id", replyToTweetId.trim()));
        }
        return execute(profile, "POST", "/2/tweets", null, payload);
    }

    public RelayResponse searchRecent(String profileId, String query, int maxResults) {
        if (!StringUtils.hasText(query)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Search query is required");
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        int bounded = Math.max(10, Math.min(maxResults, 100));
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return searchViaCookie(profile, query, bounded);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", query);
        params.put("max_results", String.valueOf(bounded));
        return execute(profile, "GET", "/2/tweets/search/recent", params, null);
    }

    public RelayResponse getUserByUsername(String profileId, String username) {
        if (!StringUtils.hasText(username)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        String clean = username.trim().replaceFirst("^@", "");
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return getUserViaCookie(profile, clean);
        }
        return execute(profile, "GET", "/2/users/by/username/" + OAuth1Signer.percentEncode(clean), null, null);
    }

    public RelayResponse test(ConnectorProfile profile) {
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            RelayResponse response = execute(profile, "GET", "/i/api/1.1/account/settings.json", null, null);
            if (!response.success() && (response.statusCode() == 403 || response.statusCode() == 404)) {
                return execute(profile, "GET", "/i/api/1.1/account/verify_credentials.json", Map.of("skip_status", "true"), null);
            }
            return response;
        }
        RelayResponse response = execute(profile, "GET", "/2/users/me", null, null);
        if (!response.success() && response.statusCode() == 403 && profile.getAuthMode() == AuthMode.BEARER_TOKEN) {
            return execute(profile, "GET", "/2/users/by/username/X", null, null);
        }
        return response;
    }

    private RelayResponse postTweetViaCookie(ConnectorProfile profile, String text, String replyToTweetId) {
        return executeGraphqlOperation(profile, "CreateTweet", op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("tweet_text", text);
            variables.put("dark_request", false);
            variables.put("media", Map.of("media_entities", List.of(), "possibly_sensitive", false));
            variables.put("semantic_annotation_ids", List.of());
            if (StringUtils.hasText(replyToTweetId)) {
                variables.put("reply", Map.of(
                        "in_reply_to_tweet_id", replyToTweetId.trim(),
                        "exclude_reply_user_ids", List.of()
                ));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("variables", variables);
            payload.put("features", mergeFeatureSwitches(defaultTweetFeatures(), op.featureSwitches()));
            payload.put("queryId", op.queryId());
            return execute(profile, "POST", "/i/api/graphql/" + op.queryId() + "/CreateTweet", null, payload);
        });
    }

    private RelayResponse searchViaCookie(ConnectorProfile profile, String query, int count) {
        return executeGraphqlOperation(profile, "SearchTimeline", op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("rawQuery", query);
            variables.put("count", count);
            variables.put("querySource", "typed_query");
            variables.put("product", "Latest");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("variables", writeJson(variables));
            params.put("features", writeJson(mergeFeatureSwitches(defaultTweetFeatures(), op.featureSwitches())));
            params.put("fieldToggles", writeJson(Map.of("withArticleRichContentState", false)));
            return execute(profile, "GET", "/i/api/graphql/" + op.queryId() + "/SearchTimeline", params, null);
        });
    }

    private RelayResponse getUserViaCookie(ConnectorProfile profile, String screenName) {
        return executeGraphqlOperation(profile, "UserByScreenName", op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("screen_name", screenName);
            variables.put("withSafetyModeUserFields", true);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("variables", writeJson(variables));
            params.put("features", writeJson(mergeFeatureSwitches(defaultUserFeatures(), op.featureSwitches())));
            return execute(profile, "GET", "/i/api/graphql/" + op.queryId() + "/UserByScreenName", params, null);
        });
    }

    private RelayResponse executeGraphqlOperation(
            ConnectorProfile profile,
            String operationName,
            Function<XClientTransactionIdProvider.GraphqlOperationMeta, RelayResponse> invoker
    ) {
        ConnectorConfig cfg = profile.getConfig();
        Optional<String> customQueryId = resolveCustomGraphqlQueryId(cfg, operationName);
        if (customQueryId.isPresent()) {
            return invoker.apply(new XClientTransactionIdProvider.GraphqlOperationMeta(customQueryId.get(), List.of()));
        }

        String baseUrl = resolveBaseUrl(profile);
        int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());
        HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());

        XClientTransactionIdProvider.GraphqlOperationMeta initialOp = transactionIdProvider
                .resolveGraphqlOperation(profile, client, baseUrl, operationName, timeoutMs, false)
                .orElseGet(() -> new XClientTransactionIdProvider.GraphqlOperationMeta(
                        DEFAULT_GRAPHQL_QUERY_IDS.getOrDefault(operationName, operationName),
                        List.of()
                ));

        RelayResponse response = invoker.apply(initialOp);
        if (!response.success() && response.statusCode() == 404) {
            Optional<XClientTransactionIdProvider.GraphqlOperationMeta> refreshed = transactionIdProvider
                    .resolveGraphqlOperation(profile, client, baseUrl, operationName, timeoutMs, true);
            if (refreshed.isPresent() && !refreshed.get().queryId().equals(initialOp.queryId())) {
                return invoker.apply(refreshed.get());
            }
        }
        return response;
    }

    private String resolveBaseUrl(ConnectorProfile profile) {
        ConnectorConfig cfg = profile.getConfig();
        String defaultBase = profile.getAuthMode() == AuthMode.COOKIE_AUTH ? DEFAULT_WEB_BASE_URL : DEFAULT_BASE_URL;
        return StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll("/+$", "")
                : defaultBase;
    }

    private Map<String, Object> mergeFeatureSwitches(Map<String, Object> base, List<String> extraSwitches) {
        if (extraSwitches == null || extraSwitches.isEmpty()) {
            return base;
        }
        for (String sw : extraSwitches) {
            base.putIfAbsent(sw, false);
        }
        return base;
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
        String defaultBase = profile.getAuthMode() == AuthMode.COOKIE_AUTH ? DEFAULT_WEB_BASE_URL : DEFAULT_BASE_URL;
        String baseUrl = StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll("/+$", "")
                : defaultBase;

        try {
            URI baseUri = URI.create(baseUrl + normalizedPath);
            URI fullUri = appendQueryParams(baseUri, queryParams);

            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());
            String jsonBody = null;
            if (body != null && !"GET".equals(httpMethod) && !"DELETE".equals(httpMethod)) {
                jsonBody = (body instanceof String s) ? s : objectMapper.writeValueAsString(body);
            }

            HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());
            String requestPath = StringUtils.hasText(baseUri.getPath()) ? baseUri.getPath() : "/";

            String transactionId = null;
            if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
                transactionId = transactionIdProvider
                        .generateHeader(profile, client, baseUrl, httpMethod, requestPath, timeoutMs, false)
                        .orElse(null);
            }

            HttpRequest request = buildHttpRequest(
                    fullUri, baseUri, profile, httpMethod, queryParams, jsonBody, timeoutMs, transactionId
            );
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            // X web APIs return HTTP 404 when x-client-transaction-id is missing or stale;
            // mirror twscrape by refreshing transaction keys and retrying once on 404.
            if (profile.getAuthMode() == AuthMode.COOKIE_AUTH && resp.statusCode() == 404) {
                String refreshedTid = transactionIdProvider
                        .generateHeader(profile, client, baseUrl, httpMethod, requestPath, timeoutMs, true)
                        .orElse(null);
                if (StringUtils.hasText(refreshedTid)) {
                    HttpRequest retryRequest = buildHttpRequest(
                            fullUri, baseUri, profile, httpMethod, queryParams, jsonBody, timeoutMs, refreshedTid
                    );
                    resp = client.send(retryRequest, HttpResponse.BodyHandlers.ofString());
                }
            }

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

    private HttpRequest buildHttpRequest(
            URI fullUri,
            URI baseUri,
            ConnectorProfile profile,
            String httpMethod,
            Map<String, String> queryParams,
            String jsonBody,
            int timeoutMs,
            String transactionId
    ) {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(fullUri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");

        applyAuthHeaders(reqBuilder, profile, httpMethod, baseUri, queryParams);
        if (StringUtils.hasText(transactionId)) {
            reqBuilder.header("x-client-transaction-id", transactionId);
        }

        if (jsonBody != null) {
            reqBuilder.header("Content-Type", "application/json");
            reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            reqBuilder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
        }
        return reqBuilder.build();
    }

    private void applyAuthHeaders(
            HttpRequest.Builder reqBuilder,
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
            reqBuilder.header("Authorization", "Bearer " + cfg.getBearerToken());
            return;
        }
        if (profile.getAuthMode() == AuthMode.OAUTH1A) {
            String oauthHeader = OAuth1Signer.buildAuthorizationHeader(
                    httpMethod,
                    baseUri,
                    queryParams,
                    cfg.getApiKey(),
                    cfg.getApiSecret(),
                    cfg.getAccessToken(),
                    cfg.getAccessTokenSecret()
            );
            reqBuilder.header("Authorization", oauthHeader);
            return;
        }
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            if (!StringUtils.hasText(cfg.getAuthToken()) || !StringUtils.hasText(cfg.getCt0())) {
                throw new ConnectorException(HttpStatus.BAD_REQUEST, "authToken and ct0 are required for X COOKIE_AUTH");
            }
            String webBearer = StringUtils.hasText(cfg.getBearerToken())
                    ? cfg.getBearerToken()
                    : DEFAULT_WEB_BEARER_TOKEN;
            String ct0 = cfg.getCt0().trim();
            String authToken = cfg.getAuthToken().trim();
            reqBuilder.header("Authorization", "Bearer " + webBearer);
            reqBuilder.header("Cookie", "auth_token=" + authToken + "; ct0=" + ct0);
            reqBuilder.header("x-csrf-token", ct0);
            reqBuilder.header("x-twitter-auth-type", "OAuth2Session");
            reqBuilder.header("x-twitter-active-user", "yes");
            reqBuilder.header("x-twitter-client-language", "en");
            reqBuilder.header("Origin", DEFAULT_WEB_BASE_URL);
            reqBuilder.header("Referer", DEFAULT_WEB_BASE_URL + "/");
            reqBuilder.header("User-Agent", DEFAULT_USER_AGENT);
            return;
        }
        throw new ConnectorException(HttpStatus.BAD_REQUEST, "Unsupported auth mode for X: " + profile.getAuthMode());
    }

    String resolveGraphqlQueryId(ConnectorConfig cfg, String operationName) {
        return resolveCustomGraphqlQueryId(cfg, operationName)
                .orElseGet(() -> DEFAULT_GRAPHQL_QUERY_IDS.getOrDefault(operationName, operationName));
    }

    Optional<String> resolveCustomGraphqlQueryId(ConnectorConfig cfg, String operationName) {
        if (cfg != null && StringUtils.hasText(cfg.getGraphqlQueryIds())) {
            String[] entries = cfg.getGraphqlQueryIds().split("[,;\\n]");
            for (String entry : entries) {
                int eq = entry.indexOf('=');
                if (eq > 0 && eq < entry.length() - 1) {
                    String key = entry.substring(0, eq).trim();
                    String val = entry.substring(eq + 1).trim();
                    if (operationName.equalsIgnoreCase(key) && StringUtils.hasText(val)) {
                        return Optional.of(val);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> defaultTweetFeatures() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("articles_preview_enabled", false);
        f.put("c9s_tweet_anatomy_moderator_badge_enabled", true);
        f.put("communities_web_enable_tweet_community_results_fetch", true);
        f.put("content_disclosure_ai_generated_indicator_enabled", false);
        f.put("content_disclosure_indicator_enabled", false);
        f.put("creator_subscriptions_quote_tweet_preview_enabled", false);
        f.put("creator_subscriptions_tweet_preview_api_enabled", true);
        f.put("freedom_of_speech_not_reach_fetch_enabled", true);
        f.put("graphql_is_translatable_rweb_tweet_is_translatable_enabled", true);
        f.put("longform_notetweets_consumption_enabled", true);
        f.put("longform_notetweets_inline_media_enabled", true);
        f.put("longform_notetweets_rich_text_read_enabled", true);
        f.put("post_ctas_fetch_enabled", false);
        f.put("premium_content_api_read_enabled", false);
        f.put("profile_label_improvements_pcf_label_in_post_enabled", false);
        f.put("responsive_web_edit_tweet_api_enabled", true);
        f.put("responsive_web_enhance_cards_enabled", false);
        f.put("responsive_web_graphql_exclude_directive_enabled", true);
        f.put("responsive_web_graphql_skip_user_profile_image_extensions_enabled", false);
        f.put("responsive_web_graphql_timeline_navigation_enabled", true);
        f.put("responsive_web_grok_analysis_button_from_backend", false);
        f.put("responsive_web_grok_analyze_button_fetch_trends_enabled", false);
        f.put("responsive_web_grok_analyze_post_followups_enabled", false);
        f.put("responsive_web_grok_annotations_enabled", false);
        f.put("responsive_web_grok_community_note_auto_translation_is_enabled", false);
        f.put("responsive_web_grok_image_annotation_enabled", false);
        f.put("responsive_web_grok_imagine_annotation_enabled", false);
        f.put("responsive_web_grok_share_attachment_enabled", false);
        f.put("responsive_web_grok_show_grok_translated_post", true);
        f.put("responsive_web_jetfuel_frame", false);
        f.put("responsive_web_made_on_x_label_enabled", false);
        f.put("responsive_web_media_download_video_enabled", false);
        f.put("responsive_web_nested_quote_preview_enabled", false);
        f.put("responsive_web_profile_redirect_enabled", true);
        f.put("responsive_web_twitter_article_tweet_consumption_enabled", true);
        f.put("rweb_cashtags_composer_attachment_enabled", false);
        f.put("rweb_cashtags_enabled", false);
        f.put("rweb_conversational_replies_downvote_enabled", false);
        f.put("rweb_sports_post_context_enabled", false);
        f.put("rweb_tipjar_consumption_enabled", true);
        f.put("rweb_video_screen_enabled", true);
        f.put("rweb_video_timestamps_enabled", true);
        f.put("standardized_nudges_misinfo", true);
        f.put("tweet_awards_web_tipping_enabled", false);
        f.put("tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled", true);
        f.put("tweet_with_visibility_results_prefer_gql_media_interstitial_enabled", false);
        f.put("tweetypie_unmention_optimization_enabled", true);
        f.put("verified_phone_label_enabled", false);
        f.put("view_counts_everywhere_api_enabled", true);
        f.put("x_elections_post_context_enabled", false);
        return f;
    }

    private Map<String, Object> defaultUserFeatures() {
        Map<String, Object> f = new LinkedHashMap<>(defaultTweetFeatures());
        f.put("highlights_tweets_tab_ui_enabled", true);
        f.put("hidden_profile_likes_enabled", true);
        f.put("hidden_profile_subscriptions_enabled", true);
        f.put("subscriptions_verification_info_verified_since_enabled", true);
        f.put("subscriptions_verification_info_is_identity_verified_enabled", false);
        f.put("responsive_web_twitter_article_notes_tab_enabled", false);
        f.put("subscriptions_feature_can_gift_premium", false);
        f.put("profile_label_improvements_pcf_label_in_post_enabled", false);
        return f;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ConnectorException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize X request payload: " + ex.getMessage());
        }
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
