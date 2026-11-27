package com.muse.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

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

    private static HttpServer mockUpstream;
    private static String mockBaseUrl;
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

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
        // 1. Create X OAuth1a profile
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xProfile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.apiSecret").value("****"));

        // 2. Relay X tweet
        mockMvc.perform(post("/api/relay/x/x-mock/tweet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello Muse!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.data.id").value("12345"));

        assertThat(lastAuthHeader.get()).startsWith("OAuth ");

        // 3. Create Telegram profile
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tgProfile)))
                .andExpect(status().isOk());

        // 4. Relay Telegram sendMessage using defaultChatId
        mockMvc.perform(post("/api/relay/telegram/tg-mock/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ping from Muse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result.message_id").value(77));

        // 5. Thymeleaf UI pages render cleanly
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MuseAnyConnect")))
                .andExpect(content().string(containsString("Mock X")))
                .andExpect(content().string(containsString("Mock TG")));

        mockMvc.perform(get("/connectors/new").param("type", "SSH"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SSH 远程服务器配置")));

        mockMvc.perform(get("/playground").param("profileId", "x-mock"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Muse HTTP Relay Playground")));

        // 6. Save connector via Thymeleaf form POST
        mockMvc.perform(post("/connectors/save")
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

        // Cleanup
        mockMvc.perform(delete("/api/connectors/x-mock")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/connectors/tg-mock")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/connectors/ssh-ui-test")).andExpect(status().isOk());
    }
}
