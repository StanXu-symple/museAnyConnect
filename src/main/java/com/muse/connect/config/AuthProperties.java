package com.muse.connect.config;

import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
import com.muse.connect.constant.StorageConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = StorageConstants.PREFIX_MUSE_AUTH)
public class AuthProperties {

    public static final String DEFAULT_PASSWORD = SecurityConstants.DEFAULT_ADMIN_PASSWORD;

    private String username = SecurityConstants.DEFAULT_ADMIN_USERNAME;
    private String password = DEFAULT_PASSWORD;
    private String passwordHash = RouteConstants.EMPTY;
    private int sessionTtlMinutes = 720;
    private int maxFailedAttempts = 5;
    private int lockoutMinutes = 15;
    private int maxSessions = 64;
    private boolean allowRawExport = true;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? RouteConstants.EMPTY : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? RouteConstants.EMPTY : password;
    }

    public void clearPassword() {
        this.password = RouteConstants.EMPTY;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash == null ? RouteConstants.EMPTY : passwordHash.trim();
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
