package com.muse.connect.service;

import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.SystemStats;
import com.muse.connect.repository.RelayActivityBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConnectorServiceAndH2Test {

    @Autowired
    private ConnectorService connectorService;

    @Autowired
    private RelayActivityBuffer activityBuffer;

    @Test
    void crudRetainsSecretsMasksOutputAndMonitorsH2Memory() {
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

        // Update name with masked password -> original password must be preserved in raw entity
        saved.setName("Updated SSH Name");
        connectorService.upsert(saved);
        assertThat(connectorService.getRaw("ssh-unit-test").getConfig().getPassword())
                .isEqualTo("super-secret-pass");

        boolean afterToggle = connectorService.toggleEnabled("ssh-unit-test");
        assertThat(afterToggle).isFalse();

        SystemStats stats = connectorService.getStats();
        assertThat(stats.h2DataBytes()).isGreaterThan(0L);
        assertThat(stats.h2DataBytes()).isLessThan(4096L);

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
}
