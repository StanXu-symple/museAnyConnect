package com.muse.connect;

import com.muse.connect.config.RelayAuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "muse.auth.username=opsadmin",
        "muse.auth.password=SecretPass!2026",
        "muse.auth.max-failed-attempts=3",
        "muse.auth.lockout-minutes=15",
        "muse.relay.api-key=machine-secret-key"
})
class AdminAuthIntegrationTest {

    private static final Pattern CSRF_PATTERN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectsWebAndApiWithLoginCsrfRateLimitAndApiKey() throws Exception {
        // 1. 公开健康检查端点无需登录即可访问
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.status").value("UP"));

        // 2. 未认证的控制台与 API 请求将被拦截拒绝
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"rememberCredentials\"")))
                .andExpect(content().string(containsString("保存账号信息")))
                .andExpect(content().string(containsString("muse_any_connect_login_cache_v1")));

        mockMvc.perform(get("/playground"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=%2Fplayground"));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/connectors/export"))
                .andExpect(status().isUnauthorized());

        // 3. 机器 API 调用方携带 X-Muse-Key 即可成功访问，无需浏览器会话或 CSRF 令牌
        mockMvc.perform(get("/api/stats").header(RelayAuthFilter.HEADER_NAME, "machine-secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.connectorCount").isNumber());

        // 4. 连续登录失败 3 次后触发 IP 临时锁定
        mockMvc.perform(post("/login")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.10");
                            return req;
                        })
                        .param("username", "opsadmin")
                        .param("password", "wrong1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/login")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.10");
                            return req;
                        })
                        .param("username", "opsadmin")
                        .param("password", "wrong2"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/login")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.10");
                            return req;
                        })
                        .param("username", "opsadmin")
                        .param("password", "wrong3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // 5. 未锁定 IP 正常登录后下发 HttpOnly SameSite=Lax Cookie，并忽略不安全的 next 跳转地址
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .with(req -> {
                            req.setRemoteAddr("198.51.100.20");
                            return req;
                        })
                        .param("username", "opsadmin")
                        .param("password", "SecretPass!2026")
                        .param("next", "//evil.example/steal"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("MUSE_SESSION=", "HttpOnly", "SameSite=Lax");
        String sessionId = extractCookieValue(setCookie, RelayAuthFilter.SESSION_COOKIE_NAME);
        Cookie sessionCookie = new Cookie(RelayAuthFilter.SESSION_COOKIE_NAME, sessionId);

        // 6. 已认证访问控制台首页可正常渲染用户信息与 CSRF 令牌
        MvcResult homeResult = mockMvc.perform(get("/").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("opsadmin")))
                .andExpect(content().string(containsString("退出")))
                .andReturn();

        String csrfToken = extractCsrfToken(homeResult.getResponse().getContentAsString());
        assertThat(csrfToken).isNotBlank();

        // 7. 未携带 CSRF 令牌的 Web 写操作 POST 返回 403；携带后成功执行
        mockMvc.perform(post("/connectors/export").cookie(sessionCookie))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/connectors/export")
                        .cookie(sessionCookie)
                        .param("_csrf", csrfToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        // 8. 已认证会话调用 /api/** 写操作 POST 时必须附带 X-CSRF-Token 请求头
        String payload = """
                {"id":"tg-auth-test","name":"Auth TG","type":"TELEGRAM","authMode":"BOT_TOKEN","enabled":true,"config":{"botToken":"123:abc"}}
                """;
        mockMvc.perform(post("/api/connectors")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/connectors")
                        .cookie(sessionCookie)
                        .header(RelayAuthFilter.CSRF_HEADER_NAME, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("tg-auth-test"));

        mockMvc.perform(delete("/api/connectors/tg-auth-test")
                        .header(RelayAuthFilter.HEADER_NAME, "machine-secret-key"))
                .andExpect(status().isOk());

        // 9. 退出登录后会话立即失效
        mockMvc.perform(post("/logout")
                        .cookie(sessionCookie)
                        .param("_csrf", csrfToken))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?loggedOut=1"));

        mockMvc.perform(get("/").cookie(sessionCookie))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
