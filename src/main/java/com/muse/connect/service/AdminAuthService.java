package com.muse.connect.service;

import com.muse.connect.config.AuthProperties;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.LogMessageConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于环境变量注入凭据的内存管理员认证服务。
 * 采用 PBKDF2-HMAC-SHA256 慢哈希、有界会话存储、CSRF 令牌与按 IP 限流机制。
 * 不在 SQLite 中保存任何认证状态，确保数据库严格保持单表极简设计。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    public static final String HASH_PREFIX = SecurityConstants.PBKDF2_HASH_PREFIX;
    public static final int DEFAULT_PBKDF2_ITERATIONS = 120_000;
    private static final int MIN_PBKDF2_ITERATIONS = 10_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int MAX_TRACKED_IPS = 1024;

    private final SecureRandom secureRandom = new SecureRandom();
    private final AuthProperties properties;
    private final boolean enabled;
    private final String adminUsername;
    private final int pbkdf2Iterations;
    private final byte[] passwordSalt;
    private final byte[] passwordHash;

    private final ConcurrentHashMap<String, AdminSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureState> failuresByIp = new ConcurrentHashMap<>();

    public AdminAuthService(AuthProperties properties) {
        this.properties = properties;
        this.adminUsername = StringUtils.hasText(properties.getUsername())
                ? properties.getUsername().trim()
                : SecurityConstants.DEFAULT_ADMIN_USERNAME;

        if (StringUtils.hasText(properties.getPasswordHash())) {
            ParsedHash parsed = parseHash(properties.getPasswordHash());
            this.pbkdf2Iterations = parsed.iterations();
            this.passwordSalt = parsed.salt();
            this.passwordHash = parsed.hash();
            this.enabled = true;
            properties.clearPassword();
            log.info(LogMessageConstants.AUTH_ENABLED_PREHASHED, adminUsername);
        } else {
            boolean usingDefaultPassword = !StringUtils.hasText(properties.getPassword());
            String effectivePassword = usingDefaultPassword ? AuthProperties.DEFAULT_PASSWORD : properties.getPassword();
            byte[] salt = randomBytes(SALT_BYTES);
            this.pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS;
            this.passwordSalt = salt;
            this.passwordHash = deriveKey(effectivePassword.toCharArray(), salt, DEFAULT_PBKDF2_ITERATIONS);
            this.enabled = true;
            // 在内存中完成哈希派生后立即清除配置对象里的明文密码副本
            properties.clearPassword();
            if (usingDefaultPassword || AuthProperties.DEFAULT_PASSWORD.equals(effectivePassword)) {
                log.warn(LogMessageConstants.AUTH_ENABLED_DEFAULT_PASSWORD_WARN, adminUsername);
            } else {
                log.info(LogMessageConstants.AUTH_ENABLED_IN_MEMORY_HASH, adminUsername);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAllowRawExport() {
        return properties.isAllowRawExport();
    }

    public Duration getSessionTtl() {
        return Duration.ofMinutes(properties.getSessionTtlMinutes());
    }

    public int activeSessionCount() {
        purgeExpiredSessions(Instant.now());
        return sessions.size();
    }

    public long getLockoutRemainingSeconds(String clientIp) {
        if (!enabled || !StringUtils.hasText(clientIp)) {
            return 0;
        }
        FailureState state = failuresByIp.get(clientIp);
        if (state == null || state.lockedUntil == null) {
            return 0;
        }
        Instant now = Instant.now();
        if (now.isBefore(state.lockedUntil)) {
            return Math.max(1, Duration.between(now, state.lockedUntil).getSeconds());
        }
        failuresByIp.remove(clientIp, state);
        return 0;
    }

    public AuthResult authenticate(String username, String password, String clientIp) {
        if (!enabled) {
            return AuthResult.invalid();
        }
        String ipKey = StringUtils.hasText(clientIp) ? clientIp.trim() : SecurityConstants.UNKNOWN_CLIENT_IP;
        long lockedSeconds = getLockoutRemainingSeconds(ipKey);
        if (lockedSeconds > 0) {
            return AuthResult.locked(lockedSeconds);
        }

        String candidateUser = username == null ? RouteConstants.EMPTY : username.trim();
        char[] candidatePass = password == null ? new char[0] : password.toCharArray();

        // 即使用户名不匹配也始终执行 PBKDF2 计算，防止时序侧信道攻击
        byte[] derived = deriveKey(candidatePass, passwordSalt, pbkdf2Iterations);
        boolean userMatch = constantTimeEquals(adminUsername, candidateUser);
        boolean passMatch = MessageDigest.isEqual(passwordHash, derived);

        if (userMatch && passMatch) {
            failuresByIp.remove(ipKey);
            AdminSession session = createSession(adminUsername);
            return AuthResult.success(session);
        }

        long newLockSeconds = recordFailure(ipKey);
        if (newLockSeconds > 0) {
            return AuthResult.locked(newLockSeconds);
        }
        return AuthResult.invalid();
    }

    public Optional<AdminSession> resolveSession(String sessionId) {
        if (!enabled || !StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        AdminSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            sessions.remove(sessionId, session);
            return Optional.empty();
        }
        session.touch(now, getSessionTtl());
        return Optional.of(session);
    }

    public void invalidateSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            sessions.remove(sessionId);
        }
    }

    public boolean verifyCsrf(AdminSession session, String providedToken) {
        if (session == null || !StringUtils.hasText(providedToken)) {
            return false;
        }
        return constantTimeEquals(session.csrfToken(), providedToken.trim());
    }

    public static String hashPassword(String plainPassword) {
        return hashPassword(plainPassword, DEFAULT_PBKDF2_ITERATIONS);
    }

    public static String hashPassword(String plainPassword, int iterations) {
        if (!StringUtils.hasText(plainPassword)) {
            throw new IllegalArgumentException(ExceptionMessageConstants.AUTH_PASSWORD_BLANK);
        }
        if (iterations < MIN_PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException(
                    ExceptionMessageConstants.AUTH_PBKDF2_ITERATIONS_MIN_PREFIX + MIN_PBKDF2_ITERATIONS);
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = deriveKey(plainPassword.toCharArray(), salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder();
        return HASH_PREFIX + SecurityConstants.PBKDF2_DELIMITER + iterations
                + SecurityConstants.PBKDF2_DELIMITER + encoder.encodeToString(salt)
                + SecurityConstants.PBKDF2_DELIMITER + encoder.encodeToString(derived);
    }

    private AdminSession createSession(String username) {
        Instant now = Instant.now();
        purgeExpiredSessions(now);
        evictOldestIfAtCapacity();

        Duration ttl = getSessionTtl();
        Duration absoluteTtl = ttl.multipliedBy(2);
        if (absoluteTtl.compareTo(Duration.ofHours(24)) < 0) {
            absoluteTtl = Duration.ofHours(24);
        }

        String id = randomUrlToken(32);
        String csrf = randomUrlToken(32);
        AdminSession session = new AdminSession(
                id,
                username,
                csrf,
                now,
                now.plus(absoluteTtl),
                new AtomicReference<>(now.plus(ttl))
        );
        sessions.put(id, session);
        return session;
    }

    private void purgeExpiredSessions(Instant now) {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void evictOldestIfAtCapacity() {
        int max = properties.getMaxSessions();
        while (sessions.size() >= max) {
            Optional<Map.Entry<String, AdminSession>> oldest = sessions.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()));
            if (oldest.isEmpty()) {
                break;
            }
            sessions.remove(oldest.get().getKey(), oldest.get().getValue());
        }
    }

    private long recordFailure(String ipKey) {
        Instant now = Instant.now();
        Duration lockoutDuration = Duration.ofMinutes(properties.getLockoutMinutes());

        if (failuresByIp.size() >= MAX_TRACKED_IPS && !failuresByIp.containsKey(ipKey)) {
            failuresByIp.entrySet().removeIf(e -> e.getValue().isStale(now, lockoutDuration));
            if (failuresByIp.size() >= MAX_TRACKED_IPS) {
                failuresByIp.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().lastAttempt))
                        .ifPresent(oldest -> failuresByIp.remove(oldest.getKey(), oldest.getValue()));
            }
        }

        FailureState updated = failuresByIp.compute(ipKey, (k, existing) -> {
            if (existing == null || existing.isStale(now, lockoutDuration)) {
                return createFailureState(1, now, lockoutDuration);
            }
            return createFailureState(existing.attempts + 1, now, lockoutDuration);
        });

        if (updated != null && updated.lockedUntil != null && now.isBefore(updated.lockedUntil)) {
            log.warn(LogMessageConstants.AUTH_IP_LOCKED_WARN, ipKey, updated.attempts);
            return Math.max(1, Duration.between(now, updated.lockedUntil).getSeconds());
        }
        return 0;
    }

    private FailureState createFailureState(int attempts, Instant now, Duration lockoutDuration) {
        Instant lockedUntil = attempts >= properties.getMaxFailedAttempts() ? now.plus(lockoutDuration) : null;
        return new FailureState(attempts, now, lockedUntil);
    }

    private byte[] randomBytes(int length) {
        byte[] buf = new byte[length];
        secureRandom.nextBytes(buf);
        return buf;
    }

    private String randomUrlToken(int numBytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(numBytes));
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(SecurityConstants.PBKDF2_ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ExceptionMessageConstants.AUTH_PBKDF2_UNAVAILABLE, ex);
        } finally {
            spec.clearPassword();
        }
    }

    private static ParsedHash parseHash(String encoded) {
        String[] parts = encoded.split(SecurityConstants.PBKDF2_SPLIT_REGEX);
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException(ExceptionMessageConstants.AUTH_INVALID_HASH_FORMAT);
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(ExceptionMessageConstants.AUTH_INVALID_HASH_ITERATIONS, ex);
        }
        if (iterations < MIN_PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException(
                    ExceptionMessageConstants.AUTH_HASH_ITERATIONS_MIN_PREFIX + MIN_PBKDF2_ITERATIONS);
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt = decoder.decode(parts[2]);
        byte[] hash = decoder.decode(parts[3]);
        if (salt.length < 8 || hash.length < 16) {
            throw new IllegalArgumentException(ExceptionMessageConstants.AUTH_INVALID_SALT_OR_HASH_LEN);
        }
        return new ParsedHash(iterations, salt, hash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] hash) {}

    private static final class FailureState {
        final int attempts;
        final Instant lastAttempt;
        final Instant lockedUntil;

        FailureState(int attempts, Instant lastAttempt, Instant lockedUntil) {
            this.attempts = attempts;
            this.lastAttempt = lastAttempt;
            this.lockedUntil = lockedUntil;
        }

        boolean isStale(Instant now, Duration window) {
            if (lockedUntil != null) {
                return !now.isBefore(lockedUntil);
            }
            return now.isAfter(lastAttempt.plus(window));
        }
    }

    public record AdminSession(
            String id,
            String username,
            String csrfToken,
            Instant createdAt,
            Instant absoluteExpiresAt,
            AtomicReference<Instant> idleExpiresAt
    ) {
        public boolean isExpired(Instant now) {
            return !now.isBefore(absoluteExpiresAt) || !now.isBefore(idleExpiresAt.get());
        }

        public void touch(Instant now, Duration ttl) {
            Instant candidate = now.plus(ttl);
            Instant bounded = candidate.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : candidate;
            idleExpiresAt.set(bounded);
        }
    }

    public record AuthResult(Status status, AdminSession session, long retryAfterSeconds) {
        public enum Status {
            SUCCESS,
            INVALID_CREDENTIALS,
            LOCKED_OUT
        }

        public static AuthResult success(AdminSession session) {
            return new AuthResult(Status.SUCCESS, session, 0);
        }

        public static AuthResult invalid() {
            return new AuthResult(Status.INVALID_CREDENTIALS, null, 0);
        }

        public static AuthResult locked(long retryAfterSeconds) {
            return new AuthResult(Status.LOCKED_OUT, null, retryAfterSeconds);
        }
    }
}
