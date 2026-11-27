package com.muse.connect.config;

import com.muse.connect.constant.SecurityConstants;
import com.muse.connect.constant.StorageConstants;
import com.muse.connect.constant.TelegramRelayConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = StorageConstants.PREFIX_MUSE_RELAY)
public class AppProperties {

    public static final String DEFAULT_API_KEY = SecurityConstants.DEFAULT_API_KEY;

    private String apiKey = DEFAULT_API_KEY;
    private int defaultTimeoutMs = 15000;
    private int activityBufferSize = 100;
    /** Telegram 个人账号 MTProto (gotd/td) 侧车默认地址 */
    private String telegramBridgeUrl = TelegramRelayConstants.DEFAULT_BRIDGE_URL;
    /** Telegram 个人账号侧车默认共享鉴权令牌（可选） */
    private String telegramBridgeToken = "";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? DEFAULT_API_KEY : apiKey.trim();
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : 15000;
    }

    public int getActivityBufferSize() {
        return activityBufferSize;
    }

    public void setActivityBufferSize(int activityBufferSize) {
        this.activityBufferSize = activityBufferSize > 0 ? activityBufferSize : 100;
    }

    public String getTelegramBridgeUrl() {
        return telegramBridgeUrl;
    }

    public void setTelegramBridgeUrl(String telegramBridgeUrl) {
        this.telegramBridgeUrl = (telegramBridgeUrl == null || telegramBridgeUrl.isBlank())
                ? TelegramRelayConstants.DEFAULT_BRIDGE_URL
                : telegramBridgeUrl.trim();
    }

    public String getTelegramBridgeToken() {
        return telegramBridgeToken;
    }

    public void setTelegramBridgeToken(String telegramBridgeToken) {
        this.telegramBridgeToken = telegramBridgeToken == null ? "" : telegramBridgeToken.trim();
    }
}
