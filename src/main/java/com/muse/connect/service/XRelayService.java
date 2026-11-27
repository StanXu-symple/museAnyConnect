package com.muse.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.HttpClientFactory;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.XRelayConstants;
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

    public static final String DEFAULT_BASE_URL = XRelayConstants.DEFAULT_BASE_URL;
    public static final String DEFAULT_WEB_BASE_URL = XRelayConstants.DEFAULT_WEB_BASE_URL;

    /** 官方 X 网页客户端内置的公共 Bearer Token（与 auth_token + ct0 配合使用）。 */
    public static final String DEFAULT_WEB_BEARER_TOKEN = XRelayConstants.DEFAULT_WEB_BEARER_TOKEN;

    private static final String DEFAULT_USER_AGENT = XClientTransactionIdProvider.DEFAULT_WEB_USER_AGENT;

    private static final Map<String, String> DEFAULT_GRAPHQL_QUERY_IDS = XRelayConstants.DEFAULT_GRAPHQL_QUERY_IDS;

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
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.X_TWEET_TEXT_REQUIRED);
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return postTweetViaCookie(profile, text, replyToTweetId);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(XRelayConstants.FIELD_TEXT, text);
        if (StringUtils.hasText(replyToTweetId)) {
            payload.put(
                    XRelayConstants.FIELD_REPLY,
                    Map.of(XRelayConstants.FIELD_IN_REPLY_TO_TWEET_ID, replyToTweetId.trim())
            );
        }
        return execute(profile, HttpConstants.METHOD_POST, XRelayConstants.PATH_V2_TWEETS, null, payload);
    }

    public RelayResponse searchRecent(String profileId, String query, int maxResults) {
        if (!StringUtils.hasText(query)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.X_SEARCH_QUERY_REQUIRED);
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        int bounded = Math.max(10, Math.min(maxResults, 100));
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return searchViaCookie(profile, query, bounded);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(XRelayConstants.PARAM_QUERY, query);
        params.put(XRelayConstants.PARAM_MAX_RESULTS, String.valueOf(bounded));
        return execute(profile, HttpConstants.METHOD_GET, XRelayConstants.PATH_V2_SEARCH_RECENT, params, null);
    }

    public RelayResponse getUserByUsername(String profileId, String username) {
        if (!StringUtils.hasText(username)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.X_USERNAME_REQUIRED);
        }
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.X);
        String clean = username.trim().replaceFirst(XRelayConstants.LEADING_AT_REGEX, RouteConstants.EMPTY);
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            return getUserViaCookie(profile, clean);
        }
        return execute(
                profile,
                HttpConstants.METHOD_GET,
                XRelayConstants.PATH_V2_USER_BY_USERNAME_PREFIX + OAuth1Signer.percentEncode(clean),
                null,
                null
        );
    }

    public RelayResponse test(ConnectorProfile profile) {
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            RelayResponse response = execute(profile, HttpConstants.METHOD_GET, XRelayConstants.PATH_WEB_ACCOUNT_SETTINGS, null, null);
            if (!response.success() && (response.statusCode() == 403 || response.statusCode() == 404)) {
                return execute(
                        profile,
                        HttpConstants.METHOD_GET,
                        XRelayConstants.PATH_WEB_VERIFY_CREDENTIALS,
                        Map.of(XRelayConstants.PARAM_SKIP_STATUS, XRelayConstants.VALUE_TRUE),
                        null
                );
            }
            return response;
        }
        RelayResponse response = execute(profile, HttpConstants.METHOD_GET, XRelayConstants.PATH_V2_USERS_ME, null, null);
        if (!response.success() && response.statusCode() == 403 && profile.getAuthMode() == AuthMode.BEARER_TOKEN) {
            return execute(profile, HttpConstants.METHOD_GET, XRelayConstants.PATH_V2_PUBLIC_TEST_USER, null, null);
        }
        return response;
    }

    private RelayResponse postTweetViaCookie(ConnectorProfile profile, String text, String replyToTweetId) {
        return executeGraphqlOperation(profile, XRelayConstants.OP_CREATE_TWEET, op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(XRelayConstants.FIELD_TWEET_TEXT, text);
            variables.put(XRelayConstants.FIELD_DARK_REQUEST, false);
            variables.put(
                    XRelayConstants.FIELD_MEDIA,
                    Map.of(
                            XRelayConstants.FIELD_MEDIA_ENTITIES, List.of(),
                            XRelayConstants.FIELD_POSSIBLY_SENSITIVE, false
                    )
            );
            variables.put(XRelayConstants.FIELD_SEMANTIC_ANNOTATION_IDS, List.of());
            if (StringUtils.hasText(replyToTweetId)) {
                variables.put(XRelayConstants.FIELD_REPLY, Map.of(
                        XRelayConstants.FIELD_IN_REPLY_TO_TWEET_ID, replyToTweetId.trim(),
                        XRelayConstants.FIELD_EXCLUDE_REPLY_USER_IDS, List.of()
                ));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(XRelayConstants.PARAM_VARIABLES, variables);
            payload.put(XRelayConstants.PARAM_FEATURES, mergeFeatureSwitches(defaultTweetFeatures(), op.featureSwitches()));
            payload.put(XRelayConstants.FIELD_QUERY_ID, op.queryId());
            return execute(
                    profile,
                    HttpConstants.METHOD_POST,
                    XRelayConstants.PATH_WEB_GRAPHQL_PREFIX + op.queryId() + HttpConstants.SLASH + XRelayConstants.OP_CREATE_TWEET,
                    null,
                    payload
            );
        });
    }

    private RelayResponse searchViaCookie(ConnectorProfile profile, String query, int count) {
        return executeGraphqlOperation(profile, XRelayConstants.OP_SEARCH_TIMELINE, op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(XRelayConstants.FIELD_RAW_QUERY, query);
            variables.put(XRelayConstants.FIELD_COUNT, count);
            variables.put(XRelayConstants.FIELD_QUERY_SOURCE, XRelayConstants.VALUE_TYPED_QUERY);
            variables.put(XRelayConstants.FIELD_PRODUCT, XRelayConstants.VALUE_LATEST);

            Map<String, String> params = new LinkedHashMap<>();
            params.put(XRelayConstants.PARAM_VARIABLES, writeJson(variables));
            params.put(XRelayConstants.PARAM_FEATURES, writeJson(mergeFeatureSwitches(defaultTweetFeatures(), op.featureSwitches())));
            params.put(XRelayConstants.PARAM_FIELD_TOGGLES, writeJson(Map.of(XRelayConstants.FIELD_ARTICLE_RICH_CONTENT, false)));
            return execute(
                    profile,
                    HttpConstants.METHOD_GET,
                    XRelayConstants.PATH_WEB_GRAPHQL_PREFIX + op.queryId() + HttpConstants.SLASH + XRelayConstants.OP_SEARCH_TIMELINE,
                    params,
                    null
            );
        });
    }

    private RelayResponse getUserViaCookie(ConnectorProfile profile, String screenName) {
        return executeGraphqlOperation(profile, XRelayConstants.OP_USER_BY_SCREEN_NAME, op -> {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(XRelayConstants.FIELD_SCREEN_NAME, screenName);
            variables.put(XRelayConstants.FIELD_SAFETY_MODE_USER_FIELDS, true);

            Map<String, String> params = new LinkedHashMap<>();
            params.put(XRelayConstants.PARAM_VARIABLES, writeJson(variables));
            params.put(XRelayConstants.PARAM_FEATURES, writeJson(mergeFeatureSwitches(defaultUserFeatures(), op.featureSwitches())));
            return execute(
                    profile,
                    HttpConstants.METHOD_GET,
                    XRelayConstants.PATH_WEB_GRAPHQL_PREFIX + op.queryId() + HttpConstants.SLASH + XRelayConstants.OP_USER_BY_SCREEN_NAME,
                    params,
                    null
            );
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
                ? cfg.getApiBaseUrl().replaceAll(HttpConstants.TRAILING_SLASH_REGEX, RouteConstants.EMPTY)
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
        String httpMethod = StringUtils.hasText(method) ? method.trim().toUpperCase() : HttpConstants.METHOD_GET;
        String normalizedPath = normalizePath(path);
        ConnectorConfig cfg = profile.getConfig();
        String defaultBase = profile.getAuthMode() == AuthMode.COOKIE_AUTH ? DEFAULT_WEB_BASE_URL : DEFAULT_BASE_URL;
        String baseUrl = StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll(HttpConstants.TRAILING_SLASH_REGEX, RouteConstants.EMPTY)
                : defaultBase;

        try {
            URI baseUri = URI.create(baseUrl + normalizedPath);
            URI fullUri = appendQueryParams(baseUri, queryParams);

            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());
            String jsonBody = null;
            if (body != null
                    && !HttpConstants.METHOD_GET.equals(httpMethod)
                    && !HttpConstants.METHOD_DELETE.equals(httpMethod)) {
                jsonBody = (body instanceof String s) ? s : objectMapper.writeValueAsString(body);
            }

            HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());
            String requestPath = StringUtils.hasText(baseUri.getPath()) ? baseUri.getPath() : RouteConstants.ROOT;

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

            // 当 x-client-transaction-id 缺失或过期时，X 网页接口会返回 HTTP 404；
            // 参考 twscrape 策略，在收到 404 时刷新事务签名密钥并重试一次。
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
                    httpMethod + HttpConstants.SPACE + normalizedPath,
                    ok, resp.statusCode(), durationMs,
                    ok ? HttpConstants.SUMMARY_OK : (HttpConstants.SUMMARY_HTTP_PREFIX + resp.statusCode())
            );
            return ok
                    ? RelayResponse.ok(profile.getId(), ConnectorType.X, resp.statusCode(), durationMs, parsed)
                    : RelayResponse.fail(profile.getId(), ConnectorType.X, resp.statusCode(), durationMs,
                    ExceptionMessageConstants.X_API_RETURNED_HTTP_PREFIX + resp.statusCode(), parsed);
        } catch (ConnectorException ex) {
            throw ex;
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            activityBuffer.record(
                    profile.getId(), ConnectorType.X,
                    httpMethod + HttpConstants.SPACE + normalizedPath,
                    false, 502, durationMs, ex.getMessage()
            );
            return RelayResponse.fail(profile.getId(), ConnectorType.X, 502, durationMs,
                    ExceptionMessageConstants.X_RELAY_FAILED_PREFIX + ex.getMessage(), null);
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
                .header(HttpConstants.HEADER_ACCEPT, HttpConstants.MIME_APPLICATION_JSON);

        applyAuthHeaders(reqBuilder, profile, httpMethod, baseUri, queryParams);
        if (StringUtils.hasText(transactionId)) {
            reqBuilder.header(XRelayConstants.HEADER_TRANSACTION_ID, transactionId);
        }

        if (jsonBody != null) {
            reqBuilder.header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.MIME_APPLICATION_JSON);
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
                throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.X_BEARER_TOKEN_MISSING);
            }
            reqBuilder.header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_PREFIX_BEARER + cfg.getBearerToken());
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
            reqBuilder.header(HttpConstants.HEADER_AUTHORIZATION, oauthHeader);
            return;
        }
        if (profile.getAuthMode() == AuthMode.COOKIE_AUTH) {
            if (!StringUtils.hasText(cfg.getAuthToken()) || !StringUtils.hasText(cfg.getCt0())) {
                throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_X_COOKIE_REQUIRED);
            }
            String webBearer = StringUtils.hasText(cfg.getBearerToken())
                    ? cfg.getBearerToken()
                    : DEFAULT_WEB_BEARER_TOKEN;
            String ct0 = cfg.getCt0().trim();
            String authToken = cfg.getAuthToken().trim();
            reqBuilder.header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.AUTH_PREFIX_BEARER + webBearer);
            reqBuilder.header(
                    HttpConstants.HEADER_COOKIE,
                    XRelayConstants.COOKIE_AUTH_TOKEN_PREFIX + authToken + XRelayConstants.COOKIE_CT0_SEPARATOR + ct0
            );
            reqBuilder.header(XRelayConstants.HEADER_X_CSRF_TOKEN, ct0);
            reqBuilder.header(XRelayConstants.HEADER_X_AUTH_TYPE, XRelayConstants.VALUE_OAUTH2_SESSION);
            reqBuilder.header(XRelayConstants.HEADER_X_ACTIVE_USER, XRelayConstants.VALUE_YES);
            reqBuilder.header(XRelayConstants.HEADER_X_CLIENT_LANGUAGE, XRelayConstants.VALUE_LANG_EN);
            reqBuilder.header(HttpConstants.HEADER_ORIGIN, DEFAULT_WEB_BASE_URL);
            reqBuilder.header(HttpConstants.HEADER_REFERER, DEFAULT_WEB_BASE_URL + HttpConstants.SLASH);
            reqBuilder.header(HttpConstants.HEADER_USER_AGENT, DEFAULT_USER_AGENT);
            return;
        }
        throw new ConnectorException(
                HttpStatus.BAD_REQUEST,
                ExceptionMessageConstants.X_UNSUPPORTED_AUTH_MODE_PREFIX + profile.getAuthMode()
        );
    }

    String resolveGraphqlQueryId(ConnectorConfig cfg, String operationName) {
        return resolveCustomGraphqlQueryId(cfg, operationName)
                .orElseGet(() -> DEFAULT_GRAPHQL_QUERY_IDS.getOrDefault(operationName, operationName));
    }

    Optional<String> resolveCustomGraphqlQueryId(ConnectorConfig cfg, String operationName) {
        if (cfg != null && StringUtils.hasText(cfg.getGraphqlQueryIds())) {
            String[] entries = cfg.getGraphqlQueryIds().split(XRelayConstants.QUERY_ID_SPLIT_REGEX);
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
        for (String key : XRelayConstants.DEFAULT_TWEET_TRUE_FEATURES) {
            f.put(key, true);
        }
        for (String key : XRelayConstants.DEFAULT_TWEET_FALSE_FEATURES) {
            f.put(key, false);
        }
        return f;
    }

    private Map<String, Object> defaultUserFeatures() {
        Map<String, Object> f = new LinkedHashMap<>(defaultTweetFeatures());
        for (String key : XRelayConstants.DEFAULT_USER_EXTRA_TRUE_FEATURES) {
            f.put(key, true);
        }
        for (String key : XRelayConstants.DEFAULT_USER_EXTRA_FALSE_FEATURES) {
            f.put(key, false);
        }
        return f;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ConnectorException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ExceptionMessageConstants.X_SERIALIZE_PAYLOAD_FAILED_PREFIX + ex.getMessage()
            );
        }
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.X_API_PATH_REQUIRED);
        }
        String trimmed = path.trim();
        return trimmed.startsWith(HttpConstants.SLASH) ? trimmed : HttpConstants.SLASH + trimmed;
    }

    private URI appendQueryParams(URI baseUri, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUri;
        }
        String query = queryParams.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .map(e -> OAuth1Signer.percentEncode(e.getKey()) + HttpConstants.EQUALS + OAuth1Signer.percentEncode(e.getValue()))
                .collect(Collectors.joining(RouteConstants.QUERY_AMP));
        if (query.isEmpty()) {
            return baseUri;
        }
        String sep = baseUri.getRawQuery() == null ? RouteConstants.QUERY_PREFIX : RouteConstants.QUERY_AMP;
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
