package com.muse.connect;

import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiDocScanner;
import com.muse.connect.aidoc.AiEndpointDescriptor;
import com.muse.connect.config.AuthProperties;
import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.service.ConnectorService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "muse.relay.api-key=ai-doc-secret-api-key")
class AiDocIntegrationTest {

    private static final String TEST_API_KEY = "ai-doc-secret-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiDocScanner aiDocScanner;

    @Autowired
    private ConnectorService connectorService;

    @Test
    void scansAnnotatedEndpointsAndGeneratesDynamicMaskedLlmsTxt() throws Exception {
        // 1. Scanner discovers annotated endpoints & reflects request body record fields automatically
        List<AiEndpointDescriptor> endpoints = aiDocScanner.getEndpoints();
        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints).anySatisfy(ep -> {
            assertThat(ep.httpMethod()).isEqualTo("POST");
            assertThat(ep.path()).isEqualTo("/api/relay/x/{profileId}/tweet");
            assertThat(ep.groupName()).isEqualTo(AiDocMeta.GROUP_X_RELAY.title());
            assertThat(ep.summary()).isEqualTo(AiDocMeta.EP_X_POST_TWEET.title());
            assertThat(ep.connectorType()).isEqualTo("X");
            assertThat(ep.bodyFields()).extracting(AiEndpointDescriptor.ParamDescriptor::name)
                    .containsExactly("text", "replyToTweetId");
            assertThat(ep.bodyFields().get(0).description()).isEqualTo(AiDocMeta.FIELD_X_TWEET_TEXT.description());
        });
        // Sensitive unannotated endpoints are never exposed to AI
        assertThat(endpoints).noneMatch(ep -> ep.path().contains("/connectors/export"));

        // 2. AI doc endpoints enforce authentication
        mockMvc.perform(get("/api/ai/llms.txt")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/ai/catalog")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ai-docs")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?next=%2Fai-docs"));

        // 3. Seed one enabled SSH profile and one disabled X profile with real secret values
        ConnectorProfile sshProfile = new ConnectorProfile();
        sshProfile.setId("ssh-ai-demo");
        sshProfile.setName("Prod SSH Box");
        sshProfile.setType(ConnectorType.SSH);
        sshProfile.setAuthMode(AuthMode.PASSWORD);
        sshProfile.setEnabled(true);
        ConnectorConfig sshCfg = new ConnectorConfig();
        sshCfg.setHost("10.0.0.8");
        sshCfg.setPort(2222);
        sshCfg.setUsername("ops");
        sshCfg.setPassword("ultra-secret-ssh-pass");
        sshCfg.setAllowedCommandPrefixes("uptime,docker ps");
        sshProfile.setConfig(sshCfg);
        connectorService.upsert(sshProfile);

        ConnectorProfile disabledX = new ConnectorProfile();
        disabledX.setId("x-disabled-demo");
        disabledX.setName("Disabled X");
        disabledX.setType(ConnectorType.X);
        disabledX.setAuthMode(AuthMode.BEARER_TOKEN);
        disabledX.setEnabled(false);
        ConnectorConfig xCfg = new ConnectorConfig();
        xCfg.setBearerToken("ultra-secret-x-token");
        disabledX.setConfig(xCfg);
        connectorService.upsert(disabledX);

        try {
            // 4. Full llms.txt includes live enabled profile, constraints, real profileId in curl, and zero secrets
            mockMvc.perform(get("/api/ai/llms.txt")
                            .header(RelayAuthFilter.HEADER_NAME, TEST_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                    .andExpect(content().string(containsString("MuseAnyConnect — AI API Guide (llms.txt)")))
                    .andExpect(content().string(containsString("`ssh-ai-demo`")))
                    .andExpect(content().string(containsString("allowedCommandPrefixes=uptime,docker ps")))
                    .andExpect(content().string(containsString("/api/relay/ssh/ssh-ai-demo/exec")))
                    .andExpect(content().string(containsString("X (Twitter) Relay")))
                    .andExpect(content().string(not(containsString("x-disabled-demo"))))
                    .andExpect(content().string(not(containsString("ultra-secret-ssh-pass"))))
                    .andExpect(content().string(not(containsString("ultra-secret-x-token"))))
                    .andExpect(content().string(not(containsString(TEST_API_KEY))));

            // 5. onlyActive=true filters out connector groups with no enabled profiles
            mockMvc.perform(get("/api/ai/llms.txt")
                            .param("onlyActive", "true")
                            .header(RelayAuthFilter.HEADER_NAME, TEST_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("SSH Server Relay")))
                    .andExpect(content().string(containsString("Connector Discovery & Health")))
                    .andExpect(content().string(not(containsString("X (Twitter) Relay"))))
                    .andExpect(content().string(not(containsString("Telegram Bot Relay"))));

            // 6. Structured JSON catalog returns active connectors and reflected endpoints
            mockMvc.perform(get("/api/ai/catalog")
                            .param("onlyActive", "true")
                            .header(RelayAuthFilter.HEADER_NAME, TEST_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.service").value("MuseAnyConnect"))
                    .andExpect(jsonPath("$.authHeader").value("X-Muse-Key"))
                    .andExpect(jsonPath("$.activeConnectors[?(@.profileId=='ssh-ai-demo')]").exists())
                    .andExpect(jsonPath("$.endpoints[?(@.path=='/api/relay/ssh/{profileId}/exec')]").exists());

            // 7. Authenticated admin can view /ai-docs console page
            MvcResult loginResult = mockMvc.perform(post("/login")
                            .param("username", "admin")
                            .param("password", AuthProperties.DEFAULT_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            String setCookie = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookie).isNotNull();
            int start = setCookie.indexOf(RelayAuthFilter.SESSION_COOKIE_NAME + "=")
                    + RelayAuthFilter.SESSION_COOKIE_NAME.length() + 1;
            int end = setCookie.indexOf(';', start);
            Cookie sessionCookie = new Cookie(
                    RelayAuthFilter.SESSION_COOKIE_NAME,
                    end > 0 ? setCookie.substring(start, end) : setCookie.substring(start)
            );

            mockMvc.perform(get("/ai-docs").cookie(sessionCookie))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("AI 接口文档 (llms.txt · 注解自动生成)")))
                    .andExpect(content().string(containsString("ssh-ai-demo")));
        } finally {
            connectorService.delete("ssh-ai-demo");
            connectorService.delete("x-disabled-demo");
        }
    }
}
