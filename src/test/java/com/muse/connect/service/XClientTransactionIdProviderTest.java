package com.muse.connect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.HttpClientFactory;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.repository.RelayActivityBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class XClientTransactionIdProviderTest {

    private static final String SAMPLE_VK_B64 =
            Base64.getEncoder().encodeToString(new byte[]{
                    10, 20, 30, 40, 50, 1, 70, 80, 90, 100, 110, 120, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
            });

    private static final String SAMPLE_ANIM_PATH =
            "M 1 0 0 C"
                    + " 100 120 140 200 210 220 128 64 128 192 64"
                    + "C 110 130 150 205 215 225 140 70 130 190 70"
                    + "C 120 140 160 210 220 230 150 75 135 185 75"
                    + "C 130 150 170 215 225 235 160 80 140 180 80"
                    + "C 140 160 180 220 230 240 170 85 145 175 85"
                    + "C 150 170 190 225 235 245 180 90 150 170 90"
                    + "C 160 180 200 230 240 250 190 95 155 165 95"
                    + "C 170 190 210 235 245 255 200 100 160 160 100"
                    + "C 10 20 30 40 50 60 70 80 90 100 110"
                    + "C 15 25 35 45 55 65 75 85 95 105 115"
                    + "C 20 30 40 50 60 70 80 90 100 110 120"
                    + "C 25 35 45 55 65 75 85 95 105 115 125"
                    + "C 30 40 50 60 70 80 90 100 110 120 130"
                    + "C 35 45 55 65 75 85 95 105 115 125 135"
                    + "C 40 50 60 70 80 90 100 110 120 130 140"
                    + "C 45 55 65 75 85 95 105 115 125 135 145";

    private static final String SAMPLE_SIGNING_JS =
            "function f(a){return [parseInt(a[2], 16),parseInt(a[4], 16),parseInt(a[6], 16),parseInt(a[8], 16)]}";

    @Test
    void parsesHtmlKeysAndGeneratesValidTransactionIdPayload() {
        String html = """
                <html><head>
                <meta name="twitter-site-verification" content="%s" />
                </head><body>
                <svg id="loading-x-anim-0"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                <svg id="loading-x-anim-1"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                <script>{"12":"ondemand.s","12":"abcdef0123456789"}</script>
                </body></html>
                """.formatted(SAMPLE_VK_B64, SAMPLE_ANIM_PATH, SAMPLE_ANIM_PATH);

        byte[] vk = XClientTransactionIdProvider.parseVerificationKey(html);
        assertThat(vk).hasSize(32);

        List<double[]> rows = XClientTransactionIdProvider.parseAnimationRows(html, vk);
        assertThat(rows).hasSize(16);

        List<Integer> indices = XClientTransactionIdProvider.extractIndicesFromScript(SAMPLE_SIGNING_JS);
        assertThat(indices).containsExactly(2, 4, 6, 8);

        String animKey = XClientTransactionIdProvider.computeAnimKey(vk, rows, indices);
        assertThat(animKey).isNotBlank().doesNotContain(".").doesNotContain("-");

        XClientTransactionIdProvider.TransactionKeys keys =
                new XClientTransactionIdProvider.TransactionKeys(vk, animKey);
        String tid = keys.generate("GET", "/i/api/1.1/account/settings.json", 1730000000_000L, 42);
        assertThat(tid).isNotBlank().doesNotContain("=");

        // 解码经异或混淆的负载并校验字节结构：[mask, vk(32), ts(4), sha16(16), drn(1)]
        byte[] decoded = Base64.getDecoder().decode(tid);
        assertThat(decoded).hasSize(1 + 32 + 4 + 16 + 1);
        int mask = decoded[0] & 0xFF;
        assertThat(mask).isEqualTo(42);
        assertThat((decoded[1] & 0xFF) ^ mask).isEqualTo(10);
        assertThat((decoded[decoded.length - 1] & 0xFF) ^ mask).isEqualTo(3);
    }

    @Test
    void extractsBothLegacyAndXWebScriptUrls() {
        String legacyHtml = """
                <script>var a={10:"ondemand.s",11:"main"};var b={10:"1234567890abcdef",11:"abcdef1"};</script>
                """;
        List<String> legacyUrls = XClientTransactionIdProvider.extractScriptUrls(legacyHtml, URI.create("https://x.com/tesla"));
        assertThat(legacyUrls).contains(
                "https://abs.twimg.com/responsive-web/client-web/ondemand.s.1234567890abcdefa.js",
                "https://abs.twimg.com/responsive-web/client-web/main.abcdef1a.js"
        );

        assertThat(XClientTransactionIdProvider.resolveBootstrapBaseUrl("https://api.x.com"))
                .isEqualTo("https://x.com");
        assertThat(XClientTransactionIdProvider.resolveBootstrapBaseUrl("http://127.0.0.1:9000/"))
                .isEqualTo("http://127.0.0.1:9000");
    }

    @Test
    void injectsTransactionIdHeaderAndRetriesOnceOn404() throws Exception {
        AtomicInteger bootstrapCount = new AtomicInteger();
        AtomicInteger settingsCalls = new AtomicInteger();
        AtomicReference<String> lastTid = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            server.createContext("/tesla", exchange -> {
                bootstrapCount.incrementAndGet();
                String html = """
                        <html><head>
                        <meta name="twitter-site-verification" content="%s" />
                        <script src="/x-web/chunk-main.js"></script>
                        </head><body>
                        <svg id="loading-x-anim-0"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                        <svg id="loading-x-anim-1"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                        </body></html>
                        """.formatted(SAMPLE_VK_B64, SAMPLE_ANIM_PATH, SAMPLE_ANIM_PATH);
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/x-web/chunk-main.js", exchange -> {
                byte[] bytes = "import('./sign.o-deadbeef.js');".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/javascript");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/x-web/sign.o-deadbeef.js", exchange -> {
                byte[] bytes = SAMPLE_SIGNING_JS.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/javascript");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/i/api/1.1/account/settings.json", exchange -> {
                int call = settingsCalls.incrementAndGet();
                String tid = exchange.getRequestHeaders().getFirst("x-client-transaction-id");
                lastTid.set(tid);
                // 模拟过期事务密钥首次请求返回 404，刷新后重试返回 200
                if (tid == null || tid.isBlank() || call == 1) {
                    byte[] err = "{\"errors\":[{\"message\":\"Sorry, that page does not exist\",\"code\":34}]}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                    return;
                }
                byte[] ok = "{\"screen_name\":\"verified_cookie_user\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok);
                }
            });
            server.start();

            ObjectMapper mapper = new ObjectMapper();
            AppProperties props = new AppProperties();
            HttpClientFactory factory = new HttpClientFactory();
            RelayActivityBuffer buffer = new RelayActivityBuffer(props);
            XClientTransactionIdProvider provider = new XClientTransactionIdProvider(mapper);
            XRelayService relayService = new XRelayService(null, factory, props, mapper, buffer, provider);

            ConnectorProfile profile = new ConnectorProfile();
            profile.setId("x-tid-test");
            profile.setName("TID Test");
            profile.setType(ConnectorType.X);
            profile.setAuthMode(AuthMode.COOKIE_AUTH);
            profile.setEnabled(true);
            ConnectorConfig cfg = new ConnectorConfig();
            cfg.setApiBaseUrl(baseUrl);
            cfg.setAuthToken("mock-auth-token");
            cfg.setCt0("mock-ct0");
            profile.setConfig(cfg);

            RelayResponse response = relayService.test(profile);
            assertThat(response.success()).isTrue();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.data().toString()).contains("verified_cookie_user");
            assertThat(lastTid.get()).isNotBlank();
            assertThat(settingsCalls.get()).isEqualTo(2);
            assertThat(bootstrapCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesGraphqlQueryIdsAndFeatureSwitchesFromBundle() {
        String js = """
                369932(e){e.exports={queryId:"liveCreateId123",operationName:"CreateTweet",operationType:"mutation",metadata:{featureSwitches:["rweb_cashtags_composer_attachment_enabled","future_switch_enabled"],fieldToggles:[]}}}
                808098(e){e.exports={queryId:"liveSearchId456",operationName:"SearchTimeline",operationType:"query",metadata:{featureSwitches:["rweb_video_screen_enabled"]}}}
                402964(e){e.exports={queryId:"liveUserId789",operationName:"UserByScreenName",operationType:"query",metadata:{featureSwitches:["hidden_profile_subscriptions_enabled"]}}}
                """;
        Map<String, XClientTransactionIdProvider.GraphqlOperationMeta> ops =
                XClientTransactionIdProvider.parseGraphqlOperations(js);
        assertThat(ops).containsKeys("CreateTweet", "SearchTimeline", "UserByScreenName");
        assertThat(ops.get("CreateTweet").queryId()).isEqualTo("liveCreateId123");
        assertThat(ops.get("CreateTweet").featureSwitches())
                .containsExactly("rweb_cashtags_composer_attachment_enabled", "future_switch_enabled");
        assertThat(ops.get("SearchTimeline").queryId()).isEqualTo("liveSearchId456");
        assertThat(ops.get("UserByScreenName").queryId()).isEqualTo("liveUserId789");
    }

    @Test
    void dynamicallyDiscoversCreateTweetQueryIdAndRecoversFromRotation404() throws Exception {
        AtomicInteger mainJsFetches = new AtomicInteger();
        AtomicReference<String> receivedCreateBody = new AtomicReference<>();
        AtomicReference<String> receivedCreateTid = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            server.createContext("/tesla", exchange -> {
                String html = """
                        <html><head>
                        <meta name="twitter-site-verification" content="%s" />
                        <script src="/responsive-web/client-web/ondemand.s.1234567a.js"></script>
                        <script src="/responsive-web/client-web/main.abc1234a.js"></script>
                        </head><body>
                        <svg id="loading-x-anim-0"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                        <svg id="loading-x-anim-1"><g><path d="M0 0"></path><path d="%s"></path></g></svg>
                        </body></html>
                        """.formatted(SAMPLE_VK_B64, SAMPLE_ANIM_PATH, SAMPLE_ANIM_PATH);
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/responsive-web/client-web/ondemand.s.1234567a.js", exchange -> {
                byte[] bytes = SAMPLE_SIGNING_JS.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/javascript");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/responsive-web/client-web/main.abc1234a.js", exchange -> {
                int fetch = mainJsFetches.incrementAndGet();
                // 模拟首次拉取与 404 后刷新之间发生 queryId 轮换
                String qid = (fetch == 1) ? "staleCreateId001" : "rotatedCreateId002";
                String js = "e.exports={queryId:\"" + qid + "\",operationName:\"CreateTweet\",operationType:\"mutation\","
                        + "metadata:{featureSwitches:[\"brand_new_future_switch_enabled\"]}}";
                byte[] bytes = js.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/javascript");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/i/api/graphql/staleCreateId001/CreateTweet", exchange -> {
                byte[] err = "{\"message\":\"Query not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            });
            server.createContext("/i/api/graphql/rotatedCreateId002/CreateTweet", exchange -> {
                receivedCreateTid.set(exchange.getRequestHeaders().getFirst("x-client-transaction-id"));
                receivedCreateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] ok = "{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{\"rest_id\":\"2026999\"}}}}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok);
                }
            });
            server.start();

            ObjectMapper mapper = new ObjectMapper();
            AppProperties props = new AppProperties();
            HttpClientFactory factory = new HttpClientFactory();
            RelayActivityBuffer buffer = new RelayActivityBuffer(props);
            XClientTransactionIdProvider provider = new XClientTransactionIdProvider(mapper);

            ConnectorProfile profile = new ConnectorProfile();
            profile.setId("x-dyn-create");
            profile.setName("Dynamic Create");
            profile.setType(ConnectorType.X);
            profile.setAuthMode(AuthMode.COOKIE_AUTH);
            profile.setEnabled(true);
            ConnectorConfig cfg = new ConnectorConfig();
            cfg.setApiBaseUrl(baseUrl);
            cfg.setAuthToken("mock-auth-token");
            cfg.setCt0("mock-ct0");
            profile.setConfig(cfg);

            ConnectorService stubService = new ConnectorService(null, buffer, null, null, null) {
                @Override
                public ConnectorProfile getEnabledProfile(String id, ConnectorType expectedType) {
                    return profile;
                }
            };
            XRelayService relayService = new XRelayService(stubService, factory, props, mapper, buffer, provider);

            RelayResponse response = relayService.postTweet("x-dyn-create", "Hello rotated CreateTweet!", null);
            assertThat(response.success()).isTrue();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.data().toString()).contains("2026999");
            assertThat(receivedCreateTid.get()).isNotBlank();
            assertThat(receivedCreateBody.get())
                    .contains("\"queryId\":\"rotatedCreateId002\"")
                    .contains("\"brand_new_future_switch_enabled\":false");
            assertThat(mainJsFetches.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
