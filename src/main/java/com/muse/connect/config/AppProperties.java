package com.muse.connect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "muse.relay")
public class AppProperties {

    public static final String DEFAULT_API_KEY = "123456789";

    private String apiKey = DEFAULT_API_KEY;
    private int defaultTimeoutMs = 15000;
    private int activityBufferSize = 100;

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
}
