package com.muse.connect.constant;

/**
 * 通用 HTTP 方法、请求头、媒体类型、代理与加密签名协议常量类。
 */
public final class HttpConstants {

    private HttpConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. HTTP 方法
    // =========================================================================

    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_DELETE = "DELETE";
    public static final String METHOD_HEAD = "HEAD";
    public static final String METHOD_OPTIONS = "OPTIONS";
    public static final String METHOD_TRACE = "TRACE";

    // =========================================================================
    // 2. 标准 HTTP 请求头名称与媒体类型
    // =========================================================================

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_COOKIE = "Cookie";
    public static final String HEADER_ORIGIN = "Origin";
    public static final String HEADER_REFERER = "Referer";
    public static final String HEADER_USER_AGENT = "User-Agent";

    /** Bearer 认证头前缀 */
    public static final String AUTH_PREFIX_BEARER = "Bearer ";
    /** OAuth 1.0a 认证头前缀 */
    public static final String AUTH_PREFIX_OAUTH = "OAuth ";

    /** JSON 媒体类型 */
    public static final String MIME_APPLICATION_JSON = "application/json";
    /** UTF-8 Markdown 媒体类型 */
    public static final String MIME_TEXT_MARKDOWN_UTF8 = "text/markdown;charset=UTF-8";
    /** 网页拉取通用 Accept 头 */
    public static final String ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    /** 英文语言偏好 Accept-Language 头 */
    public static final String ACCEPT_LANGUAGE_EN = "en-US,en;q=0.9";
    /** 导出连接器配置 JSON 的 Content-Disposition 响应头值 */
    public static final String CONTENT_DISPOSITION_EXPORT_JSON = "attachment; filename=muse-connectors.json";

    // =========================================================================
    // 3. 代理、URL 处理与活动摘要
    // =========================================================================

    /** 无代理直连 HttpClient 缓存键 */
    public static final String PROXY_DIRECT_KEY = "DIRECT";
    /** 代理主机端口拼接分隔符 */
    public static final String COLON = ":";
    /** 默认本地服务地址 */
    public static final String DEFAULT_LOCAL_BASE_URL = "http://localhost:8080";
    /** 去除 URL 末尾多余斜杠的正则 */
    public static final String TRAILING_SLASH_REGEX = "/+$";
    /** URL 协议与主机分隔符 */
    public static final String SCHEME_SEPARATOR = "://";
    /** 路径斜杠 */
    public static final String SLASH = "/";
    /** 等号 */
    public static final String EQUALS = "=";
    /** 空格 */
    public static final String SPACE = " ";
    /** 省略号 */
    public static final String ELLIPSIS = "...";
    /** 中转成功摘要文案 */
    public static final String SUMMARY_OK = "OK";
    /** 中转 HTTP 错误摘要前缀 */
    public static final String SUMMARY_HTTP_PREFIX = "HTTP ";

    // =========================================================================
    // 4. OAuth 1.0a 与哈希算法常量
    // =========================================================================

    public static final String OAUTH_CONSUMER_KEY = "oauth_consumer_key";
    public static final String OAUTH_NONCE = "oauth_nonce";
    public static final String OAUTH_SIGNATURE_METHOD = "oauth_signature_method";
    public static final String OAUTH_SIGNATURE_METHOD_HMAC_SHA1 = "HMAC-SHA1";
    public static final String OAUTH_TIMESTAMP = "oauth_timestamp";
    public static final String OAUTH_TOKEN = "oauth_token";
    public static final String OAUTH_VERSION = "oauth_version";
    public static final String OAUTH_VERSION_1_0 = "1.0";
    public static final String OAUTH_SIGNATURE = "oauth_signature";
    public static final String OAUTH_HEADER_PAIR_SEP = ", ";
    public static final String OAUTH_HEADER_QUOTE_OPEN = "=\"";
    public static final String OAUTH_HEADER_QUOTE_CLOSE = "\"";

    /** HMAC-SHA1 算法名称 */
    public static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
    /** SHA-256 算法名称 */
    public static final String SHA256_ALGORITHM = "SHA-256";

    /** RFC 3986 百分号编码替换字符 */
    public static final String PLUS = "+";
    public static final String ENCODED_SPACE = "%20";
    public static final String ASTERISK = "*";
    public static final String ENCODED_ASTERISK = "%2A";
    public static final String ENCODED_TILDE = "%7E";
    public static final String TILDE = "~";
}
