package com.muse.connect.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.muse.connect.constant.SecurityConstants;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectorConfig {

    public static final String MASK = SecurityConstants.SECRET_MASK;

    // 通用 HTTP 与网络配置字段
    private String apiBaseUrl;
    private String proxyHost;
    private Integer proxyPort;
    private Integer timeoutMs;

    // X (Twitter) 连接器字段
    private String bearerToken;
    private String apiKey;
    private String apiSecret;
    private String accessToken;
    private String accessTokenSecret;
    private String authToken;
    private String ct0;
    private String graphqlQueryIds;

    // Telegram 连接器字段
    private String botToken;
    private String defaultChatId;
    private String parseMode;

    // SSH 连接器字段
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String privateKey;
    private String passphrase;
    private Boolean strictHostKeyChecking;
    private String allowedCommandPrefixes;

    public ConnectorConfig maskedCopy() {
        ConnectorConfig copy = new ConnectorConfig();
        copy.apiBaseUrl = this.apiBaseUrl;
        copy.proxyHost = this.proxyHost;
        copy.proxyPort = this.proxyPort;
        copy.timeoutMs = this.timeoutMs;
        copy.bearerToken = maskIfPresent(this.bearerToken);
        copy.apiKey = this.apiKey;
        copy.apiSecret = maskIfPresent(this.apiSecret);
        copy.accessToken = maskIfPresent(this.accessToken);
        copy.accessTokenSecret = maskIfPresent(this.accessTokenSecret);
        copy.authToken = maskIfPresent(this.authToken);
        copy.ct0 = maskIfPresent(this.ct0);
        copy.graphqlQueryIds = this.graphqlQueryIds;
        copy.botToken = maskIfPresent(this.botToken);
        copy.defaultChatId = this.defaultChatId;
        copy.parseMode = this.parseMode;
        copy.host = this.host;
        copy.port = this.port;
        copy.username = this.username;
        copy.password = maskIfPresent(this.password);
        copy.privateKey = maskIfPresent(this.privateKey);
        copy.passphrase = maskIfPresent(this.passphrase);
        copy.strictHostKeyChecking = this.strictHostKeyChecking;
        copy.allowedCommandPrefixes = this.allowedCommandPrefixes;
        return copy;
    }

    public void retainSecretsFrom(ConnectorConfig existing) {
        if (existing == null) {
            return;
        }
        this.bearerToken = pickSecret(this.bearerToken, existing.bearerToken);
        this.apiSecret = pickSecret(this.apiSecret, existing.apiSecret);
        this.accessToken = pickSecret(this.accessToken, existing.accessToken);
        this.accessTokenSecret = pickSecret(this.accessTokenSecret, existing.accessTokenSecret);
        this.authToken = pickSecret(this.authToken, existing.authToken);
        this.ct0 = pickSecret(this.ct0, existing.ct0);
        this.botToken = pickSecret(this.botToken, existing.botToken);
        this.password = pickSecret(this.password, existing.password);
        this.privateKey = pickSecret(this.privateKey, existing.privateKey);
        this.passphrase = pickSecret(this.passphrase, existing.passphrase);
    }

    private static String maskIfPresent(String val) {
        return (val != null && !val.isBlank()) ? MASK : null;
    }

    private static String pickSecret(String incoming, String existing) {
        if (incoming == null || incoming.isBlank() || MASK.equals(incoming.trim())) {
            return existing;
        }
        return incoming;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = trimToNull(apiBaseUrl);
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = trimToNull(proxyHost);
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = trimToNull(bearerToken);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = trimToNull(apiKey);
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = trimToNull(apiSecret);
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = trimToNull(accessToken);
    }

    public String getAccessTokenSecret() {
        return accessTokenSecret;
    }

    public void setAccessTokenSecret(String accessTokenSecret) {
        this.accessTokenSecret = trimToNull(accessTokenSecret);
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = trimToNull(authToken);
    }

    public String getCt0() {
        return ct0;
    }

    public void setCt0(String ct0) {
        this.ct0 = trimToNull(ct0);
    }

    public String getGraphqlQueryIds() {
        return graphqlQueryIds;
    }

    public void setGraphqlQueryIds(String graphqlQueryIds) {
        this.graphqlQueryIds = trimToNull(graphqlQueryIds);
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = trimToNull(botToken);
    }

    public String getDefaultChatId() {
        return defaultChatId;
    }

    public void setDefaultChatId(String defaultChatId) {
        this.defaultChatId = trimToNull(defaultChatId);
    }

    public String getParseMode() {
        return parseMode;
    }

    public void setParseMode(String parseMode) {
        this.parseMode = trimToNull(parseMode);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = trimToNull(host);
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = trimToNull(username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = (password != null && !password.isEmpty()) ? password : null;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = trimToNull(privateKey);
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = (passphrase != null && !passphrase.isEmpty()) ? passphrase : null;
    }

    public Boolean getStrictHostKeyChecking() {
        return strictHostKeyChecking;
    }

    public void setStrictHostKeyChecking(Boolean strictHostKeyChecking) {
        this.strictHostKeyChecking = strictHostKeyChecking;
    }

    public String getAllowedCommandPrefixes() {
        return allowedCommandPrefixes;
    }

    public void setAllowedCommandPrefixes(String allowedCommandPrefixes) {
        this.allowedCommandPrefixes = trimToNull(allowedCommandPrefixes);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
