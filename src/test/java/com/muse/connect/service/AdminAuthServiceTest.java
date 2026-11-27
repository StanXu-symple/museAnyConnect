package com.muse.connect.service;

import com.muse.connect.config.AppProperties;
import com.muse.connect.config.AuthProperties;
import com.muse.connect.service.AdminAuthService.AdminSession;
import com.muse.connect.service.AdminAuthService.AuthResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthServiceTest {

    @Test
    void hashesPlainPasswordClearsConfigAndAuthenticates() {
        AuthProperties props = new AuthProperties();
        props.setUsername("ops");
        props.setPassword("Str0ng!Pass");

        AdminAuthService service = new AdminAuthService(props);

        assertThat(service.isEnabled()).isTrue();
        assertThat(props.getPassword()).isEmpty();

        AuthResult ok = service.authenticate("ops", "Str0ng!Pass", "10.0.0.1");
        assertThat(ok.status()).isEqualTo(AuthResult.Status.SUCCESS);
        assertThat(ok.session()).isNotNull();
        assertThat(service.resolveSession(ok.session().id())).isPresent();
        assertThat(service.verifyCsrf(ok.session(), ok.session().csrfToken())).isTrue();
        assertThat(service.verifyCsrf(ok.session(), "wrong-token")).isFalse();

        service.invalidateSession(ok.session().id());
        assertThat(service.resolveSession(ok.session().id())).isEmpty();
    }

    @Test
    void supportsPreHashedPbkdf2PasswordAndRejectsMalformedHash() {
        String encoded = AdminAuthService.hashPassword("HashedSecret#1", 10_000);
        assertThat(encoded).startsWith("pbkdf2$10000$");

        AuthProperties props = new AuthProperties();
        props.setUsername("admin");
        props.setPasswordHash(encoded);

        AdminAuthService service = new AdminAuthService(props);
        assertThat(service.authenticate("admin", "HashedSecret#1", "127.0.0.1").status())
                .isEqualTo(AuthResult.Status.SUCCESS);
        assertThat(service.authenticate("admin", "wrong", "127.0.0.1").status())
                .isEqualTo(AuthResult.Status.INVALID_CREDENTIALS);

        AuthProperties badProps = new AuthProperties();
        badProps.setPasswordHash("not-a-valid-hash");
        assertThatThrownBy(() -> new AdminAuthService(badProps))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksOutIpAfterConfiguredFailedAttemptsAndEnforcesSessionCap() {
        AuthProperties props = new AuthProperties();
        props.setUsername("admin");
        props.setPassword("secret");
        props.setMaxFailedAttempts(2);
        props.setLockoutMinutes(10);
        props.setMaxSessions(2);

        AdminAuthService service = new AdminAuthService(props);

        assertThat(service.authenticate("admin", "bad1", "192.168.1.50").status())
                .isEqualTo(AuthResult.Status.INVALID_CREDENTIALS);
        AuthResult locked = service.authenticate("admin", "bad2", "192.168.1.50");
        assertThat(locked.status()).isEqualTo(AuthResult.Status.LOCKED_OUT);
        assertThat(locked.retryAfterSeconds()).isPositive();

        // 其他 IP 不受锁定影响
        AdminSession s1 = service.authenticate("admin", "secret", "192.168.1.51").session();
        AdminSession s2 = service.authenticate("admin", "secret", "192.168.1.51").session();
        AdminSession s3 = service.authenticate("admin", "secret", "192.168.1.51").session();
        assertThat(s1).isNotNull();
        assertThat(s2).isNotNull();
        assertThat(s3).isNotNull();
        assertThat(service.activeSessionCount()).isEqualTo(2);
        assertThat(service.resolveSession(s3.id())).isPresent();
    }

    @Test
    void fallsBackToHardcodedDefaultsWhenUnconfiguredOrBlank() {
        AuthProperties blankAuth = new AuthProperties();
        blankAuth.setPassword("   ");
        AdminAuthService service = new AdminAuthService(blankAuth);

        assertThat(service.isEnabled()).isTrue();
        assertThat(blankAuth.getPassword()).isEmpty();
        assertThat(service.authenticate("admin", AuthProperties.DEFAULT_PASSWORD, "127.0.0.1").status())
                .isEqualTo(AuthResult.Status.SUCCESS);
        assertThat(service.authenticate("admin", "wrong", "127.0.0.1").status())
                .isEqualTo(AuthResult.Status.INVALID_CREDENTIALS);

        AppProperties appProps = new AppProperties();
        assertThat(appProps.getApiKey()).isEqualTo("123456789");
        appProps.setApiKey("   ");
        assertThat(appProps.getApiKey()).isEqualTo("123456789");
        appProps.setApiKey("custom-key");
        assertThat(appProps.getApiKey()).isEqualTo("custom-key");
    }
}
