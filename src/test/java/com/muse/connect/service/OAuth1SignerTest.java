package com.muse.connect.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth1SignerTest {

    @Test
    void buildsDeterministicRfc5849SignatureHeader() {
        String header = OAuth1Signer.buildAuthorizationHeader(
                "POST",
                URI.create("https://api.x.com/2/tweets"),
                Map.of("include_entities", "true"),
                "xvz1evFS4wEEPTGEFPHBog",
                "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
                "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
                "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
                "1318622958",
                "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg"
        );

        assertThat(header).startsWith("OAuth ");
        assertThat(header).contains("oauth_consumer_key=\"xvz1evFS4wEEPTGEFPHBog\"");
        assertThat(header).contains("oauth_nonce=\"kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg\"");
        assertThat(header).contains("oauth_signature_method=\"HMAC-SHA1\"");
        assertThat(header).contains("oauth_timestamp=\"1318622958\"");
        assertThat(header).contains("oauth_token=\"370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb\"");
        assertThat(header).contains("oauth_version=\"1.0\"");
        assertThat(header).contains("oauth_signature=\"");
    }

    @Test
    void percentEncodesSpecialCharactersPerRfc5849() {
        assertThat(OAuth1Signer.percentEncode("hello world!*~")).isEqualTo("hello%20world%21%2A~");
    }
}
