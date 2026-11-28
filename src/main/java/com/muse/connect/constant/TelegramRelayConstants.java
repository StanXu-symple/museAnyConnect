package com.muse.connect.constant;

/**
 * Telegram Bot API 与个人账号 MTProto (gotd/td) 桥接协议、方法与字段常量类。
 */
public final class TelegramRelayConstants {

    private TelegramRelayConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. Telegram Bot API 常量
    // =========================================================================

    /** Telegram Bot API 官方默认根地址 */
    public static final String DEFAULT_BASE_URL = "https://api.telegram.org";
    /** Bot API URL 路径前缀 */
    public static final String BOT_PATH_PREFIX = "/bot";

    /** 发送文本消息方法名 */
    public static final String METHOD_SEND_MESSAGE = "sendMessage";
    /** 拉取机器人更新方法名 */
    public static final String METHOD_GET_UPDATES = "getUpdates";
    /** 获取机器人基本信息方法名 */
    public static final String METHOD_GET_ME = "getMe";
    /** 合法 Bot API 方法名校验正则 */
    public static final String METHOD_NAME_REGEX = "^[a-zA-Z0-9_]+$";

    /** 目标聊天 ID 字段（Bot API） */
    public static final String FIELD_CHAT_ID = "chat_id";
    /** 消息文本字段 */
    public static final String FIELD_TEXT = "text";
    /** 文本解析模式字段（Bot API） */
    public static final String FIELD_PARSE_MODE = "parse_mode";
    /** 静音发送开关字段（Bot API） */
    public static final String FIELD_DISABLE_NOTIFICATION = "disable_notification";
    /** 回复参数对象字段（Bot API） */
    public static final String FIELD_REPLY_PARAMETERS = "reply_parameters";
    /** 回复目标消息 ID 字段（Bot API） */
    public static final String FIELD_MESSAGE_ID = "message_id";
    /** 更新起始偏移量字段 */
    public static final String FIELD_OFFSET = "offset";
    /** 单次拉取数量上限字段 */
    public static final String FIELD_LIMIT = "limit";
    /** 长轮询超时秒数字段 */
    public static final String FIELD_TIMEOUT = "timeout";
    /** Telegram 响应成功标记字段 */
    public static final String FIELD_OK = "ok";
    /** Telegram 桥接响应结果字段 */
    public static final String FIELD_RESULT = "result";
    /** Telegram 桥接响应错误文案字段 */
    public static final String FIELD_ERROR = "error";

    // =========================================================================
    // 2. Telegram 个人账号 MTProto (gotd/td) 桥接服务常量
    // =========================================================================

    /** 默认本地 gotd/td 侧车服务地址 */
    public static final String DEFAULT_BRIDGE_URL = "http://127.0.0.1:8081";
    /** 侧车服务共享鉴权请求头 */
    public static final String HEADER_BRIDGE_TOKEN = "X-Bridge-Token";

    /** 桥接接口：获取当前登录个人账号信息 */
    public static final String BRIDGE_PATH_ME = "/bridge/me";
    /** 桥接接口：获取对话/群组/频道列表 */
    public static final String BRIDGE_PATH_DIALOGS = "/bridge/dialogs";
    /** 桥接接口：拉取历史消息 */
    public static final String BRIDGE_PATH_HISTORY = "/bridge/messages/history";
    /** 桥接接口：搜索群组或全局消息 */
    public static final String BRIDGE_PATH_SEARCH = "/bridge/messages/search";
    /** 桥接接口：发送或回复文本消息 */
    public static final String BRIDGE_PATH_SEND = "/bridge/messages/send";
    /** 桥接接口：编辑消息 */
    public static final String BRIDGE_PATH_EDIT = "/bridge/messages/edit";
    /** 桥接接口：删除消息 */
    public static final String BRIDGE_PATH_DELETE = "/bridge/messages/delete";
    /** 桥接接口：转发消息 */
    public static final String BRIDGE_PATH_FORWARD = "/bridge/messages/forward";
    /** 桥接接口：标记消息已读 */
    public static final String BRIDGE_PATH_READ = "/bridge/messages/read";
    /** 桥接接口：解析用户名或会话信息 */
    public static final String BRIDGE_PATH_RESOLVE = "/bridge/chat/resolve";
    /** 桥接接口：交互式发送登录验证码 */
    public static final String BRIDGE_PATH_AUTH_START = "/bridge/auth/start";
    /** 桥接接口：提交验证码/两步验证密码并导出 Session */
    public static final String BRIDGE_PATH_AUTH_VERIFY = "/bridge/auth/verify";
    /** 桥接接口：发起二维码扫码登录 */
    public static final String BRIDGE_PATH_AUTH_QR_START = "/bridge/auth/qr/start";
    /** 桥接接口：轮询二维码扫码状态或提交 2FA 密码 */
    public static final String BRIDGE_PATH_AUTH_QR_POLL = "/bridge/auth/qr/poll";

    /** 桥接活动流水动作名称 */
    public static final String ACTION_USER_ME = "user.me";
    public static final String ACTION_USER_DIALOGS = "user.dialogs";
    public static final String ACTION_USER_HISTORY = "user.history";
    public static final String ACTION_USER_SEARCH = "user.search";
    public static final String ACTION_USER_SEND = "user.send";
    public static final String ACTION_USER_EDIT = "user.edit";
    public static final String ACTION_USER_DELETE = "user.delete";
    public static final String ACTION_USER_FORWARD = "user.forward";
    public static final String ACTION_USER_READ = "user.read";
    public static final String ACTION_USER_RESOLVE = "user.resolve";

    /** 桥接请求体字段名 */
    public static final String BRIDGE_FIELD_API_ID = "apiId";
    public static final String BRIDGE_FIELD_API_HASH = "apiHash";
    public static final String BRIDGE_FIELD_SESSION_STRING = "sessionString";
    public static final String BRIDGE_FIELD_PROXY_HOST = "proxyHost";
    public static final String BRIDGE_FIELD_PROXY_PORT = "proxyPort";
    public static final String BRIDGE_FIELD_CHAT_ID = "chatId";
    public static final String BRIDGE_FIELD_FROM_CHAT_ID = "fromChatId";
    public static final String BRIDGE_FIELD_TO_CHAT_ID = "toChatId";
    public static final String BRIDGE_FIELD_TEXT = "text";
    public static final String BRIDGE_FIELD_PARSE_MODE = "parseMode";
    public static final String BRIDGE_FIELD_DISABLE_NOTIFICATION = "disableNotification";
    public static final String BRIDGE_FIELD_REPLY_TO_MESSAGE_ID = "replyToMessageId";
    public static final String BRIDGE_FIELD_NO_WEBPAGE = "noWebpage";
    public static final String BRIDGE_FIELD_MESSAGE_ID = "messageId";
    public static final String BRIDGE_FIELD_MESSAGE_IDS = "messageIds";
    public static final String BRIDGE_FIELD_REVOKE = "revoke";
    public static final String BRIDGE_FIELD_DROP_AUTHOR = "dropAuthor";
    public static final String BRIDGE_FIELD_SILENT = "silent";
    public static final String BRIDGE_FIELD_MAX_ID = "maxId";
    public static final String BRIDGE_FIELD_MIN_ID = "minId";
    public static final String BRIDGE_FIELD_OFFSET_ID = "offsetId";
    public static final String BRIDGE_FIELD_OFFSET_DATE = "offsetDate";
    public static final String BRIDGE_FIELD_FOLDER_ID = "folderId";
    public static final String BRIDGE_FIELD_QUERY = "query";
    public static final String BRIDGE_FIELD_PHONE = "phone";
    public static final String BRIDGE_FIELD_LOGIN_ID = "loginId";
    public static final String BRIDGE_FIELD_CODE = "code";
    public static final String BRIDGE_FIELD_PASSWORD = "password";
    public static final String BRIDGE_FIELD_QR_LOGIN_ID = "qrLoginId";
    public static final String BRIDGE_FIELD_QR_URL = "qrUrl";
    public static final String BRIDGE_FIELD_QR_SVG = "qrSvg";

    /** 二维码 SVG 渲染模板片段 */
    public static final String QR_SVG_PREFIX = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ";
    public static final String QR_SVG_MIDDLE =
            "\" width=\"200\" height=\"200\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Telegram QR\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                    + "<path fill=\"#0f172a\" d=\"";
    public static final String QR_SVG_PATH_UNIT = "h1v1h-1z";
    public static final String QR_SVG_SUFFIX = "\"/></svg>";
}
