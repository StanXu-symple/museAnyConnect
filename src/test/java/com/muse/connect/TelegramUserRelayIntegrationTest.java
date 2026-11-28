package com.muse.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.config.AppProperties;
import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.constant.TelegramRelayConstants;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Telegram 个人账号 MTProto (gotd/td 侧车) 全链路集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelegramUserRelayIntegrationTest {

    private static final String API_KEY = AppProperties.DEFAULT_API_KEY;
    private static final String BRIDGE_TOKEN = "bridge-test-secret";

    private static HttpServer mockBridge;
    private static String mockBridgeUrl;
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastBridgeToken = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startMockBridge() throws IOException {
        mockBridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockBridge.createContext("/bridge/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String token = exchange.getRequestHeaders().getFirst(TelegramRelayConstants.HEADER_BRIDGE_TOKEN);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastPath.set(path);
            lastBridgeToken.set(token);
            lastBody.set(body);

            String jsonResp = switch (path) {
                case TelegramRelayConstants.BRIDGE_PATH_ME ->
                        "{\"ok\":true,\"result\":{\"id\":9001,\"username\":\"muse_owner\",\"firstName\":\"Muse\"}}";
                case TelegramRelayConstants.BRIDGE_PATH_DIALOGS ->
                        "{\"ok\":true,\"result\":{\"totalCount\":1,\"returned\":1,\"dialogs\":[{\"chatId\":\"-100888999\",\"title\":\"Core Dev Group\",\"type\":\"supergroup\"}]}}";
                case TelegramRelayConstants.BRIDGE_PATH_HISTORY ->
                        "{\"ok\":true,\"result\":{\"totalCount\":1,\"returned\":1,\"messages\":[{\"id\":42,\"text\":\"hello group\",\"senderName\":\"Alice\"}]}}";
                case TelegramRelayConstants.BRIDGE_PATH_SEARCH ->
                        "{\"ok\":true,\"result\":{\"totalCount\":1,\"returned\":1,\"messages\":[{\"id\":42,\"text\":\"release v1\"}]}}";
                case TelegramRelayConstants.BRIDGE_PATH_SEND ->
                        "{\"ok\":true,\"result\":{\"chatId\":\"-100888999\",\"sent\":true,\"messageId\":43}}";
                case TelegramRelayConstants.BRIDGE_PATH_EDIT ->
                        "{\"ok\":true,\"result\":{\"chatId\":\"-100888999\",\"messageId\":43,\"edited\":true}}";
                case TelegramRelayConstants.BRIDGE_PATH_DELETE ->
                        "{\"ok\":true,\"result\":{\"chatId\":\"-100888999\",\"deleted\":true,\"ptsCount\":1}}";
                case TelegramRelayConstants.BRIDGE_PATH_FORWARD ->
                        "{\"ok\":true,\"result\":{\"fromChatId\":\"-100888999\",\"toChatId\":\"me\",\"forwardedMessageIds\":[99]}}";
                case TelegramRelayConstants.BRIDGE_PATH_READ ->
                        "{\"ok\":true,\"result\":{\"chatId\":\"-100888999\",\"read\":true}}";
                case TelegramRelayConstants.BRIDGE_PATH_RESOLVE ->
                        "{\"ok\":true,\"result\":{\"chatId\":\"-100888999\",\"type\":\"supergroup\",\"username\":\"muse_group\"}}";
                case TelegramRelayConstants.BRIDGE_PATH_AUTH_START ->
                        "{\"ok\":true,\"result\":{\"loginId\":\"login-abc\",\"status\":\"WAIT_CODE\"}}";
                case TelegramRelayConstants.BRIDGE_PATH_AUTH_VERIFY ->
                        "{\"ok\":true,\"result\":{\"loginId\":\"login-abc\",\"status\":\"AUTHORIZED\",\"sessionString\":\"tg_sess_v1:demoSession\"}}";
                case TelegramRelayConstants.BRIDGE_PATH_AUTH_QR_START ->
                        "{\"ok\":true,\"result\":{\"qrLoginId\":\"qr-123\",\"status\":\"WAIT_SCAN\",\"qrUrl\":\"tg://login?token=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\",\"expiresAt\":1999999999}}";
                case TelegramRelayConstants.BRIDGE_PATH_AUTH_QR_POLL ->
                        "{\"ok\":true,\"result\":{\"qrLoginId\":\"qr-123\",\"status\":\"AUTHORIZED\",\"sessionString\":\"tg_sess_v1:qrSession\"}}";
                default -> "{\"ok\":false,\"error\":\"unknown path\"}";
            };
            byte[] bytes = jsonResp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockBridge.start();
        mockBridgeUrl = "http://127.0.0.1:" + mockBridge.getAddress().getPort();
    }

    @AfterAll
    static void stopMockBridge() {
        if (mockBridge != null) {
            mockBridge.stop(0);
        }
    }

    @Test
    void relaysTelegramUserAccountCapabilitiesAndMasksSecrets() throws Exception {
        // 1. 创建 USER_SESSION 连接器并校验敏感凭据全部脱敏
        ConnectorProfile profile = new ConnectorProfile();
        profile.setId("tg-user-mock");
        profile.setName("Muse 个人号");
        profile.setType(ConnectorType.TELEGRAM);
        profile.setAuthMode(AuthMode.USER_SESSION);
        profile.setEnabled(true);

        ConnectorConfig cfg = new ConnectorConfig();
        cfg.setApiBaseUrl(mockBridgeUrl);
        cfg.setTelegramApiId(123456);
        cfg.setTelegramApiHash("hash-secret-xyz");
        cfg.setTelegramSession("tg_sess_v1:raw-session-secret");
        cfg.setTelegramBridgeToken(BRIDGE_TOKEN);
        cfg.setDefaultChatId("-100888999");
        cfg.setProxyHost("socks5://127.0.0.1");
        cfg.setProxyPort(1080);
        profile.setConfig(cfg);

        mockMvc.perform(post("/api/connectors")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("USER_SESSION"))
                .andExpect(jsonPath("$.config.telegramApiId").value(123456))
                .andExpect(jsonPath("$.config.telegramApiHash").value("****"))
                .andExpect(jsonPath("$.config.telegramSession").value("****"))
                .andExpect(jsonPath("$.config.telegramBridgeToken").value("****"));

        // 2. 更新名称时提交占位符，后端必须保留原密钥不丢失
        ConnectorConfig updateCfg = new ConnectorConfig();
        updateCfg.setApiBaseUrl(mockBridgeUrl);
        updateCfg.setTelegramApiId(123456);
        updateCfg.setTelegramApiHash("****");
        updateCfg.setTelegramSession("****");
        updateCfg.setTelegramBridgeToken("****");
        updateCfg.setDefaultChatId("-100888999");
        updateCfg.setProxyHost("socks5://127.0.0.1");
        updateCfg.setProxyPort(1080);
        profile.setName("Muse 个人号（已更新）");
        profile.setConfig(updateCfg);

        mockMvc.perform(post("/api/connectors")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Muse 个人号（已更新）"));

        // 3. 连通性测试：应转发至 /bridge/me 并携带完整原密钥、代理信息与 X-Bridge-Token
        mockMvc.perform(post("/api/connectors/tg-user-mock/test")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result.username").value("muse_owner"));

        assertThat(lastPath.get()).isEqualTo(TelegramRelayConstants.BRIDGE_PATH_ME);
        assertThat(lastBridgeToken.get()).isEqualTo(BRIDGE_TOKEN);
        JsonNode mePayload = objectMapper.readTree(lastBody.get());
        assertThat(mePayload.get("apiId").asInt()).isEqualTo(123456);
        assertThat(mePayload.get("apiHash").asText()).isEqualTo("hash-secret-xyz");
        assertThat(mePayload.get("sessionString").asText()).isEqualTo("tg_sess_v1:raw-session-secret");
        assertThat(mePayload.get("proxyHost").asText()).isEqualTo("socks5://127.0.0.1");
        assertThat(mePayload.get("proxyPort").asInt()).isEqualTo(1080);

        // 4. 列举对话/群组列表
        mockMvc.perform(get("/api/relay/telegram/tg-user-mock/dialogs")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result.dialogs[0].chatId").value("-100888999"));

        // 5. 读取群历史消息（使用 defaultChatId 兜底）
        mockMvc.perform(get("/api/relay/telegram/tg-user-mock/history")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.messages[0].text").value("hello group"));

        // 6. 发送回复消息
        mockMvc.perform(post("/api/relay/telegram/tg-user-mock/send")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"reply text\",\"parseMode\":\"HTML\",\"replyToMessageId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.messageId").value(43));
        JsonNode sendBody = objectMapper.readTree(lastBody.get());
        assertThat(sendBody.get("replyToMessageId").asLong()).isEqualTo(42L);
        assertThat(sendBody.get("chatId").asText()).isEqualTo("-100888999");

        // 7. 搜索、编辑、删除、转发、标记已读、解析用户名
        mockMvc.perform(get("/api/relay/telegram/tg-user-mock/search")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .param("query", "release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.messages[0].text").value("release v1"));

        mockMvc.perform(post("/api/relay/telegram/tg-user-mock/edit")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":43,\"text\":\"edited text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.edited").value(true));

        mockMvc.perform(post("/api/relay/telegram/tg-user-mock/delete")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[43],\"revoke\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.deleted").value(true));

        mockMvc.perform(post("/api/relay/telegram/tg-user-mock/forward")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromChatId\":\"-100888999\",\"toChatId\":\"me\",\"messageIds\":[42]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.forwardedMessageIds[0]").value(99));

        mockMvc.perform(post("/api/relay/telegram/tg-user-mock/read")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxId\":43}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.read").value(true));

        mockMvc.perform(get("/api/relay/telegram/tg-user-mock/resolve")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .param("chatId", "@muse_group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.username").value("muse_group"));

        // 8. 个人账号模式调用 Bot 专用 /updates 应返回 400 明确提示
        mockMvc.perform(get("/api/relay/telegram/tg-user-mock/updates")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isBadRequest());

        // 9. 交互式在线登录接口（获取 Session 字符串）
        mockMvc.perform(post("/api/connectors/telegram/auth/start")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiId\":123456,\"apiHash\":\"hash-secret-xyz\",\"phone\":\"+8613800138000\",\"bridgeUrl\":\"" + mockBridgeUrl + "\",\"bridgeToken\":\"" + BRIDGE_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.loginId").value("login-abc"));

        mockMvc.perform(post("/api/connectors/telegram/auth/verify")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"login-abc\",\"code\":\"12345\",\"bridgeUrl\":\"" + mockBridgeUrl + "\",\"bridgeToken\":\"" + BRIDGE_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sessionString").value("tg_sess_v1:demoSession"));

        // 9b. 二维码扫码登录接口（自动生成内联 SVG 二维码并轮询返回 Session 字符串）
        mockMvc.perform(post("/api/connectors/telegram/auth/qr/start")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiId\":123456,\"apiHash\":\"hash-secret-xyz\",\"bridgeUrl\":\"" + mockBridgeUrl + "\",\"bridgeToken\":\"" + BRIDGE_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.qrLoginId").value("qr-123"))
                .andExpect(jsonPath("$.result.status").value("WAIT_SCAN"))
                .andExpect(jsonPath("$.result.qrUrl", containsString("tg://login?token=")))
                .andExpect(jsonPath("$.result.qrSvg", containsString("<svg ")));

        mockMvc.perform(post("/api/connectors/telegram/auth/qr/poll")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrLoginId\":\"qr-123\",\"bridgeUrl\":\"" + mockBridgeUrl + "\",\"bridgeToken\":\"" + BRIDGE_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.result.sessionString").value("tg_sess_v1:qrSession"));

        // 10. AI 文档自动收录新增的个人账号 MTProto 端点
        mockMvc.perform(get("/api/ai/llms.txt")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/relay/telegram/{profileId}/dialogs")))
                .andExpect(content().string(containsString("/api/relay/telegram/{profileId}/history")));

        // 清理测试连接器
        mockMvc.perform(delete("/api/connectors/tg-user-mock")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void returnsActionableErrorWhenBridgeUnreachableOrDcIpEntered() throws Exception {
        // 1. 误将 Telegram 官方 DC IP 填入侧车地址时应返回 400 明确提示
        mockMvc.perform(post("/api/connectors/telegram/auth/start")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiId\":123456,\"apiHash\":\"hash-secret\",\"phone\":\"+8613800138000\",\"bridgeUrl\":\"149.154.167.50:443\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("tg-user-bridge")))
                .andExpect(jsonPath("$.error", containsString("149.154.167.50:443")));

        // 2. 侧车未启动导致连接被拒绝时，不得返回模糊的 "Telegram relay failed: null"
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String closedBridgeUrl = "http://127.0.0.1:" + closedPort;

        mockMvc.perform(post("/api/connectors/telegram/auth/start")
                        .header(RelayAuthFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiId\":123456,\"apiHash\":\"hash-secret\",\"phone\":\"+8613800138000\",\"bridgeUrl\":\"" + closedBridgeUrl + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("无法连接 Telegram 侧车服务")))
                .andExpect(jsonPath("$.error", containsString(closedBridgeUrl)));
    }
}
