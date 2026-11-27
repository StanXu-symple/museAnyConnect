package com.muse.connect.aidoc;

import com.muse.connect.model.ConnectorType;

/**
 * AI 接口文档元数据统一枚举类。
 * <p>
 * 集中管理 {@link AiApiGroup}、{@link AiEndpoint}、{@link AiField} 注解中使用的英文文案、排序、
 * 关联连接器类型、必填标记与示例值，避免在各 Controller 中散落硬编码字符串。
 * 后期新增接口或参数时，只需在此枚举新增条目并在注解中引用即可。
 */
public enum AiDocMeta {

    /** 默认空占位项：当注解未指定枚举条目时作为回退占位使用 */
    NONE("", "", null, 100, false, ""),

    // =========================================================================
    // 1. 接口分组定义（用于 @AiApiGroup）
    // =========================================================================

    /** X (Twitter) 中转接口分组：转发 X API 请求，支持 Bearer/OAuth 2.0、OAuth 1.0a 自动签名及普通用户 Web Cookie (auth_token + ct0) */
    GROUP_X_RELAY(
            "X (Twitter) Relay",
            "Forward X requests using configured Bearer/OAuth 2.0, OAuth 1.0a (auto HMAC-SHA1), or Web Cookie (auth_token + ct0) profiles.",
            ConnectorType.X,
            10
    ),

    /** Telegram 机器人中转接口分组：发送消息、拉取更新以及透传调用任意 Bot API 方法 */
    GROUP_TELEGRAM_RELAY(
            "Telegram Bot Relay",
            "Send messages, fetch updates, or invoke any Telegram Bot API method through configured bot profiles.",
            ConnectorType.TELEGRAM,
            20
    ),

    /** SSH 远程服务器中转接口分组：执行远程 Shell 命令与 SFTP 文件读写，遵循命令前缀白名单约束 */
    GROUP_SSH_RELAY(
            "SSH Server Relay",
            "Execute remote commands and read/write files over SFTP. Respect each profile's allowedCommandPrefixes constraint if configured.",
            ConnectorType.SSH,
            30
    ),

    /** 连接器发现与运行状态分组：查看脱敏后的连接器列表与详情、执行连通性测试、查询运行统计 */
    GROUP_CONNECTOR_DISCOVERY(
            "Connector Discovery & Health",
            "Inspect configured connectors (secrets always masked), run connectivity checks, and view runtime statistics.",
            null,
            40
    ),

    // =========================================================================
    // 2. 接口端点定义（用于 @AiEndpoint）
    // =========================================================================

    /** X 快捷发推或回复接口：支持 OAuth 1.0a、具备写权限的 OAuth 2.0 User Token 或 Web Cookie (auth_token + ct0) */
    EP_X_POST_TWEET(
            "Post a new tweet (or reply)",
            "Requires an OAUTH1A profile with Read and Write permissions, an OAuth 2.0 User Access Token with tweet.write scope, or a COOKIE_AUTH (auth_token + ct0) profile.",
            10
    ),

    /** X 快捷搜索近期推文接口 */
    EP_X_SEARCH_RECENT(
            "Search recent tweets",
            "",
            20
    ),

    /** X 按用户名查询用户资料接口 */
    EP_X_USER_BY_USERNAME(
            "Look up an X user by username",
            "",
            30
    ),

    /** X 通用 API v2 转发接口：用于快捷接口未覆盖的任意 /2/** 端点 */
    EP_X_GENERIC_REQUEST(
            "Send a generic X API v2 request",
            "Use this for any X API v2 endpoint not covered by the shortcut endpoints.",
            40
    ),

    /** Telegram 快捷发送文本消息接口 */
    EP_TELEGRAM_SEND_MESSAGE(
            "Send a text message to a Telegram chat",
            "",
            10
    ),

    /** Telegram 拉取机器人消息更新接口（可用于获取 Chat ID 与最新消息） */
    EP_TELEGRAM_GET_UPDATES(
            "Fetch incoming bot updates (messages / chat IDs)",
            "",
            20
    ),

    /** Telegram 通用 Bot API 方法透传接口：直接将 JSON 负载转发至 /bot<token>/<method> */
    EP_TELEGRAM_CALL_METHOD(
            "Invoke any Telegram Bot API method directly",
            "Passes JSON payload directly to https://api.telegram.org/bot<token>/<method>.",
            30
    ),

    /** SSH 远程执行 Shell 命令接口：返回 stdout、stderr、exitCode，受命令前缀白名单约束 */
    EP_SSH_EXEC(
            "Execute a shell command on a remote SSH host",
            "Returns stdout, stderr, and exitCode inside data. Blocked if command violates allowedCommandPrefixes.",
            10
    ),

    /** SSH SFTP 读取远程 UTF-8 文本文件接口 */
    EP_SSH_SFTP_READ(
            "Read a UTF-8 text file via SFTP",
            "",
            20
    ),

    /** SSH SFTP 写入或追加远程 UTF-8 文本文件接口 */
    EP_SSH_SFTP_WRITE(
            "Write or append a UTF-8 text file via SFTP",
            "",
            30
    ),

    /** 列出所有已配置连接器接口（敏感密钥已脱敏） */
    EP_CONNECTOR_LIST(
            "List all configured connector profiles (secrets masked)",
            "",
            10
    ),

    /** 获取单个连接器配置详情接口（敏感密钥已脱敏） */
    EP_CONNECTOR_GET(
            "Get details of a single connector profile (secrets masked)",
            "",
            20
    ),

    /** 对指定连接器执行实时连通性测试接口 */
    EP_CONNECTOR_TEST(
            "Run a live connectivity test against a connector profile",
            "",
            30
    ),

    /** 查询服务运行状态、H2 内存占用与最近中转流水接口 */
    EP_SYSTEM_STATS(
            "Get runtime statistics, H2 memory usage, and recent relay activity",
            "",
            40
    ),

    // =========================================================================
    // 3. 参数与请求体字段定义（用于 @AiField）
    // =========================================================================

    /** 已启用的 X 连接器 Profile ID */
    FIELD_X_PROFILE_ID("Enabled X connector profile ID", false, ""),

    /** X 通用转发请求的 HTTP 方法（如 GET、POST、DELETE），必填 */
    FIELD_X_METHOD("HTTP method (GET, POST, DELETE, etc.)", true, "GET"),

    /** X API 路径（API v2 以 /2/ 开头，Web Cookie 模式以 /i/api/ 开头），必填 */
    FIELD_X_PATH("X API path (e.g. /2/users/me for OAuth/Bearer, or /i/api/... for COOKIE_AUTH)", true, "/2/users/me"),

    /** X 通用转发可选 URL 查询参数表 */
    FIELD_X_QUERY_PARAMS("Optional URL query parameters", false, "{}"),

    /** X 通用转发可选 JSON 请求体（用于 POST/PUT 等写请求） */
    FIELD_X_BODY("Optional JSON request body for POST/PUT", false, ""),

    /** 推文正文内容，必填 */
    FIELD_X_TWEET_TEXT("Tweet text content", true, "Hello from Muse!"),

    /** 可选回复目标推文的 ID */
    FIELD_X_REPLY_TO_TWEET_ID("Optional tweet ID to reply to", false, ""),

    /** X 推文搜索查询表达式 */
    FIELD_X_SEARCH_QUERY("X search query expression", false, "spring boot"),

    /** 搜索返回的最大推文条数（范围 10..100） */
    FIELD_X_MAX_RESULTS("Max results (10..100)", false, "10"),

    /** X 用户名（不含 @ 前缀） */
    FIELD_X_USERNAME("X handle without @", false, "X"),

    /** 已启用的 Telegram 连接器 Profile ID */
    FIELD_TELEGRAM_PROFILE_ID("Enabled Telegram connector profile ID", false, ""),

    /** 目标聊天 Chat ID（若连接器已配置 defaultChatId 则可省略） */
    FIELD_TELEGRAM_CHAT_ID("Target chat ID (optional if profile has defaultChatId configured)", false, "-1001234567890"),

    /** 待发送的 Telegram 文本消息内容，必填 */
    FIELD_TELEGRAM_TEXT("Message text to send", true, "Hello from Muse!"),

    /** 可选文本格式化模式：HTML、MarkdownV2 或留空纯文本 */
    FIELD_TELEGRAM_PARSE_MODE("Optional formatting mode: HTML, MarkdownV2, or omit for plain text", false, "HTML"),

    /** 是否静音发送消息（不触发客户端提示音） */
    FIELD_TELEGRAM_DISABLE_NOTIFICATION("Send silently without notification sound", false, "false"),

    /** 可选回复目标消息的 ID */
    FIELD_TELEGRAM_REPLY_TO_MESSAGE_ID("Optional message ID to reply to", false, ""),

    /** 拉取更新的起始 update_id 偏移量 */
    FIELD_TELEGRAM_OFFSET("First update ID to return", false, ""),

    /** 单次拉取最大更新条数（范围 1..100） */
    FIELD_TELEGRAM_LIMIT("Max updates to return (1..100)", false, "10"),

    /** 长轮询超时时间（秒） */
    FIELD_TELEGRAM_TIMEOUT("Long polling timeout in seconds", false, ""),

    /** 要调用的 Telegram Bot API 方法名（如 getMe、sendPhoto） */
    FIELD_TELEGRAM_METHOD("Telegram Bot API method name", false, "getMe"),

    /** 透传给 Telegram Bot API 方法的可选 JSON 参数对象 */
    FIELD_TELEGRAM_PAYLOAD("Optional JSON parameters for the Bot API method", false, "{}"),

    /** 已启用的 SSH 连接器 Profile ID */
    FIELD_SSH_PROFILE_ID("Enabled SSH connector profile ID", false, ""),

    /** 在远程 SSH 主机上执行的 Shell 命令，必填 */
    FIELD_SSH_COMMAND("Shell command to execute on the remote server", true, "uname -a && uptime"),

    /** 可选远程命令执行超时时间（毫秒） */
    FIELD_SSH_TIMEOUT_MS("Optional execution timeout in milliseconds", false, "10000"),

    /** 待读取的远程文本文件绝对路径，必填 */
    FIELD_SSH_REMOTE_PATH_READ("Absolute path of the remote text file to read", true, "/etc/hostname"),

    /** SFTP 单次读取的最大字节数上限 */
    FIELD_SSH_MAX_BYTES("Maximum bytes to read", false, "65536"),

    /** 待写入的远程文件绝对路径，必填 */
    FIELD_SSH_REMOTE_PATH_WRITE("Absolute path of the remote file to write", true, "/tmp/muse-note.txt"),

    /** 待写入远程文件的 UTF-8 文本内容，必填 */
    FIELD_SSH_CONTENT("UTF-8 text content to write", true, "hello from muse"),

    /** 是否以追加模式写入文件（true 追加，false 覆盖） */
    FIELD_SSH_APPEND("True to append instead of overwriting", false, "false"),

    /** 通用连接器配置 Profile ID */
    FIELD_CONNECTOR_ID("Connector profile ID", false, "x-main");

    private final String title;
    private final String description;
    private final ConnectorType connectorType;
    private final int order;
    private final boolean required;
    private final String example;

    /** 分组构造器（用于 @AiApiGroup） */
    AiDocMeta(String title, String description, ConnectorType connectorType, int order) {
        this(title, description, connectorType, order, false, "");
    }

    /** 端点构造器（用于 @AiEndpoint） */
    AiDocMeta(String title, String description, int order) {
        this(title, description, null, order, false, "");
    }

    /** 参数/字段构造器（用于 @AiField） */
    AiDocMeta(String description, boolean required, String example) {
        this("", description, null, 100, required, example);
    }

    AiDocMeta(
            String title,
            String description,
            ConnectorType connectorType,
            int order,
            boolean required,
            String example
    ) {
        this.title = title;
        this.description = description;
        this.connectorType = connectorType;
        this.order = order;
        this.required = required;
        this.example = example;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public ConnectorType connectorType() {
        return connectorType;
    }

    public String connectorTypeName() {
        return connectorType != null ? connectorType.name() : "";
    }

    public int order() {
        return order;
    }

    public boolean required() {
        return required;
    }

    public String example() {
        return example;
    }
}
