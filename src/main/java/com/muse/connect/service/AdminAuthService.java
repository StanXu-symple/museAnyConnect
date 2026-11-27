package com.muse.connect.service;

import com.muse.connect.config.AuthProperties;
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
 * In-memory administrator authentication backed by environment-injected credentials.
 * Uses PBKDF2-HMAC-SHA256 slow hashing, bounded session storage, CSRF tokens, and per-IP rate limiting.
 * Keeps zero authentication state in SQLite so the database stays strictly single-table.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    public static final String HASH_PREFIX = "pbkdf2";
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
        this.adminUsername = StringUtils.hasText(properties.getUsername()) ? properties.getUsername().trim() : "admin";

        if (StringUtils.hasText(properties.getPasswordHash())) {
            ParsedHash parsed = parseHash(properties.getPasswordHash());
            this.pbkdf2Iterations = parsed.iterations();
            this.passwordSalt = parsed.salt();
            this.passwordHash = parsed.hash();
            this.enabled = true;
            properties.clearPassword();
            log.info("Admin login authentication ENABLED for user '{}' using PBKDF2 password hash.", adminUsername);
        } else {
            boolean usingDefaultPassword = !StringUtils.hasText(properties.getPassword());
            String effectivePassword = usingDefaultPassword ? AuthProperties.DEFAULT_PASSWORD : properties.getPassword();
            byte[] salt = randomBytes(SALT_BYTES);
            this.pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS;
            this.passwordSalt = salt;
            this.passwordHash = deriveKey(effectivePassword.toCharArray(), salt, DEFAULT_PBKDF2_ITERATIONS);
            this.enabled = true;
            // Clear plaintext copy from config properties after hashing into memory
            properties.clearPassword();
            if (usingDefaultPassword || AuthProperties.DEFAULT_PASSWORD.equals(effectivePassword)) {
                log.warn("Admin login authentication ENABLED for user '{}' using default fallback password. Override MUSE_ADMIN_PASSWORD or MUSE_ADMIN_PASSWORD_HASH before exposing publicly.", adminUsername);
            } else {
                log.info("Admin login authentication ENABLED for user '{}' (password hashed in memory via PBKDF2-HMAC-SHA256).", adminUsername);
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
        String ipKey = StringUtils.hasText(clientIp) ? clientIp.trim() : "unknown";
        long lockedSeconds = getLockoutRemainingSeconds(ipKey);
        if (lockedSeconds > 0) {
            return AuthResult.locked(lockedSeconds);
        }

        String candidateUser = username == null ? "" : username.trim();
        char[] candidatePass = password == null ? new char[0] : password.toCharArray();

        // Always compute PBKDF2 even if username mismatches to avoid timing side channels
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
            throw new IllegalArgumentException("Password must not be blank");
        }
        if (iterations < MIN_PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException("PBKDF2 iterations must be at least " + MIN_PBKDF2_ITERATIONS);
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = deriveKey(plainPassword.toCharArray(), salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder();
        return HASH_PREFIX + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
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
            log.warn("Admin login temporarily locked for IP '{}' after {} failed attempts.", ipKey, updated.attempts);
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
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable in this JVM", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private static ParsedHash parseHash(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "Invalid muse.auth.password-hash format. Expected: pbkdf2$<iterations>$<saltBase64>$<hashBase64>");
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid PBKDF2 iteration count in muse.auth.password-hash", ex);
        }
        if (iterations < MIN_PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException("PBKDF2 iteration count must be >= " + MIN_PBKDF2_ITERATIONS);
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt = decoder.decode(parts[2]);
        byte[] hash = decoder.decode(parts[3]);
        if (salt.length < 8 || hash.length < 16) {
            throw new IllegalArgumentException("Invalid salt or hash length in muse.auth.password-hash");
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
