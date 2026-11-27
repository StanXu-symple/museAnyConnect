package com.muse.connect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "muse.auth")
public class AuthProperties {

    public static final String DEFAULT_PASSWORD = "123456";

    private String username = "admin";
    private String password = DEFAULT_PASSWORD;
    private String passwordHash = "";
    private int sessionTtlMinutes = 720;
    private int maxFailedAttempts = 5;
    private int lockoutMinutes = 15;
    private int maxSessions = 64;
    private boolean allowRawExport = true;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public void clearPassword() {
        this.password = "";
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
    }

    public int getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(int sessionTtlMinutes) {
        this.sessionTtlMinutes = clamp(sessionTtlMinutes, 5, 43200, 720);
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = clamp(maxFailedAttempts, 1, 100, 5);
    }

    public int getLockoutMinutes() {
        return lockoutMinutes;
    }

    public void setLockoutMinutes(int lockoutMinutes) {
        this.lockoutMinutes = clamp(lockoutMinutes, 1, 1440, 15);
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = clamp(maxSessions, 1, 512, 64);
    }

    public boolean isAllowRawExport() {
        return allowRawExport;
    }

    public void setAllowRawExport(boolean allowRawExport) {
        this.allowRawExport = allowRawExport;
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(max, Math.max(min, value));
    }
}
