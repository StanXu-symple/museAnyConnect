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
            // 1. Login
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

            // 2. Open /connectors/new?type=X to get CSRF
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

            // 3. Submit /connectors/save with COOKIE_AUTH and Chinese name, empty apiBaseUrl
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

            // 4. Following redirect renders dashboard cleanly with flash message and saved connector
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
                // ignore if test failed before creation
            }
        }
    }

    private String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
