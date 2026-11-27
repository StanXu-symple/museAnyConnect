package com.muse.connect.service;

import com.muse.connect.config.AppProperties;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.repository.RelayActivityBuffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshRelayPolicyTest {

    private final SshRelayService service = new SshRelayService(null, new AppProperties(), new RelayActivityBuffer(new AppProperties()));

    @Test
    void allowsCommandsMatchingPrefixesAndBlocksChaining() {
        ConnectorConfig cfg = new ConnectorConfig();
        cfg.setAllowedCommandPrefixes("ls, uname, docker ps");

        assertThatCode(() -> service.enforceCommandPolicy(cfg, "ls -la /tmp")).doesNotThrowAnyException();
        assertThatCode(() -> service.enforceCommandPolicy(cfg, "uname")).doesNotThrowAnyException();
        assertThatCode(() -> service.enforceCommandPolicy(cfg, "docker ps -a")).doesNotThrowAnyException();

        assertThatThrownBy(() -> service.enforceCommandPolicy(cfg, "rm -rf /"))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("allowedCommandPrefixes");

        assertThatThrownBy(() -> service.enforceCommandPolicy(cfg, "ls && id"))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("chaining");

        assertThatThrownBy(() -> service.enforceCommandPolicy(cfg, "ls; whoami"))
                .isInstanceOf(ConnectorException.class);
    }
}
