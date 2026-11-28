package com.muse.connect.constant;

/**
 * 异常消息、参数校验文案与失败错误提示常量类。
 * 集中管理所有 Exception 抛出的错误消息、Bean Validation 注解校验提示、鉴权失败响应及中转失败提示。
 */
public final class ExceptionMessageConstants {

    private ExceptionMessageConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. Bean Validation 字段校验消息（ConnectorProfile）
    // =========================================================================

    /** 连接器 ID 格式非法提示 */
    public static final String VALIDATION_ID_PATTERN = "ID only allows letters, digits, '-' and '_'";
    /** 连接器名称必填提示 */
    public static final String VALIDATION_NAME_REQUIRED = "Name is required";
    /** 连接器名称长度超限提示 */
    public static final String VALIDATION_NAME_MAX_LENGTH = "Name must be <= 64 characters";
    /** 连接器类型必填提示 */
    public static final String VALIDATION_TYPE_REQUIRED = "Connector type is required";
    /** 认证方式必填提示 */
    public static final String VALIDATION_AUTH_MODE_REQUIRED = "Auth mode is required";

    // =========================================================================
    // 2. 鉴权过滤器与管理员认证异常消息
    // =========================================================================

    /** 缺少或无效的 X-CSRF-Token 请求头 */
    public static final String AUTH_INVALID_CSRF_HEADER = "Invalid or missing X-CSRF-Token header";
    /** 缺少或无效的 X-Muse-Key 请求头 */
    public static final String AUTH_INVALID_API_KEY = "Invalid or missing X-Muse-Key header";
    /** 未认证访问提示 */
    public static final String AUTH_REQUIRED = "Authentication required. Please log in or configure X-Muse-Key";
    /** 无效 CSRF 令牌（Web 控制台） */
    public static final String AUTH_INVALID_CSRF_TOKEN = "Invalid CSRF token";
    /** 密码为空异常 */
    public static final String AUTH_PASSWORD_BLANK = "Password must not be blank";
    /** PBKDF2 迭代次数下限提示前缀 */
    public static final String AUTH_PBKDF2_ITERATIONS_MIN_PREFIX = "PBKDF2 iterations must be at least ";
    /** PBKDF2 算法不可用异常 */
    public static final String AUTH_PBKDF2_UNAVAILABLE = "PBKDF2WithHmacSHA256 is unavailable in this JVM";
    /** 预哈希密码格式非法异常 */
    public static final String AUTH_INVALID_HASH_FORMAT =
            "Invalid muse.auth.password-hash format. Expected: pbkdf2$<iterations>$<saltBase64>$<hashBase64>";
    /** 预哈希迭代次数非法异常 */
    public static final String AUTH_INVALID_HASH_ITERATIONS = "Invalid PBKDF2 iteration count in muse.auth.password-hash";
    /** 预哈希迭代次数过低前缀 */
    public static final String AUTH_HASH_ITERATIONS_MIN_PREFIX = "PBKDF2 iteration count must be >= ";
    /** 预哈希盐值或摘要长度非法 */
    public static final String AUTH_INVALID_SALT_OR_HASH_LEN = "Invalid salt or hash length in muse.auth.password-hash";
    /** 控制台登录失败提示 */
    public static final String WEB_LOGIN_INVALID = "用户名或密码错误，请重试。";

    // =========================================================================
    // 3. 连接器管理与存储异常消息
    // =========================================================================

    /** 连接器配置未找到前缀 */
    public static final String CONNECTOR_NOT_FOUND_PREFIX = "Connector profile not found: ";
    /** 连接器类型不匹配模板（依次填入 id、实际类型、期望类型） */
    public static final String CONNECTOR_TYPE_MISMATCH_FMT = "Connector '%s' is of type %s, expected %s";
    /** 连接器已禁用模板（填入 id） */
    public static final String CONNECTOR_DISABLED_FMT = "Connector '%s' is currently disabled";
    /** 连接器类型与认证方式必填 */
    public static final String CONNECTOR_TYPE_AND_AUTH_REQUIRED = "Connector type and authMode are required";
    /** 认证方式与连接器类型不兼容模板 */
    public static final String CONNECTOR_AUTH_NOT_SUPPORTED_FMT = "AuthMode %s is not supported for type %s";
    /** 连接器 config 必填 */
    public static final String CONNECTOR_CONFIG_REQUIRED = "Connector config is required";
    /** X Bearer Token 必填 */
    public static final String CONNECTOR_X_BEARER_REQUIRED = "bearerToken is required for X BEARER_TOKEN";
    /** X OAuth 1.0a 四元组必填 */
    public static final String CONNECTOR_X_OAUTH1A_REQUIRED =
            "apiKey, apiSecret, accessToken and accessTokenSecret are required for X OAUTH1A";
    /** X Cookie 凭据必填 */
    public static final String CONNECTOR_X_COOKIE_REQUIRED = "authToken and ct0 are required for X COOKIE_AUTH";
    /** Telegram Bot Token 必填 */
    public static final String CONNECTOR_TELEGRAM_BOT_TOKEN_REQUIRED = "botToken is required for Telegram BOT_TOKEN";
    /** Telegram 个人账号 MTProto 凭据必填 */
    public static final String CONNECTOR_TELEGRAM_USER_SESSION_REQUIRED =
            "telegramApiId, telegramApiHash and telegramSession are required for Telegram USER_SESSION";
    /** SSH 主机与用户名必填 */
    public static final String CONNECTOR_SSH_HOST_USER_REQUIRED = "host and username are required for SSH";
    /** SSH 密码认证必填 */
    public static final String CONNECTOR_SSH_PASSWORD_REQUIRED = "password is required for SSH PASSWORD mode";
    /** SSH 私钥认证必填 */
    public static final String CONNECTOR_SSH_PRIVATE_KEY_REQUIRED = "privateKey is required for SSH PRIVATE_KEY mode";
    /** API 禁止导出明文凭据 */
    public static final String API_RAW_EXPORT_DISABLED = "Raw credential export is disabled (MUSE_ALLOW_RAW_EXPORT=false)";
    /** Web 控制台禁止导出明文凭据 */
    public static final String WEB_RAW_EXPORT_DISABLED = "当前配置已禁止导出明文凭据 (MUSE_ALLOW_RAW_EXPORT=false)";
    /** Web 控制台连接名称必填 */
    public static final String WEB_CONNECTOR_NAME_REQUIRED = "连接名称不能为空";
    /** Web 控制台 JSON 导入失败前缀 */
    public static final String WEB_JSON_IMPORT_FAILED_PREFIX = "JSON 导入失败: ";
    /** 数据目录创建失败前缀 */
    public static final String DATA_DIR_CREATE_FAILED_PREFIX = "Failed to create data directory: ";
    /** 反序列化连接器配置失败前缀 */
    public static final String REPO_DESERIALIZE_FAILED_PREFIX = "Failed to deserialize connector config for id=";
    /** 序列化连接器配置失败 */
    public static final String REPO_INVALID_CONFIG = "Invalid connector config";

    // =========================================================================
    // 4. X (Twitter) 中转与签名异常消息
    // =========================================================================

    /** 推文正文必填 */
    public static final String X_TWEET_TEXT_REQUIRED = "Tweet text is required";
    /** 搜索关键字必填 */
    public static final String X_SEARCH_QUERY_REQUIRED = "Search query is required";
    /** 用户名必填 */
    public static final String X_USERNAME_REQUIRED = "Username is required";
    /** Bearer Token 缺失 */
    public static final String X_BEARER_TOKEN_MISSING = "Bearer token is missing";
    /** 不支持的 X 认证方式前缀 */
    public static final String X_UNSUPPORTED_AUTH_MODE_PREFIX = "Unsupported auth mode for X: ";
    /** X API 路径必填 */
    public static final String X_API_PATH_REQUIRED = "API path is required (e.g. /2/users/me)";
    /** X 请求负载序列化失败前缀 */
    public static final String X_SERIALIZE_PAYLOAD_FAILED_PREFIX = "Failed to serialize X request payload: ";
    /** X 接口返回非 2xx 状态码前缀 */
    public static final String X_API_RETURNED_HTTP_PREFIX = "X API returned HTTP ";
    /** X 中转异常失败前缀 */
    public static final String X_RELAY_FAILED_PREFIX = "X relay failed: ";
    /** OAuth 1.0a 签名计算失败 */
    public static final String OAUTH1_SIGN_FAILED = "Failed to compute OAuth1 HMAC-SHA1 signature";
    /** X 引导页返回异常 HTTP 状态码前缀 */
    public static final String X_BOOTSTRAP_HTTP_PREFIX = "Bootstrap page returned HTTP ";
    /** X 重定向页返回异常 HTTP 状态码前缀 */
    public static final String X_REDIRECT_HTTP_PREFIX = "Redirect page returned HTTP ";
    /** X 迁移接口返回异常 HTTP 状态码前缀 */
    public static final String X_MIGRATE_HTTP_PREFIX = "Migrate endpoint returned HTTP ";
    /** 未找到 X 校验密钥 meta 标签 */
    public static final String X_VK_META_NOT_FOUND = "X verification key meta tag not found";
    /** X 校验密钥过短 */
    public static final String X_VK_TOO_SHORT = "X verification key is too short";
    /** 未找到 X SVG 动画数据 */
    public static final String X_SVG_ANIM_NOT_FOUND = "X SVG animation data not found";
    /** X SVG 动画路径格式异常 */
    public static final String X_SVG_ANIM_MALFORMED = "X SVG animation path is malformed";
    /** 未找到 X 网页脚本地址 */
    public static final String X_SCRIPT_URLS_NOT_FOUND = "X web script URLs not found";
    /** X 网页会话未登录或已失效 */
    public static final String X_WEB_LOGGED_OUT = "Logged-out X web app (auth_token or ct0 may be invalid/expired)";
    /** 签名脚本请求返回异常状态码前缀 */
    public static final String X_SIGNING_SCRIPT_HTTP_PREFIX = "Signing script returned HTTP ";
    /** 未在网页脚本包中找到签名脚本引用 */
    public static final String X_SIGNING_SCRIPT_REF_NOT_FOUND = "X signing script reference not found in web bundles";
    /** 未在脚本中提取到签名索引 */
    public static final String X_SIGNING_INDICES_NOT_FOUND = "X signing indices not found in script";
    /** SHA-256 算法不可用 */
    public static final String SHA256_UNAVAILABLE = "SHA-256 unavailable";

    // =========================================================================
    // 5. Telegram 中转异常消息
    // =========================================================================

    /** Telegram chatId 缺失 */
    public static final String TELEGRAM_CHAT_ID_REQUIRED = "chatId is required (no defaultChatId configured)";
    /** Telegram 消息正文缺失 */
    public static final String TELEGRAM_TEXT_REQUIRED = "Message text is required";
    /** Telegram Bot API 方法名非法 */
    public static final String TELEGRAM_INVALID_METHOD_NAME = "Invalid Telegram Bot API method name";
    /** Telegram botToken 缺失 */
    public static final String TELEGRAM_BOT_TOKEN_MISSING = "Telegram botToken is missing";
    /** Telegram API 返回错误 */
    public static final String TELEGRAM_API_ERROR = "Telegram API returned error";
    /** Telegram 中转异常失败前缀 */
    public static final String TELEGRAM_RELAY_FAILED_PREFIX = "Telegram relay failed: ";
    /** Telegram 个人账号模式专用接口提示 */
    public static final String TELEGRAM_USER_SESSION_ONLY =
            "This endpoint requires a Telegram USER_SESSION (MTProto) connector profile";
    /** Telegram Bot 模式专用接口提示 */
    public static final String TELEGRAM_BOT_TOKEN_ONLY =
            "This endpoint requires a Telegram BOT_TOKEN connector profile";
    /** Telegram 个人账号 MTProto 凭据缺失 */
    public static final String TELEGRAM_USER_CREDENTIALS_MISSING =
            "Telegram USER_SESSION credentials (telegramApiId, telegramApiHash, telegramSession) are incomplete";
    /** Telegram 消息 ID 必填 */
    public static final String TELEGRAM_MESSAGE_ID_REQUIRED = "messageId must be a positive integer";
    /** Telegram 消息 ID 列表必填 */
    public static final String TELEGRAM_MESSAGE_IDS_REQUIRED = "messageIds must not be empty";
    /** Telegram 搜索关键词必填 */
    public static final String TELEGRAM_SEARCH_QUERY_REQUIRED = "Search query is required";
    /** Telegram 转发来源和目标会话必填 */
    public static final String TELEGRAM_FORWARD_CHATS_REQUIRED = "fromChatId and toChatId are required for forwarding";
    /** Telegram 桥接服务返回错误前缀 */
    public static final String TELEGRAM_BRIDGE_ERROR_PREFIX = "Telegram user bridge returned error: ";
    /** Telegram 交互登录必填参数校验 */
    public static final String TELEGRAM_AUTH_START_REQUIRED = "apiId, apiHash and phone are required";
    /** Telegram 交互登录校验参数缺失 */
    public static final String TELEGRAM_AUTH_VERIFY_REQUIRED = "loginId is required";
    /** Telegram 二维码登录必填参数校验 */
    public static final String TELEGRAM_QR_START_REQUIRED = "apiId and apiHash are required";
    /** Telegram 二维码轮询必填参数校验 */
    public static final String TELEGRAM_QR_POLL_REQUIRED = "qrLoginId is required";
    /** QR 编码文本为空异常 */
    public static final String QR_TEXT_EMPTY = "QR 编码内容不能为空";
    /** QR 编码文本超长异常前缀 */
    public static final String QR_TEXT_TOO_LONG_PREFIX = "QR 编码内容超过最大支持长度（180 字节）: ";
    /** Telegram 侧车地址格式非法提示模板 */
    public static final String TELEGRAM_BRIDGE_URL_INVALID_FMT =
            "Telegram 侧车地址无效（%s）：请填写 tg-user-bridge 的 HTTP 地址（如 http://127.0.0.1:8081 或 http://tg-user-bridge:8081），无需填写 Telegram 官方 DC IP（如 149.154.167.50:443）";
    /** Telegram 侧车服务不可达提示模板 */
    public static final String TELEGRAM_BRIDGE_UNREACHABLE_FMT =
            "无法连接 Telegram 侧车服务 tg-user-bridge（%s），请确认侧车已启动（docker compose up -d 或在 tg-user-bridge 目录执行 go run .）且 MUSE_TELEGRAM_BRIDGE_URL 配置正确";
    /** Telegram 侧车服务请求超时提示模板 */
    public static final String TELEGRAM_BRIDGE_TIMEOUT_FMT =
            "连接 Telegram 侧车服务超时（%s）：%s";

    // =========================================================================
    // 6. SSH / SFTP 中转异常消息
    // =========================================================================

    /** SSH 命令必填 */
    public static final String SSH_COMMAND_REQUIRED = "Command is required";
    /** SFTP 远程路径必填 */
    public static final String SSH_REMOTE_PATH_REQUIRED = "remotePath is required";
    /** SFTP 读取失败前缀 */
    public static final String SSH_SFTP_READ_FAILED_PREFIX = "SFTP read failed: ";
    /** SFTP 写入失败前缀 */
    public static final String SSH_SFTP_WRITE_FAILED_PREFIX = "SFTP write failed: ";
    /** SSH 命令执行超时模板 */
    public static final String SSH_COMMAND_TIMEOUT_FMT = "SSH command timed out after %dms";
    /** SSH 命令非零退出码前缀 */
    public static final String SSH_COMMAND_EXIT_PREFIX = "SSH command exited with code ";
    /** SSH 执行异常失败前缀 */
    public static final String SSH_EXEC_FAILED_PREFIX = "SSH exec failed: ";
    /** SSH 私钥缺失 */
    public static final String SSH_PRIVATE_KEY_MISSING = "SSH privateKey is missing";
    /** 启用白名单时禁止 Shell 链式操作符 */
    public static final String SSH_CHAINING_BLOCKED =
            "Shell chaining operators are blocked when allowedCommandPrefixes is configured";
    /** 命令不在白名单允许前缀范围内 */
    public static final String SSH_COMMAND_NOT_ALLOWED = "Command not allowed by allowedCommandPrefixes policy";
}
