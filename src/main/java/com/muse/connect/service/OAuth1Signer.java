package com.muse.connect.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 面向 X API 请求的 RFC 5849 OAuth 1.0a HMAC-SHA1 Authorization 请求头生成器。
 */
public final class OAuth1Signer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth1Signer() {
    }

    public static String buildAuthorizationHeader(
            String httpMethod,
            URI requestUri,
            Map<String, String> queryParams,
            String consumerKey,
            String consumerSecret,
            String accessToken,
            String accessTokenSecret
    ) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = generateNonce();
        return buildAuthorizationHeader(
                httpMethod, requestUri, queryParams,
                consumerKey, consumerSecret, accessToken, accessTokenSecret,
                timestamp, nonce
        );
    }

    static String buildAuthorizationHeader(
            String httpMethod,
            URI requestUri,
            Map<String, String> queryParams,
            String consumerKey,
            String consumerSecret,
            String accessToken,
            String accessTokenSecret,
            String timestamp,
            String nonce
    ) {
        TreeMap<String, String> allParams = new TreeMap<>();
        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    allParams.put(percentEncode(entry.getKey()), percentEncode(entry.getValue()));
                }
            }
        }

        TreeMap<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_signature_method", "HMAC-SHA1");
        oauthParams.put("oauth_timestamp", timestamp);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_version", "1.0");

        for (Map.Entry<String, String> entry : oauthParams.entrySet()) {
            allParams.put(percentEncode(entry.getKey()), percentEncode(entry.getValue()));
        }

        String paramString = allParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        String baseUrl = requestUri.getScheme() + "://" + requestUri.getAuthority() + requestUri.getRawPath();
        String baseString = httpMethod.toUpperCase() + "&"
                + percentEncode(baseUrl) + "&"
                + percentEncode(paramString);

        String signingKey = percentEncode(consumerSecret) + "&" + percentEncode(accessTokenSecret);
        String signature = hmacSha1Base64(baseString, signingKey);

        oauthParams.put("oauth_signature", signature);
        return "OAuth " + oauthParams.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + "=\"" + percentEncode(e.getValue()) + "\"")
                .collect(Collectors.joining(", "));
    }

    static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String hmacSha1Base64(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(spec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute OAuth1 HMAC-SHA1 signature", e);
        }
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
