package com.muse.connect.service;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;

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
        oauthParams.put(HttpConstants.OAUTH_CONSUMER_KEY, consumerKey);
        oauthParams.put(HttpConstants.OAUTH_NONCE, nonce);
        oauthParams.put(HttpConstants.OAUTH_SIGNATURE_METHOD, HttpConstants.OAUTH_SIGNATURE_METHOD_HMAC_SHA1);
        oauthParams.put(HttpConstants.OAUTH_TIMESTAMP, timestamp);
        oauthParams.put(HttpConstants.OAUTH_TOKEN, accessToken);
        oauthParams.put(HttpConstants.OAUTH_VERSION, HttpConstants.OAUTH_VERSION_1_0);

        for (Map.Entry<String, String> entry : oauthParams.entrySet()) {
            allParams.put(percentEncode(entry.getKey()), percentEncode(entry.getValue()));
        }

        String paramString = allParams.entrySet().stream()
                .map(e -> e.getKey() + HttpConstants.EQUALS + e.getValue())
                .collect(Collectors.joining(RouteConstants.QUERY_AMP));

        String baseUrl = requestUri.getScheme() + HttpConstants.SCHEME_SEPARATOR
                + requestUri.getAuthority() + requestUri.getRawPath();
        String baseString = httpMethod.toUpperCase() + RouteConstants.QUERY_AMP
                + percentEncode(baseUrl) + RouteConstants.QUERY_AMP
                + percentEncode(paramString);

        String signingKey = percentEncode(consumerSecret) + RouteConstants.QUERY_AMP + percentEncode(accessTokenSecret);
        String signature = hmacSha1Base64(baseString, signingKey);

        oauthParams.put(HttpConstants.OAUTH_SIGNATURE, signature);
        return HttpConstants.AUTH_PREFIX_OAUTH + oauthParams.entrySet().stream()
                .map(e -> percentEncode(e.getKey()) + HttpConstants.OAUTH_HEADER_QUOTE_OPEN
                        + percentEncode(e.getValue()) + HttpConstants.OAUTH_HEADER_QUOTE_CLOSE)
                .collect(Collectors.joining(HttpConstants.OAUTH_HEADER_PAIR_SEP));
    }

    static String percentEncode(String value) {
        if (value == null) {
            return RouteConstants.EMPTY;
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace(HttpConstants.PLUS, HttpConstants.ENCODED_SPACE)
                .replace(HttpConstants.ASTERISK, HttpConstants.ENCODED_ASTERISK)
                .replace(HttpConstants.ENCODED_TILDE, HttpConstants.TILDE);
    }

    private static String hmacSha1Base64(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HttpConstants.HMAC_SHA1_ALGORITHM);
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HttpConstants.HMAC_SHA1_ALGORITHM);
            mac.init(spec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(ExceptionMessageConstants.OAUTH1_SIGN_FAILED, e);
        }
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
