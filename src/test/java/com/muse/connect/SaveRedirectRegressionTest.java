package com.muse.connect;

import com.muse.connect.config.AuthProperties;
import com.muse.connect.service.ConnectorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SaveRedirectRegressionTest {

    private static final Pattern CSRF_PATTERN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private ConnectorService connectorService;

    @Test
    void saveConnectorRedirectHasNoJsessionIdAndRendersDashboardCleanly() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + port;
        String testId = "x-save-redirect-test";

        try {
            // 1. 登录获取管理员会话 Cookie
            String loginBody = "username=admin&password=" + AuthProperties.DEFAULT_PASSWORD;
            HttpResponse<String> loginResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(loginResp.statusCode()).isEqualTo(302);
            String museCookie = loginResp.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];

            // 2. 打开 /connectors/new?type=X 页面获取 CSRF 令牌
            HttpResponse<String> formResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/connectors/new?type=X"))
                            .header("Cookie", museCookie)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(formResp.statusCode()).isEqualTo(200);
            Matcher m = CSRF_PATTERN.matcher(formResp.body());
            assertThat(m.find()).isTrue();
            String csrf = m.group(1);

            // 3. 提交 /connectors/save 表单（COOKIE_AUTH 模式、中文名称、apiBaseUrl 留空）
            String saveBody = "_csrf=" + enc(csrf)
                    + "&editing=false"
                    + "&id=" + testId
                    + "&name=" + enc("Muse用于登陆的X账号")
                    + "&type=X"
                    + "&authMode=COOKIE_AUTH"
                    + "&enabled=true"
                    + "&config.authToken=test-auth-token"
                    + "&config.ct0=test-ct0";

            HttpResponse<String> saveResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/connectors/save"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Cookie", museCookie)
                            .POST(HttpRequest.BodyPublishers.ofString(saveBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(saveResp.statusCode()).isEqualTo(302);

            String location = saveResp.headers().firstValue("location").orElseThrow();
            assertThat(location).doesNotContain(";jsessionid=");
            URI targetUri = location.startsWith("http") ? URI.create(location) : URI.create(base + location);

            StringBuilder cookieHeader = new StringBuilder(museCookie);
            List<String> saveSetCookies = saveResp.headers().allValues("set-cookie");
            for (String sc : saveSetCookies) {
                cookieHeader.append("; ").append(sc.split(";", 2)[0]);
            }

            // 4. 跟随重定向访问控制台首页，应正常展示提示消息与已保存的连接器
            HttpResponse<String> homeResp = client.send(
                    HttpRequest.newBuilder(targetUri)
                            .header("Cookie", cookieHeader.toString())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(homeResp.statusCode()).isEqualTo(200);
            assertThat(homeResp.body()).contains("已保存连接器 [Muse用于登陆的X账号] (" + testId + ")");
            assertThat(homeResp.body()).contains("https://x.com");
        } finally {
            try {
                connectorService.delete(testId);
            } catch (Exception ignored) {
                // 若测试在创建前即失败则忽略清理异常
            }
        }
    }

    private String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
