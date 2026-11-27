package com.muse.connect.constant;

/**
 * 系统运行日志模板常量类。
 * 集中管理 SLF4J 日志输出消息模板，避免在业务类中分散硬编码日志字符串。
 */
public final class LogMessageConstants {

    private LogMessageConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    /** 管理员登录认证启用（预哈希密码模式） */
    public static final String AUTH_ENABLED_PREHASHED =
            "Admin login authentication ENABLED for user '{}' using PBKDF2 password hash.";
    /** 管理员登录认证启用（默认回退密码告警） */
    public static final String AUTH_ENABLED_DEFAULT_PASSWORD_WARN =
            "Admin login authentication ENABLED for user '{}' using default fallback password. Override MUSE_ADMIN_PASSWORD or MUSE_ADMIN_PASSWORD_HASH before exposing publicly.";
    /** 管理员登录认证启用（内存派生 PBKDF2 模式） */
    public static final String AUTH_ENABLED_IN_MEMORY_HASH =
            "Admin login authentication ENABLED for user '{}' (password hashed in memory via PBKDF2-HMAC-SHA256).";
    /** 管理员登录失败过多触发 IP 临时锁定 */
    public static final String AUTH_IP_LOCKED_WARN =
            "Admin login temporarily locked for IP '{}' after {} failed attempts.";
    /** 无法初始化 X 网页上下文告警 */
    public static final String X_WEB_CONTEXT_INIT_WARN =
            "Unable to initialize X web context from {}: {}";
}
