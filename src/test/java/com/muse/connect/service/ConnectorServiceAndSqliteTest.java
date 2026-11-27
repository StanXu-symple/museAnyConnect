package com.muse.connect.service;

import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.SystemStats;
import com.muse.connect.repository.RelayActivityBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConnectorServiceAndSqliteTest {

    @Autowired
    private ConnectorService connectorService;

    @Autowired
    private RelayActivityBuffer activityBuffer;

    @Value("${muse.data.sqlite-dir}")
    private String sqliteDir;

    @Value("${muse.data.log-dir}")
    private String logDir;

    @Test
    void crudRetainsSecretsMasksOutputAndPersistsInSqliteVolume() {
        assertThat(Files.isDirectory(Path.of(sqliteDir))).isTrue();
        assertThat(Files.isDirectory(Path.of(logDir))).isTrue();
        assertThat(Files.exists(Path.of(sqliteDir, "muse-connect.db"))).isTrue();

        ConnectorProfile ssh = new ConnectorProfile();
        ssh.setId("ssh-unit-test");
        ssh.setName("Unit Test SSH");
        ssh.setType(ConnectorType.SSH);
        ssh.setAuthMode(AuthMode.PASSWORD);
        ConnectorConfig cfg = new ConnectorConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(2222);
        cfg.setUsername("muse");
        cfg.setPassword("super-secret-pass");
        ssh.setConfig(cfg);

        ConnectorProfile saved = connectorService.upsert(ssh);
        assertThat(saved.getConfig().getPassword()).isEqualTo(ConnectorConfig.MASK);

        // 提交脱敏占位密码更新名称时，数据库中原有密码必须保留不变
        saved.setName("Updated SSH Name");
        connectorService.upsert(saved);
        assertThat(connectorService.getRaw("ssh-unit-test").getConfig().getPassword())
                .isEqualTo("super-secret-pass");

        boolean afterToggle = connectorService.toggleEnabled("ssh-unit-test");
        assertThat(afterToggle).isFalse();

        SystemStats stats = connectorService.getStats();
        assertThat(stats.dbDataBytes()).isGreaterThan(0L);
        assertThat(stats.dbDataBytes()).isLessThan(4096L);

        connectorService.delete("ssh-unit-test");
    }

    @Test
    void rejectsIncompatibleAuthMode() {
        ConnectorProfile bad = new ConnectorProfile();
        bad.setName("Bad Combo");
        bad.setType(ConnectorType.TELEGRAM);
        bad.setAuthMode(AuthMode.PRIVATE_KEY);
        bad.setConfig(new ConnectorConfig());

        assertThatThrownBy(() -> connectorService.upsert(bad))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void activityRingBufferRespectsCapacity() {
        int cap = activityBuffer.capacity();
        for (int i = 0; i < cap + 25; i++) {
            activityBuffer.record("p-" + i, ConnectorType.X, "GET /2/users/me", true, 200, 5, "ok");
        }
        assertThat(activityBuffer.size()).isEqualTo(cap);
    }

    @Test
    void validatesMasksAndRetainsXCookieAuthSecrets() {
        ConnectorProfile missingCt0 = new ConnectorProfile();
        missingCt0.setId("x-cookie-bad");
        missingCt0.setName("Bad Cookie X");
        missingCt0.setType(ConnectorType.X);
        missingCt0.setAuthMode(AuthMode.COOKIE_AUTH);
        ConnectorConfig badCfg = new ConnectorConfig();
        badCfg.setAuthToken("tok-only");
        missingCt0.setConfig(badCfg);

        assertThatThrownBy(() -> connectorService.upsert(missingCt0))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("authToken and ct0");

        ConnectorProfile cookieX = new ConnectorProfile();
        cookieX.setId("x-cookie-unit");
        cookieX.setName("Cookie X");
        cookieX.setType(ConnectorType.X);
        cookieX.setAuthMode(AuthMode.COOKIE_AUTH);
        ConnectorConfig cfg = new ConnectorConfig();
        cfg.setAuthToken("real-auth-token-123");
        cfg.setCt0("real-ct0-csrf-456");
        cfg.setGraphqlQueryIds("CreateTweet=abc123");
        cookieX.setConfig(cfg);

        ConnectorProfile saved = connectorService.upsert(cookieX);
        assertThat(saved.getConfig().getAuthToken()).isEqualTo(ConnectorConfig.MASK);
        assertThat(saved.getConfig().getCt0()).isEqualTo(ConnectorConfig.MASK);
        assertThat(saved.getConfig().getGraphqlQueryIds()).isEqualTo("CreateTweet=abc123");

        // 提交脱敏占位值更新名称时，原有 authToken 与 ct0 必须保持不变
        saved.setName("Cookie X Renamed");
        connectorService.upsert(saved);
        ConnectorProfile raw = connectorService.getRaw("x-cookie-unit");
        assertThat(raw.getConfig().getAuthToken()).isEqualTo("real-auth-token-123");
        assertThat(raw.getConfig().getCt0()).isEqualTo("real-ct0-csrf-456");

        connectorService.delete("x-cookie-unit");
    }
}
