package com.muse.connect.constant;

/**
 * 路由路径、视图名称、重定向目标与请求参数默认值常量类。
 * 集中管理所有 Spring MVC 的 {@code @RequestMapping} / {@code @GetMapping} / {@code @PostMapping} / {@code @DeleteMapping} 路径
 * 及安全过滤器使用的路由匹配前缀，避免在各 Controller 与 Filter 中硬编码路径字符串。
 */
public final class RouteConstants {

    private RouteConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. 通用路径与安全放行路由
    // =========================================================================

    /** 根路径 */
    public static final String ROOT = "/";
    /** 健康检查端点 */
    public static final String HEALTH = "/health";
    /** 登录页与登录提交路由 */
    public static final String LOGIN = "/login";
    /** 退出登录路由 */
    public static final String LOGOUT = "/logout";
    /** 站点图标路径 */
    public static final String FAVICON = "/favicon.ico";
    /** 静态样式目录前缀 */
    public static final String CSS_PREFIX = "/css/";
    /** 机器 API 路径前缀 */
    public static final String API_PREFIX = "/api/";

    // =========================================================================
    // 2. REST API 控制器路由（@RequestMapping / 子路径）
    // =========================================================================

    /** 通用 API 基础路径 */
    public static final String API_BASE = "/api";
    /** 连接器集合路径 */
    public static final String API_CONNECTORS = "/connectors";
    /** 单个连接器路径 */
    public static final String API_CONNECTOR_BY_ID = "/connectors/{id}";
    /** 连接器启停切换路径 */
    public static final String API_CONNECTOR_TOGGLE = "/connectors/{id}/toggle";
    /** 连接器连通性测试路径 */
    public static final String API_CONNECTOR_TEST = "/connectors/{id}/test";
    /** 连接器导出路径 */
    public static final String API_CONNECTOR_EXPORT = "/connectors/export";
    /** 连接器导入路径 */
    public static final String API_CONNECTOR_IMPORT = "/connectors/import";
    /** Telegram 个人账号交互登录：发送验证码 */
    public static final String API_TELEGRAM_AUTH_START = "/connectors/telegram/auth/start";
    /** Telegram 个人账号交互登录：提交验证码/2FA 密码 */
    public static final String API_TELEGRAM_AUTH_VERIFY = "/connectors/telegram/auth/verify";
    /** Telegram 个人账号二维码登录：生成登录二维码 */
    public static final String API_TELEGRAM_AUTH_QR_START = "/connectors/telegram/auth/qr/start";
    /** Telegram 个人账号二维码登录：轮询扫码状态或提交 2FA 密码 */
    public static final String API_TELEGRAM_AUTH_QR_POLL = "/connectors/telegram/auth/qr/poll";
    /** 系统统计信息路径 */
    public static final String API_STATS = "/stats";

    /** AI 文档控制器基础路径 */
    public static final String API_AI_BASE = "/api/ai";
    /** AI Markdown 文档子路径 */
    public static final String AI_LLMS_TXT = "/llms.txt";
    /** AI 结构化目录子路径 */
    public static final String AI_CATALOG = "/catalog";
    /** AI Markdown 文档完整路径 */
    public static final String API_AI_LLMS_TXT_FULL = "/api/ai/llms.txt";

    /** X (Twitter) 中转控制器基础路径 */
    public static final String API_RELAY_X_BASE = "/api/relay/x/{profileId}";
    /** X 通用请求转发子路径 */
    public static final String X_REQUEST = "/request";
    /** X 快捷发推子路径 */
    public static final String X_TWEET = "/tweet";
    /** X 快捷搜索子路径 */
    public static final String X_SEARCH = "/search";
    /** X 按用户名查询资料子路径 */
    public static final String X_USER_BY_USERNAME = "/users/by/username/{username}";

    /** Telegram 中转控制器基础路径 */
    public static final String API_RELAY_TELEGRAM_BASE = "/api/relay/telegram/{profileId}";
    /** Telegram 发送消息子路径（Bot 与个人账号通用） */
    public static final String TELEGRAM_SEND = "/send";
    /** Telegram 拉取更新子路径（Bot 模式） */
    public static final String TELEGRAM_UPDATES = "/updates";
    /** Telegram 通用方法透传子路径（Bot 模式） */
    public static final String TELEGRAM_METHOD = "/method/{method}";
    /** Telegram 个人账号：获取对话/群组/频道列表 */
    public static final String TELEGRAM_DIALOGS = "/dialogs";
    /** Telegram 个人账号：读取聊天历史消息 */
    public static final String TELEGRAM_HISTORY = "/history";
    /** Telegram 个人账号：搜索群组或全局消息 */
    public static final String TELEGRAM_SEARCH = "/search";
    /** Telegram 个人账号：编辑消息 */
    public static final String TELEGRAM_EDIT = "/edit";
    /** Telegram 个人账号：删除消息 */
    public static final String TELEGRAM_DELETE = "/delete";
    /** Telegram 个人账号：转发消息 */
    public static final String TELEGRAM_FORWARD = "/forward";
    /** Telegram 个人账号：标记已读 */
    public static final String TELEGRAM_READ = "/read";
    /** Telegram 个人账号：解析用户名或会话 */
    public static final String TELEGRAM_RESOLVE = "/resolve";

    /** SSH 中转控制器基础路径 */
    public static final String API_RELAY_SSH_BASE = "/api/relay/ssh/{profileId}";
    /** SSH 远程执行命令子路径 */
    public static final String SSH_EXEC = "/exec";
    /** SSH SFTP 读取文件子路径 */
    public static final String SSH_SFTP_READ = "/sftp/read";
    /** SSH SFTP 写入文件子路径 */
    public static final String SSH_SFTP_WRITE = "/sftp/write";

    // =========================================================================
    // 3. Web 控制台页面与表单路由
    // =========================================================================

    /** 新建连接器表单页面路径 */
    public static final String WEB_CONNECTORS_NEW = "/connectors/new";
    /** 编辑连接器表单页面路径 */
    public static final String WEB_CONNECTORS_EDIT = "/connectors/{id}/edit";
    /** 保存连接器表单提交路径 */
    public static final String WEB_CONNECTORS_SAVE = "/connectors/save";
    /** 控制台切换连接器启停路径 */
    public static final String WEB_CONNECTORS_TOGGLE = "/connectors/{id}/toggle";
    /** 控制台删除连接器路径 */
    public static final String WEB_CONNECTORS_DELETE = "/connectors/{id}/delete";
    /** 控制台测试连接器连通性路径 */
    public static final String WEB_CONNECTORS_TEST = "/connectors/{id}/test";
    /** 控制台导出连接器路径 */
    public static final String WEB_CONNECTORS_EXPORT = "/connectors/export";
    /** 控制台导入连接器路径 */
    public static final String WEB_CONNECTORS_IMPORT = "/connectors/import";
    /** 在线调试页面路径 */
    public static final String WEB_PLAYGROUND = "/playground";
    /** AI 文档预览页面路径 */
    public static final String WEB_AI_DOCS = "/ai-docs";

    // =========================================================================
    // 4. 重定向与视图名称
    // =========================================================================

    /** Spring MVC 重定向前缀 */
    public static final String REDIRECT_PREFIX = "redirect:";
    /** 重定向至首页 */
    public static final String REDIRECT_ROOT = "redirect:/";
    /** 退出登录后重定向至登录页 */
    public static final String REDIRECT_LOGIN_LOGGED_OUT = "redirect:/login?loggedOut=1";
    /** 登录跳转回原地址查询前缀 */
    public static final String LOGIN_NEXT_PREFIX = "/login?next=";
    /** 仅启用连接器查询参数后缀 */
    public static final String QUERY_ONLY_ACTIVE_TRUE = "?onlyActive=true";

    /** 非法双斜杠重定向前缀 */
    public static final String UNSAFE_REDIRECT_DOUBLE_SLASH = "//";
    /** 非法反斜杠重定向前缀 */
    public static final String UNSAFE_REDIRECT_BACKSLASH = "/\\";
    /** 回车符 */
    public static final String CHAR_CR = "\r";
    /** 换行符 */
    public static final String CHAR_LF = "\n";

    /** 控制台首页视图名 */
    public static final String VIEW_DASHBOARD = "dashboard";
    /** 连接器表单视图名 */
    public static final String VIEW_CONNECTOR_FORM = "connector-form";
    /** 在线调试视图名 */
    public static final String VIEW_PLAYGROUND = "playground";
    /** AI 文档视图名 */
    public static final String VIEW_AI_DOCS = "ai-docs";
    /** 登录页视图名 */
    public static final String VIEW_LOGIN = "login";

    // =========================================================================
    // 5. 请求参数名与编译期默认值
    // =========================================================================

    /** 空字符串默认值 */
    public static final String EMPTY = "";
    /** 布尔 false 默认值 */
    public static final String DEFAULT_FALSE = "false";
    /** 新建连接器默认类型 X */
    public static final String DEFAULT_CONNECTOR_TYPE_X = "X";
    /** X 搜索默认返回条数 */
    public static final String DEFAULT_X_MAX_RESULTS = "10";
    /** Telegram 拉取更新默认条数 */
    public static final String DEFAULT_TELEGRAM_LIMIT = "20";
    /** Telegram 个人账号对话列表默认条数 */
    public static final String DEFAULT_TELEGRAM_DIALOGS_LIMIT = "30";
    /** Telegram 长轮询默认超时秒数 */
    public static final String DEFAULT_TELEGRAM_TIMEOUT = "0";
    /** 控制台导入表单参数名 */
    public static final String PARAM_JSON_PAYLOAD = "jsonPayload";
    /** 查询字符串问号分隔符 */
    public static final String QUERY_PREFIX = "?";
    /** 查询参数连接符 */
    public static final String QUERY_AMP = "&";
}
