package com.muse.connect.constant;

/**
 * 连接器类型与认证方式展示标签常量类。
 */
public final class ConnectorLabelConstants {

    private ConnectorLabelConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    /** X 连接器显示名称 */
    public static final String TYPE_X = "X (Twitter)";
    /** Telegram 连接器显示名称 */
    public static final String TYPE_TELEGRAM = "Telegram";
    /** SSH 连接器显示名称 */
    public static final String TYPE_SSH = "SSH Server";

    /** X Bearer / OAuth 2.0 认证展示标签 */
    public static final String AUTH_BEARER_TOKEN = "Bearer / OAuth2 Token";
    /** X OAuth 1.0a 认证展示标签 */
    public static final String AUTH_OAUTH1A = "OAuth 1.0a (HMAC-SHA1)";
    /** X 网页 Cookie 认证展示标签 */
    public static final String AUTH_COOKIE_AUTH = "Web Cookie (auth_token + ct0)";
    /** Telegram Bot Token 认证展示标签 */
    public static final String AUTH_BOT_TOKEN = "Bot API Token";
    /** SSH 密码认证展示标签 */
    public static final String AUTH_PASSWORD = "SSH Password";
    /** SSH 私钥认证展示标签 */
    public static final String AUTH_PRIVATE_KEY = "SSH Private Key";
}
