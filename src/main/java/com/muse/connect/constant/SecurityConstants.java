package com.muse.connect.constant;

/**
 * 安全认证、会话管理、CSRF 防护、密码哈希与脱敏常量类。
 */
public final class SecurityConstants {

    private SecurityConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    /** 未配置环境变量时的默认回退中转 API Key */
    public static final String DEFAULT_API_KEY = "123456789";
    /** 未配置环境变量时的默认管理员用户名 */
    public static final String DEFAULT_ADMIN_USERNAME = "admin";
    /** 未配置环境变量时的默认管理员登录密码 */
    public static final String DEFAULT_ADMIN_PASSWORD = "123456";

    /** 机器 API 鉴权请求头名称 */
    public static final String HEADER_API_KEY = "X-Muse-Key";
    /** 浏览器会话调用写操作接口时的 CSRF 请求头名称 */
    public static final String HEADER_CSRF_TOKEN = "X-CSRF-Token";
    /** Web 表单提交的 CSRF 参数名称 */
    public static final String PARAM_CSRF = "_csrf";
    /** 管理员会话 Cookie 名称 */
    public static final String COOKIE_SESSION = "MUSE_SESSION";
    /** 挂在 HttpServletRequest 上的管理员会话属性名 */
    public static final String ATTR_ADMIN_SESSION = "muse.admin.session";

    /** 禁止 MIME 嗅探响应头名称 */
    public static final String HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    /** 禁止 MIME 嗅探响应头值 */
    public static final String VALUE_NOSNIFF = "nosniff";
    /** 禁止页面嵌入 iframe 响应头名称 */
    public static final String HEADER_FRAME_OPTIONS = "X-Frame-Options";
    /** 拒绝嵌入 iframe 响应头值 */
    public static final String VALUE_DENY = "DENY";
    /** 来源策略响应头名称 */
    public static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    /** 同源来源策略响应头值 */
    public static final String VALUE_SAME_ORIGIN = "same-origin";
    /** 禁止缓存响应头值 */
    public static final String VALUE_NO_STORE = "no-store";
    /** 会话 Cookie 的 SameSite 策略值 */
    public static final String COOKIE_SAME_SITE_LAX = "Lax";

    /** PBKDF2 编码字符串前缀 */
    public static final String PBKDF2_HASH_PREFIX = "pbkdf2";
    /** PBKDF2 密钥派生算法名称 */
    public static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    /** PBKDF2 编码字段分隔符 */
    public static final String PBKDF2_DELIMITER = "$";
    /** PBKDF2 编码拆分正则 */
    public static final String PBKDF2_SPLIT_REGEX = "\\$";

    /** 客户端 IP 缺失时的默认占位标识 */
    public static final String UNKNOWN_CLIENT_IP = "unknown";
    /** 敏感配置字段脱敏占位符 */
    public static final String SECRET_MASK = "****";

    /** 过滤器鉴权失败 JSON 响应模板 */
    public static final String FILTER_JSON_ERROR_FMT = "{\"success\":false,\"statusCode\":%d,\"error\":\"%s\"}";
}
