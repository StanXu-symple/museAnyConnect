package com.muse.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.AuthProperties;
import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebAndRelayIntegrationTest {

    private static final Pattern CSRF_PATTERN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static HttpServer mockUpstream;
    private static String mockBaseUrl;
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastCookieHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastCsrfHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastAuthTypeHeader = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/2/tweets", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"data\":{\"id\":\"12345\",\"text\":\"ok\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockUpstream.createContext("/bot123:test/sendMessage", exchange -> {
            byte[] resp = "{\"ok\":true,\"result\":{\"message_id\":77}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockUpstream.createContext("/i/api/1.1/account/settings.json", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastCookieHeader.set(exchange.getRequestHeaders().getFirst("Cookie"));
            lastCsrfHeader.set(exchange.getRequestHeaders().getFirst("x-csrf-token"));
            lastAuthTypeHeader.set(exchange.getRequestHeaders().getFirst("x-twitter-auth-type"));
            byte[] resp = "{\"screen_name\":\"normal_user\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockUpstream.createContext("/i/api/graphql/customCreateId/CreateTweet", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastCookieHeader.set(exchange.getRequestHeaders().getFirst("Cookie"));
            lastCsrfHeader.set(exchange.getRequestHeaders().getFirst("x-csrf-token"));
            lastAuthTypeHeader.set(exchange.getRequestHeaders().getFirst("x-twitter-auth-type"));
            byte[] resp = "{\"data\":{\"create_tweet\":{\"tweet_results\":{\"result\":{\"rest_id\":\"98765\"}}}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockUpstream.createContext("/i/api/graphql/customUserId/UserByScreenName", exchange -> {
            lastCookieHeader.set(exchange.getRequestHeaders().getFirst("Cookie"));
            lastCsrfHeader.set(exchange.getRequestHeaders().getFirst("x-csrf-token"));
            byte[] resp = "{\"data\":{\"user\":{\"result\":{\"legacy\":{\"screen_name\":\"jack\"}}}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockUpstream.start();
        mockBaseUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort();
    }

    @AfterAll
    static void stopMockUpstream() {
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
    }

    @Test
    void relaysXAndTelegramRequestsAndRendersThymeleafPages() throws Exception {
        // 0. 未设置环境变量时，默认回退凭据仍保护 API 与 Web 控制台
        mockMvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));

        // 1. 使用默认回退 X-Muse-Key（123456789）创建 X OAuth 1.0a 连接器配置
        ConnectorProfile xProfile = new ConnectorProfile();
        xProfile.setId("x-mock");
        xProfile.setName("Mock X");
        xProfile.setType(ConnectorType.X);
        xProfile.setAuthMode(AuthMode.OAUTH1A);
        ConnectorConfig xCfg = new ConnectorConfig();
        xCfg.setApiBaseUrl(mockBaseUrl);
        xCfg.setApiKey("ck");
        xCfg.setApiSecret("cs");
        xCfg.setAccessToken("at");
        xCfg.setAccessTokenSecret("ats");
        xProfile.setConfig(xCfg);

        mockMvc.perform(post("/api/connectors")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xProfile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.apiSecret").value("****"));

        // 2. 中转转发 X 发推请求
        mockMvc.perform(post("/api/relay/x/x-mock/tweet")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello Muse!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.data.id").value("12345"));

        assertThat(lastAuthHeader.get()).startsWith("OAuth ");

        // 3. 创建 Telegram 连接器配置
        ConnectorProfile tgProfile = new ConnectorProfile();
        tgProfile.setId("tg-mock");
        tgProfile.setName("Mock TG");
        tgProfile.setType(ConnectorType.TELEGRAM);
        tgProfile.setAuthMode(AuthMode.BOT_TOKEN);
        ConnectorConfig tgCfg = new ConnectorConfig();
        tgCfg.setApiBaseUrl(mockBaseUrl);
        tgCfg.setBotToken("123:test");
        tgCfg.setDefaultChatId("9999");
        tgProfile.setConfig(tgCfg);

        mockMvc.perform(post("/api/connectors")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tgProfile)))
                .andExpect(status().isOk());

        // 4. 使用默认 defaultChatId 中转转发 Telegram sendMessage 请求
        mockMvc.perform(post("/api/relay/telegram/tg-mock/send")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ping from Muse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result.message_id").value(77));

        // 5. 使用默认回退管理员凭据（admin / 123456）登录并渲染 Thymeleaf 页面
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", AuthProperties.DEFAULT_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        String setCookie = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        Cookie sessionCookie = new Cookie(
                RelayAuthFilter.SESSION_COOKIE_NAME,
                extractCookieValue(setCookie, RelayAuthFilter.SESSION_COOKIE_NAME)
        );

        MvcResult homeResult = mockMvc.perform(get("/").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MuseAnyConnect")))
                .andExpect(content().string(containsString("Mock X")))
                .andExpect(content().string(containsString("Mock TG")))
                .andExpect(content().string(containsString("<a class=\"nav-link\" href=\"/connectors/new?type=X\">连接器</a>")))
                .andReturn();
        String homeHtml = homeResult.getResponse().getContentAsString();
        assertThat(homeHtml)
                .doesNotContain("+ Telegram")
                .doesNotContain("+ SSH 服务器");
        assertThat(homeHtml.indexOf(">连接器</a>")).isLessThan(homeHtml.indexOf(">控制台</a>"));
        String csrfToken = extractCsrfToken(homeHtml);

        MvcResult newXResult = mockMvc.perform(get("/connectors/new").param("type", "X").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a class=\"nav-link active\" href=\"/connectors/new?type=X\">连接器</a>")))
                .andExpect(content().string(containsString("如何拿到 X 的 Bearer Token / OAuth 2.0 Token")))
                .andExpect(content().string(containsString("如何拿到 X 的 OAuth 1.0a 四个密钥")))
                .andExpect(content().string(containsString("普通用户如何从浏览器获取 X 的 auth_token 和 ct0")))
                .andReturn();
        assertThat(newXResult.getResponse().getContentAsString())
                .contains("<details class=\"guide-box\">")
                .doesNotContain("open=\"open\"");

        mockMvc.perform(get("/connectors/new").param("type", "SSH").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SSH 远程服务器配置")))
                .andExpect(content().string(containsString("如何生成并配置 SSH 私钥")));

        mockMvc.perform(get("/playground").param("profileId", "x-mock").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a class=\"nav-link\" href=\"/connectors/new?type=X\">连接器</a>")))
                .andExpect(content().string(containsString("Muse HTTP Relay Playground")));

        // 6. 通过 Thymeleaf 表单 POST 保存连接器
        mockMvc.perform(post("/connectors/save")
                        .cookie(sessionCookie)
                        .param("_csrf", csrfToken)
                        .param("id", "ssh-ui-test")
                        .param("name", "UI SSH")
                        .param("type", "SSH")
                        .param("authMode", "PASSWORD")
                        .param("enabled", "true")
                        .param("config.host", "10.0.0.5")
                        .param("config.port", "22")
                        .param("config.username", "ops")
                        .param("config.password", "pw123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // 7. 验证 X COOKIE_AUTH（auth_token + ct0）配置创建、连通性测试、发推及按用户名查询
        ConnectorProfile cookieProfile = new ConnectorProfile();
        cookieProfile.setId("x-cookie-mock");
        cookieProfile.setName("Cookie X");
        cookieProfile.setType(ConnectorType.X);
        cookieProfile.setAuthMode(AuthMode.COOKIE_AUTH);
        ConnectorConfig cookieCfg = new ConnectorConfig();
        cookieCfg.setApiBaseUrl(mockBaseUrl);
        cookieCfg.setAuthToken("my-auth-token");
        cookieCfg.setCt0("my-ct0-value");
        cookieCfg.setGraphqlQueryIds("CreateTweet=customCreateId,UserByScreenName=customUserId");
        cookieProfile.setConfig(cookieCfg);

        mockMvc.perform(post("/api/connectors")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cookieProfile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.authToken").value("****"))
                .andExpect(jsonPath("$.config.ct0").value("****"));

        mockMvc.perform(post("/api/connectors/x-cookie-mock/test")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.screen_name").value("normal_user"));

        assertThat(lastCookieHeader.get()).isEqualTo("auth_token=my-auth-token; ct0=my-ct0-value");
        assertThat(lastCsrfHeader.get()).isEqualTo("my-ct0-value");
        assertThat(lastAuthTypeHeader.get()).isEqualTo("OAuth2Session");
        assertThat(lastAuthHeader.get()).startsWith("Bearer ");

        mockMvc.perform(post("/api/relay/x/x-cookie-mock/tweet")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Cookie tweet\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.create_tweet.tweet_results.result.rest_id").value("98765"));

        mockMvc.perform(get("/api/relay/x/x-cookie-mock/users/by/username/jack")
                        .header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.data.user.result.legacy.screen_name").value("jack"));

        // 清理测试数据
        mockMvc.perform(delete("/api/connectors/x-mock").header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/connectors/tg-mock").header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/connectors/ssh-ui-test").header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/connectors/x-cookie-mock").header(RelayAuthFilter.HEADER_NAME, AppProperties.DEFAULT_API_KEY)).andExpect(status().isOk());
    }

    private String extractCookieValue(String setCookieHeader, String name) {
        String prefix = name + "=";
        int start = setCookieHeader.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + prefix.length();
        int end = setCookieHeader.indexOf(';', valueStart);
        return end > 0 ? setCookieHeader.substring(valueStart, end) : setCookieHeader.substring(valueStart);
    }

    private String extractCsrfToken(String html) {
        Matcher matcher = CSRF_PATTERN.matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
