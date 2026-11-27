package com.muse.connect.service;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.StorageConstants;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.model.SystemStats;
import com.muse.connect.repository.ConnectorProfileRepository;
import com.muse.connect.repository.RelayActivityBuffer;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ConnectorService {

    private final ConnectorProfileRepository repository;
    private final RelayActivityBuffer activityBuffer;
    private final XRelayService xRelayService;
    private final TelegramRelayService telegramRelayService;
    private final SshRelayService sshRelayService;

    public ConnectorService(
            ConnectorProfileRepository repository,
            RelayActivityBuffer activityBuffer,
            @Lazy XRelayService xRelayService,
            @Lazy TelegramRelayService telegramRelayService,
            @Lazy SshRelayService sshRelayService
    ) {
        this.repository = repository;
        this.activityBuffer = activityBuffer;
        this.xRelayService = xRelayService;
        this.telegramRelayService = telegramRelayService;
        this.sshRelayService = sshRelayService;
    }

    public List<ConnectorProfile> listMasked() {
        return repository.findAll().stream()
                .map(ConnectorProfile::maskedCopy)
                .toList();
    }

    public List<ConnectorProfile> exportAllRaw() {
        return repository.findAll();
    }

    public ConnectorProfile getMasked(String id) {
        return getRaw(id).maskedCopy();
    }

    public ConnectorProfile getRaw(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ConnectorException(
                        HttpStatus.NOT_FOUND, ExceptionMessageConstants.CONNECTOR_NOT_FOUND_PREFIX + id));
    }

    public ConnectorProfile getEnabledProfile(String id, ConnectorType expectedType) {
        ConnectorProfile profile = getRaw(id);
        if (profile.getType() != expectedType) {
            throw new ConnectorException(
                    HttpStatus.BAD_REQUEST,
                    ExceptionMessageConstants.CONNECTOR_TYPE_MISMATCH_FMT.formatted(id, profile.getType(), expectedType)
            );
        }
        if (!profile.isEnabled()) {
            throw new ConnectorException(
                    HttpStatus.BAD_REQUEST,
                    ExceptionMessageConstants.CONNECTOR_DISABLED_FMT.formatted(id)
            );
        }
        return profile;
    }

    public ConnectorProfile upsert(ConnectorProfile incoming) {
        if (incoming.getType() == null || incoming.getAuthMode() == null) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_TYPE_AND_AUTH_REQUIRED);
        }
        if (!incoming.getAuthMode().supports(incoming.getType())) {
            throw new ConnectorException(
                    HttpStatus.BAD_REQUEST,
                    ExceptionMessageConstants.CONNECTOR_AUTH_NOT_SUPPORTED_FMT.formatted(incoming.getAuthMode(), incoming.getType())
            );
        }

        String id = StringUtils.hasText(incoming.getId())
                ? incoming.getId().trim()
                : generateId(incoming.getType(), incoming.getName());
        incoming.setId(id);

        ConnectorProfile existing = repository.findById(id).orElse(null);
        if (existing != null && existing.getConfig() != null) {
            incoming.getConfig().retainSecretsFrom(existing.getConfig());
        }

        validateRequiredConfig(incoming.getType(), incoming.getAuthMode(), incoming.getConfig());
        incoming.setUpdatedAt(System.currentTimeMillis());
        return repository.save(incoming).maskedCopy();
    }

    public void importAll(List<ConnectorProfile> profiles) {
        if (profiles == null) {
            return;
        }
        for (ConnectorProfile p : profiles) {
            upsert(p);
        }
    }

    public boolean toggleEnabled(String id) {
        ConnectorProfile profile = getRaw(id);
        profile.setEnabled(!profile.isEnabled());
        profile.setUpdatedAt(System.currentTimeMillis());
        repository.save(profile);
        return profile.isEnabled();
    }

    public void delete(String id) {
        if (!repository.deleteById(id)) {
            throw new ConnectorException(HttpStatus.NOT_FOUND, ExceptionMessageConstants.CONNECTOR_NOT_FOUND_PREFIX + id);
        }
    }

    public RelayResponse testConnection(String id) {
        ConnectorProfile profile = getRaw(id);
        return switch (profile.getType()) {
            case X -> xRelayService.test(profile);
            case TELEGRAM -> telegramRelayService.test(profile);
            case SSH -> sshRelayService.test(profile);
        };
    }

    public SystemStats getStats() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        return new SystemStats(
                repository.count(),
                repository.countEnabled(),
                repository.queryDbDataBytes(),
                activityBuffer.size(),
                activityBuffer.capacity(),
                usedMb,
                maxMb
        );
    }

    private void validateRequiredConfig(ConnectorType type, AuthMode authMode, ConnectorConfig cfg) {
        if (cfg == null) {
            throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_CONFIG_REQUIRED);
        }
        switch (type) {
            case X -> {
                if (authMode == AuthMode.BEARER_TOKEN && !StringUtils.hasText(cfg.getBearerToken())) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_X_BEARER_REQUIRED);
                }
                if (authMode == AuthMode.OAUTH1A && (
                        !StringUtils.hasText(cfg.getApiKey())
                                || !StringUtils.hasText(cfg.getApiSecret())
                                || !StringUtils.hasText(cfg.getAccessToken())
                                || !StringUtils.hasText(cfg.getAccessTokenSecret())
                )) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_X_OAUTH1A_REQUIRED);
                }
                if (authMode == AuthMode.COOKIE_AUTH && (
                        !StringUtils.hasText(cfg.getAuthToken())
                                || !StringUtils.hasText(cfg.getCt0())
                )) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_X_COOKIE_REQUIRED);
                }
            }
            case TELEGRAM -> {
                if (!StringUtils.hasText(cfg.getBotToken())) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_TELEGRAM_BOT_TOKEN_REQUIRED);
                }
            }
            case SSH -> {
                if (!StringUtils.hasText(cfg.getHost()) || !StringUtils.hasText(cfg.getUsername())) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_SSH_HOST_USER_REQUIRED);
                }
                if (authMode == AuthMode.PASSWORD && !StringUtils.hasText(cfg.getPassword())) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_SSH_PASSWORD_REQUIRED);
                }
                if (authMode == AuthMode.PRIVATE_KEY && !StringUtils.hasText(cfg.getPrivateKey())) {
                    throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.CONNECTOR_SSH_PRIVATE_KEY_REQUIRED);
                }
            }
        }
    }

    private String generateId(ConnectorType type, String name) {
        String slug = (name == null ? RouteConstants.EMPTY : name.toLowerCase(Locale.ROOT))
                .replaceAll(StorageConstants.SLUG_NON_ALNUM_REGEX, StorageConstants.HYPHEN)
                .replaceAll(StorageConstants.SLUG_EDGE_HYPHEN_REGEX, RouteConstants.EMPTY);
        if (slug.length() > 24) {
            slug = slug.substring(0, 24);
        }
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String prefix = type.name().toLowerCase(Locale.ROOT);
        return slug.isEmpty()
                ? (prefix + StorageConstants.HYPHEN + suffix)
                : (prefix + StorageConstants.HYPHEN + slug + StorageConstants.HYPHEN + suffix);
    }
}
