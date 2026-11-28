package com.muse.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.HttpClientFactory;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.TelegramRelayConstants;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.repository.RelayActivityBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram 中转核心服务。
 * 同时支持传统 Bot API（BOT_TOKEN）与基于 gotd/td 侧车服务的个人账号 MTProto 全能力（USER_SESSION）。
 */
@Service
public class TelegramRelayService {

    public static final String DEFAULT_BASE_URL = TelegramRelayConstants.DEFAULT_BASE_URL;

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

    /**
     * 发送或回复文本消息，自动根据连接器认证模式路由至 Bot API 或个人账号 MTProto 桥接服务。
     */
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
        String targetChatId = resolveChatId(chatId, cfg);
        if (!StringUtils.hasText(text)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_TEXT_REQUIRED);
        }
        String effectiveParseMode = StringUtils.hasText(parseMode) ? parseMode.trim() : cfg.getParseMode();

        if (profile.getAuthMode() == AuthMode.USER_SESSION) {
            Map<String, Object> payload = buildUserBridgePayload(cfg);
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_TEXT, text);
            if (StringUtils.hasText(effectiveParseMode)) {
                payload.put(TelegramRelayConstants.BRIDGE_FIELD_PARSE_MODE, effectiveParseMode);
            }
            if (Boolean.TRUE.equals(disableNotification)) {
                payload.put(TelegramRelayConstants.BRIDGE_FIELD_DISABLE_NOTIFICATION, true);
            }
            if (replyToMessageId != null && replyToMessageId > 0) {
                payload.put(TelegramRelayConstants.BRIDGE_FIELD_REPLY_TO_MESSAGE_ID, replyToMessageId);
            }
            return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_SEND, TelegramRelayConstants.ACTION_USER_SEND, payload);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.FIELD_CHAT_ID, targetChatId);
        payload.put(TelegramRelayConstants.FIELD_TEXT, text);
        if (StringUtils.hasText(effectiveParseMode)) {
            payload.put(TelegramRelayConstants.FIELD_PARSE_MODE, effectiveParseMode);
        }
        if (disableNotification != null) {
            payload.put(TelegramRelayConstants.FIELD_DISABLE_NOTIFICATION, disableNotification);
        }
        if (replyToMessageId != null) {
            payload.put(TelegramRelayConstants.FIELD_REPLY_PARAMETERS, Map.of(TelegramRelayConstants.FIELD_MESSAGE_ID, replyToMessageId));
        }
        return executeBotMethod(profile, TelegramRelayConstants.METHOD_SEND_MESSAGE, payload);
    }

    /**
     * 拉取机器人更新（仅 BOT_TOKEN 模式）。
     */
    public RelayResponse getUpdates(String profileId, Long offset, Integer limit, Integer timeoutSeconds) {
        ConnectorProfile profile = requireBotProfile(profileId);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (offset != null) {
            payload.put(TelegramRelayConstants.FIELD_OFFSET, offset);
        }
        if (limit != null) {
            payload.put(TelegramRelayConstants.FIELD_LIMIT, Math.max(1, Math.min(limit, 100)));
        }
        if (timeoutSeconds != null) {
            payload.put(TelegramRelayConstants.FIELD_TIMEOUT, Math.max(0, Math.min(timeoutSeconds, 30)));
        }
        return executeBotMethod(profile, TelegramRelayConstants.METHOD_GET_UPDATES, payload);
    }

    /**
     * 透传调用任意 Bot API 方法（仅 BOT_TOKEN 模式）。
     */
    public RelayResponse callMethod(String profileId, String method, Map<String, Object> payload) {
        ConnectorProfile profile = requireBotProfile(profileId);
        return executeBotMethod(profile, method, payload);
    }

    // =========================================================================
    // Telegram 个人账号 MTProto (gotd/td) 专属能力
    // =========================================================================

    /**
     * 获取当前个人账号的对话/群组/超级群/频道列表，并预热 Peer 缓存。
     */
    public RelayResponse getDialogs(String profileId, Integer limit, Integer offsetDate, Integer folderId) {
        ConnectorProfile profile = requireUserProfile(profileId);
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        if (limit != null) {
            payload.put(TelegramRelayConstants.FIELD_LIMIT, Math.max(1, Math.min(limit, 100)));
        }
        if (offsetDate != null && offsetDate > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_OFFSET_DATE, offsetDate);
        }
        if (folderId != null && folderId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_FOLDER_ID, folderId);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_DIALOGS, TelegramRelayConstants.ACTION_USER_DIALOGS, payload);
    }

    /**
     * 读取指定群组、频道或私聊的历史消息。
     */
    public RelayResponse getHistory(String profileId, String chatId, Integer limit, Integer offsetId, Integer minId, Integer maxId) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String targetChatId = resolveChatId(chatId, profile.getConfig());
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
        if (limit != null) {
            payload.put(TelegramRelayConstants.FIELD_LIMIT, Math.max(1, Math.min(limit, 100)));
        }
        if (offsetId != null && offsetId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_OFFSET_ID, offsetId);
        }
        if (minId != null && minId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_MIN_ID, minId);
        }
        if (maxId != null && maxId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_MAX_ID, maxId);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_HISTORY, TelegramRelayConstants.ACTION_USER_HISTORY, payload);
    }

    /**
     * 在指定群组内或全账号范围内搜索历史消息。
     */
    public RelayResponse searchMessages(String profileId, String chatId, String query, Integer limit, Integer offsetId) {
        ConnectorProfile profile = requireUserProfile(profileId);
        if (!StringUtils.hasText(query)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_SEARCH_QUERY_REQUIRED);
        }
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        if (StringUtils.hasText(chatId)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, chatId.trim());
        }
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_QUERY, query.trim());
        if (limit != null) {
            payload.put(TelegramRelayConstants.FIELD_LIMIT, Math.max(1, Math.min(limit, 100)));
        }
        if (offsetId != null && offsetId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_OFFSET_ID, offsetId);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_SEARCH, TelegramRelayConstants.ACTION_USER_SEARCH, payload);
    }

    /**
     * 编辑已发送的消息内容。
     */
    public RelayResponse editMessage(String profileId, String chatId, Integer messageId, String text, String parseMode) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String targetChatId = resolveChatId(chatId, profile.getConfig());
        if (messageId == null || messageId <= 0) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_MESSAGE_ID_REQUIRED);
        }
        if (!StringUtils.hasText(text)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_TEXT_REQUIRED);
        }
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_MESSAGE_ID, messageId);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_TEXT, text);
        String effectiveParseMode = StringUtils.hasText(parseMode) ? parseMode.trim() : profile.getConfig().getParseMode();
        if (StringUtils.hasText(effectiveParseMode)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PARSE_MODE, effectiveParseMode);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_EDIT, TelegramRelayConstants.ACTION_USER_EDIT, payload);
    }

    /**
     * 删除一条或多条消息。
     */
    public RelayResponse deleteMessages(String profileId, String chatId, List<Integer> messageIds, Boolean revoke) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String targetChatId = resolveChatId(chatId, profile.getConfig());
        if (messageIds == null || messageIds.isEmpty()) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_MESSAGE_IDS_REQUIRED);
        }
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_MESSAGE_IDS, messageIds);
        if (revoke != null) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_REVOKE, revoke);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_DELETE, TelegramRelayConstants.ACTION_USER_DELETE, payload);
    }

    /**
     * 转发消息到另一个聊天或群组。
     */
    public RelayResponse forwardMessages(
            String profileId,
            String fromChatId,
            String toChatId,
            List<Integer> messageIds,
            Boolean dropAuthor,
            Boolean silent
    ) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String resolvedTo = StringUtils.hasText(toChatId) ? toChatId.trim() : profile.getConfig().getDefaultChatId();
        if (!StringUtils.hasText(fromChatId) || !StringUtils.hasText(resolvedTo)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_FORWARD_CHATS_REQUIRED);
        }
        if (messageIds == null || messageIds.isEmpty()) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_MESSAGE_IDS_REQUIRED);
        }
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_FROM_CHAT_ID, fromChatId.trim());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_TO_CHAT_ID, resolvedTo);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_MESSAGE_IDS, messageIds);
        if (Boolean.TRUE.equals(dropAuthor)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_DROP_AUTHOR, true);
        }
        if (Boolean.TRUE.equals(silent)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_SILENT, true);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_FORWARD, TelegramRelayConstants.ACTION_USER_FORWARD, payload);
    }

    /**
     * 标记指定群组/聊天的消息已读。
     */
    public RelayResponse markRead(String profileId, String chatId, Integer maxId) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String targetChatId = resolveChatId(chatId, profile.getConfig());
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
        if (maxId != null && maxId > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_MAX_ID, maxId);
        }
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_READ, TelegramRelayConstants.ACTION_USER_READ, payload);
    }

    /**
     * 解析 @username 或 chatId 获取实体详情。
     */
    public RelayResponse resolveChat(String profileId, String chatId) {
        ConnectorProfile profile = requireUserProfile(profileId);
        String targetChatId = resolveChatId(chatId, profile.getConfig());
        Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_CHAT_ID, targetChatId);
        return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_RESOLVE, TelegramRelayConstants.ACTION_USER_RESOLVE, payload);
    }

    // =========================================================================
    // 控制台/API 交互式登录辅助方法（用于获取个人账号 StringSession）
    // =========================================================================

    /**
     * 转发至 gotd/td 侧车发起手机号验证码登录。
     */
    public Object startUserAuth(
            Integer apiId,
            String apiHash,
            String phone,
            String bridgeUrl,
            String bridgeToken,
            String proxyHost,
            Integer proxyPort
    ) {
        if (apiId == null || apiId <= 0 || !StringUtils.hasText(apiHash) || !StringUtils.hasText(phone)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_AUTH_START_REQUIRED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_ID, apiId);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_HASH, apiHash.trim());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_PHONE, phone.trim());
        if (StringUtils.hasText(proxyHost)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_HOST, proxyHost.trim());
        }
        if (proxyPort != null && proxyPort > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_PORT, proxyPort);
        }
        return invokeRawBridge(resolveBridgeUrl(bridgeUrl), resolveBridgeToken(bridgeToken), TelegramRelayConstants.BRIDGE_PATH_AUTH_START, payload);
    }

    /**
     * 转发至 gotd/td 侧车提交验证码与可选 2FA 密码，换取持久化 Session 字符串。
     */
    public Object verifyUserAuth(
            String loginId,
            String code,
            String password,
            String bridgeUrl,
            String bridgeToken
    ) {
        if (!StringUtils.hasText(loginId)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_AUTH_VERIFY_REQUIRED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_LOGIN_ID, loginId.trim());
        if (StringUtils.hasText(code)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_CODE, code.trim());
        }
        if (StringUtils.hasText(password)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PASSWORD, password);
        }
        return invokeRawBridge(resolveBridgeUrl(bridgeUrl), resolveBridgeToken(bridgeToken), TelegramRelayConstants.BRIDGE_PATH_AUTH_VERIFY, payload);
    }

    /**
     * 转发至 gotd/td 侧车发起 Telegram 二维码扫码登录，并自动生成可直接展示的 SVG 二维码。
     */
    public Object startQrUserAuth(
            Integer apiId,
            String apiHash,
            String bridgeUrl,
            String bridgeToken,
            String proxyHost,
            Integer proxyPort
    ) {
        if (apiId == null || apiId <= 0 || !StringUtils.hasText(apiHash)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_QR_START_REQUIRED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_ID, apiId);
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_HASH, apiHash.trim());
        if (StringUtils.hasText(proxyHost)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_HOST, proxyHost.trim());
        }
        if (proxyPort != null && proxyPort > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_PORT, proxyPort);
        }
        Object resp = invokeRawBridge(resolveBridgeUrl(bridgeUrl), resolveBridgeToken(bridgeToken), TelegramRelayConstants.BRIDGE_PATH_AUTH_QR_START, payload);
        return attachQrSvgIfPresent(resp);
    }

    /**
     * 轮询 Telegram 二维码扫码登录状态；若开启 2FA 则附带 password 完成二次校验。
     */
    public Object pollQrUserAuth(
            String qrLoginId,
            String password,
            String bridgeUrl,
            String bridgeToken
    ) {
        if (!StringUtils.hasText(qrLoginId)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_QR_POLL_REQUIRED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_QR_LOGIN_ID, qrLoginId.trim());
        if (StringUtils.hasText(password)) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PASSWORD, password);
        }
        Object resp = invokeRawBridge(resolveBridgeUrl(bridgeUrl), resolveBridgeToken(bridgeToken), TelegramRelayConstants.BRIDGE_PATH_AUTH_QR_POLL, payload);
        return attachQrSvgIfPresent(resp);
    }

    private Object attachQrSvgIfPresent(Object resp) {
        if (resp instanceof ObjectNode root) {
            JsonNode target = root.has(TelegramRelayConstants.FIELD_RESULT)
                    ? root.get(TelegramRelayConstants.FIELD_RESULT)
                    : root;
            if (target instanceof ObjectNode targetObj && targetObj.has(TelegramRelayConstants.BRIDGE_FIELD_QR_URL)) {
                String qrUrl = targetObj.get(TelegramRelayConstants.BRIDGE_FIELD_QR_URL).asText();
                if (StringUtils.hasText(qrUrl)) {
                    targetObj.put(TelegramRelayConstants.BRIDGE_FIELD_QR_SVG, QrCodeSvgEncoder.encodeToSvg(qrUrl));
                }
            }
        }
        return resp;
    }

    /**
     * 执行连接器连通性测试（Bot 模式调 getMe，个人账号模式调侧车 /bridge/me）。
     */
    public RelayResponse test(ConnectorProfile profile) {
        if (profile.getAuthMode() == AuthMode.USER_SESSION) {
            Map<String, Object> payload = buildUserBridgePayload(profile.getConfig());
            return executeUserBridge(profile, TelegramRelayConstants.BRIDGE_PATH_ME, TelegramRelayConstants.ACTION_USER_ME, payload);
        }
        return executeBotMethod(profile, TelegramRelayConstants.METHOD_GET_ME, Map.of());
    }

    // =========================================================================
    // 内部执行逻辑
    // =========================================================================

    private ConnectorProfile requireBotProfile(String profileId) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.TELEGRAM);
        if (profile.getAuthMode() != AuthMode.BOT_TOKEN) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_BOT_TOKEN_ONLY);
        }
        return profile;
    }

    private ConnectorProfile requireUserProfile(String profileId) {
        ConnectorProfile profile = connectorService.getEnabledProfile(profileId, ConnectorType.TELEGRAM);
        if (profile.getAuthMode() != AuthMode.USER_SESSION) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_USER_SESSION_ONLY);
        }
        return profile;
    }

    private String resolveChatId(String chatId, ConnectorConfig cfg) {
        String targetChatId = StringUtils.hasText(chatId) ? chatId.trim() : cfg.getDefaultChatId();
        if (!StringUtils.hasText(targetChatId)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_CHAT_ID_REQUIRED);
        }
        return targetChatId;
    }

    private Map<String, Object> buildUserBridgePayload(ConnectorConfig cfg) {
        if (cfg.getTelegramApiId() == null || cfg.getTelegramApiId() <= 0
                || !StringUtils.hasText(cfg.getTelegramApiHash())
                || !StringUtils.hasText(cfg.getTelegramSession())) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_USER_CREDENTIALS_MISSING);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_ID, cfg.getTelegramApiId());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_API_HASH, cfg.getTelegramApiHash());
        payload.put(TelegramRelayConstants.BRIDGE_FIELD_SESSION_STRING, cfg.getTelegramSession());
        if (StringUtils.hasText(cfg.getProxyHost())) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_HOST, cfg.getProxyHost());
        }
        if (cfg.getProxyPort() != null && cfg.getProxyPort() > 0) {
            payload.put(TelegramRelayConstants.BRIDGE_FIELD_PROXY_PORT, cfg.getProxyPort());
        }
        return payload;
    }

    private String resolveBridgeUrl(String overrideUrl) {
        String raw = StringUtils.hasText(overrideUrl) ? overrideUrl.trim() : properties.getTelegramBridgeUrl();
        String normalized = raw.replaceAll(HttpConstants.TRAILING_SLASH_REGEX, RouteConstants.EMPTY);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            boolean validScheme = HttpConstants.SCHEME_HTTP.equalsIgnoreCase(scheme)
                    || HttpConstants.SCHEME_HTTPS.equalsIgnoreCase(scheme);
            if (!validScheme || !StringUtils.hasText(uri.getHost())) {
                throw new ConnectorException(
                        HttpStatus.BAD_REQUEST,
                        String.format(ExceptionMessageConstants.TELEGRAM_BRIDGE_URL_INVALID_FMT, raw)
                );
            }
            return normalized;
        } catch (ConnectorException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ConnectorException(
                    HttpStatus.BAD_REQUEST,
                    String.format(ExceptionMessageConstants.TELEGRAM_BRIDGE_URL_INVALID_FMT, raw)
            );
        }
    }

    private String resolveBridgeToken(String overrideToken) {
        return StringUtils.hasText(overrideToken) ? overrideToken.trim() : properties.getTelegramBridgeToken();
    }

    /**
     * 调用 gotd/td 侧车服务，注意此处直连侧车地址，不将 Telegram 代理应用到本机/内网侧车 HTTP 请求上。
     */
    private RelayResponse executeUserBridge(
            ConnectorProfile profile,
            String bridgePath,
            String action,
            Map<String, Object> payload
    ) {
        long start = System.currentTimeMillis();
        ConnectorConfig cfg = profile.getConfig();
        String baseUrl = resolveBridgeUrl(cfg.getApiBaseUrl());
        String bridgeToken = resolveBridgeToken(cfg.getTelegramBridgeToken());
        URI uri = URI.create(baseUrl + bridgePath);

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, Math.max(properties.getDefaultTimeoutMs(), 30000));
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.MIME_APPLICATION_JSON)
                    .header(HttpConstants.HEADER_ACCEPT, HttpConstants.MIME_APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (StringUtils.hasText(bridgeToken)) {
                reqBuilder.header(TelegramRelayConstants.HEADER_BRIDGE_TOKEN, bridgeToken);
            }

            // 构造仅带超时设置的直连客户端配置，避免误走外部代理访问内网侧车
            ConnectorConfig directCfg = new ConnectorConfig();
            directCfg.setTimeoutMs(timeoutMs);
            HttpClient client = httpClientFactory.getClient(directCfg, timeoutMs);
            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - start;

            Object parsed = parseJsonOrText(resp.body());
            boolean apiOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
            String errMsg = ExceptionMessageConstants.TELEGRAM_API_ERROR;
            if (parsed instanceof JsonNode node) {
                if (node.has(TelegramRelayConstants.FIELD_OK)) {
                    apiOk = apiOk && node.get(TelegramRelayConstants.FIELD_OK).asBoolean(false);
                }
                if (!apiOk && node.has(TelegramRelayConstants.FIELD_ERROR)) {
                    errMsg = ExceptionMessageConstants.TELEGRAM_BRIDGE_ERROR_PREFIX
                            + node.get(TelegramRelayConstants.FIELD_ERROR).asText();
                }
            }

            activityBuffer.record(
                    profile.getId(), ConnectorType.TELEGRAM,
                    action, apiOk, resp.statusCode(), durationMs,
                    apiOk ? HttpConstants.SUMMARY_OK : (HttpConstants.SUMMARY_HTTP_PREFIX + resp.statusCode())
            );
            return apiOk
                    ? RelayResponse.ok(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs, parsed)
                    : RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs, errMsg, parsed);
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            String errMsg = formatBridgeTransportError(baseUrl, ex);
            activityBuffer.record(profile.getId(), ConnectorType.TELEGRAM, action, false, 502, durationMs, errMsg);
            return RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, 502, durationMs, errMsg, null);
        }
    }

    private Object invokeRawBridge(String baseUrl, String bridgeToken, String path, Map<String, Object> payload) {
        URI uri = URI.create(baseUrl + path);
        try {
            int timeoutMs = Math.max(properties.getDefaultTimeoutMs(), 45000);
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.MIME_APPLICATION_JSON)
                    .header(HttpConstants.HEADER_ACCEPT, HttpConstants.MIME_APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (StringUtils.hasText(bridgeToken)) {
                reqBuilder.header(TelegramRelayConstants.HEADER_BRIDGE_TOKEN, bridgeToken);
            }
            ConnectorConfig directCfg = new ConnectorConfig();
            directCfg.setTimeoutMs(timeoutMs);
            HttpClient client = httpClientFactory.getClient(directCfg, timeoutMs);
            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Object parsed = parseJsonOrText(resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String msg = (parsed instanceof JsonNode node && node.has(TelegramRelayConstants.FIELD_ERROR))
                        ? node.get(TelegramRelayConstants.FIELD_ERROR).asText()
                        : (HttpConstants.SUMMARY_HTTP_PREFIX + resp.statusCode());
                throw new ConnectorException(HttpStatus.BAD_GATEWAY, ExceptionMessageConstants.TELEGRAM_BRIDGE_ERROR_PREFIX + msg);
            }
            return parsed;
        } catch (ConnectorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ConnectorException(HttpStatus.BAD_GATEWAY, formatBridgeTransportError(baseUrl, ex));
        }
    }

    private RelayResponse executeBotMethod(ConnectorProfile profile, String method, Map<String, Object> payload) {
        if (!StringUtils.hasText(method) || !method.matches(TelegramRelayConstants.METHOD_NAME_REGEX)) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_INVALID_METHOD_NAME);
        }
        long start = System.currentTimeMillis();
        ConnectorConfig cfg = profile.getConfig();
        if (!StringUtils.hasText(cfg.getBotToken())) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.TELEGRAM_BOT_TOKEN_MISSING);
        }

        String baseUrl = StringUtils.hasText(cfg.getApiBaseUrl())
                ? cfg.getApiBaseUrl().replaceAll(HttpConstants.TRAILING_SLASH_REGEX, RouteConstants.EMPTY)
                : DEFAULT_BASE_URL;
        URI uri = URI.create(baseUrl + TelegramRelayConstants.BOT_PATH_PREFIX + cfg.getBotToken() + HttpConstants.SLASH + method);

        try {
            String jsonBody = objectMapper.writeValueAsString(payload != null ? payload : Map.of());
            int timeoutMs = httpClientFactory.resolveTimeoutMs(cfg, properties.getDefaultTimeoutMs());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.MIME_APPLICATION_JSON)
                    .header(HttpConstants.HEADER_ACCEPT, HttpConstants.MIME_APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpClient client = httpClientFactory.getClient(cfg, properties.getDefaultTimeoutMs());
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - start;

            Object parsed = parseJsonOrText(resp.body());
            boolean apiOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
            if (parsed instanceof JsonNode node && node.has(TelegramRelayConstants.FIELD_OK)) {
                apiOk = apiOk && node.get(TelegramRelayConstants.FIELD_OK).asBoolean(false);
            }

            activityBuffer.record(
                    profile.getId(), ConnectorType.TELEGRAM,
                    method, apiOk, resp.statusCode(), durationMs,
                    apiOk ? HttpConstants.SUMMARY_OK : (HttpConstants.SUMMARY_HTTP_PREFIX + resp.statusCode())
            );
            return apiOk
                    ? RelayResponse.ok(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs, parsed)
                    : RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, resp.statusCode(), durationMs,
                    ExceptionMessageConstants.TELEGRAM_API_ERROR, parsed);
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - start;
            String detail = formatExceptionDetail(ex);
            activityBuffer.record(profile.getId(), ConnectorType.TELEGRAM, method, false, 502, durationMs, detail);
            return RelayResponse.fail(profile.getId(), ConnectorType.TELEGRAM, 502, durationMs,
                    ExceptionMessageConstants.TELEGRAM_RELAY_FAILED_PREFIX + detail, null);
        }
    }

    /**
     * 将调用 gotd/td 侧车时的底层网络异常格式化为可读提示，避免 JDK HttpClient ConnectException.getMessage() 为 null。
     */
    private String formatBridgeTransportError(String baseUrl, Exception ex) {
        if (hasCause(ex, ConnectException.class)
                || hasCause(ex, UnknownHostException.class)
                || hasCause(ex, UnresolvedAddressException.class)) {
            return String.format(ExceptionMessageConstants.TELEGRAM_BRIDGE_UNREACHABLE_FMT, baseUrl);
        }
        if (hasCause(ex, HttpTimeoutException.class)) {
            return String.format(ExceptionMessageConstants.TELEGRAM_BRIDGE_TIMEOUT_FMT, baseUrl, formatExceptionDetail(ex));
        }
        return ExceptionMessageConstants.TELEGRAM_RELAY_FAILED_PREFIX + formatExceptionDetail(ex);
    }

    private String formatExceptionDetail(Throwable ex) {
        Throwable cursor = ex;
        Throwable last = ex;
        while (cursor != null) {
            if (StringUtils.hasText(cursor.getMessage())) {
                return cursor.getClass().getSimpleName() + HttpConstants.COLON_SPACE + cursor.getMessage();
            }
            last = cursor;
            cursor = (cursor.getCause() == cursor) ? null : cursor.getCause();
        }
        return last.getClass().getSimpleName();
    }

    private boolean hasCause(Throwable ex, Class<? extends Throwable> targetType) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (targetType.isInstance(cursor)) {
                return true;
            }
            cursor = (cursor.getCause() == cursor) ? null : cursor.getCause();
        }
        return false;
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
