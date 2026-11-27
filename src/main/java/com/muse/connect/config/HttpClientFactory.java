package com.muse.connect.config;

import com.muse.connect.model.ConnectorConfig;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reuses HttpClient instances by proxy key to avoid creating selector threads per relay call.
 */
@Component
public class HttpClientFactory {

    private final ConcurrentHashMap<String, HttpClient> clientCache = new ConcurrentHashMap<>();

    public HttpClient getClient(ConnectorConfig config, int defaultTimeoutMs) {
        String proxyHost = config.getProxyHost() != null ? config.getProxyHost().trim() : "";
        Integer proxyPort = config.getProxyPort();
        String cacheKey = (!proxyHost.isEmpty() && proxyPort != null && proxyPort > 0)
                ? proxyHost + ":" + proxyPort
                : "DIRECT";

        int connectTimeout = Math.min(resolveTimeoutMs(config, defaultTimeoutMs), 10_000);
        return clientCache.computeIfAbsent(cacheKey, key -> {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeout))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (!"DIRECT".equals(key)) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            }
            return builder.build();
        });
    }

    public int resolveTimeoutMs(ConnectorConfig config, int defaultTimeoutMs) {
        Integer custom = config.getTimeoutMs();
        return (custom != null && custom > 0) ? custom : defaultTimeoutMs;
    }
}
