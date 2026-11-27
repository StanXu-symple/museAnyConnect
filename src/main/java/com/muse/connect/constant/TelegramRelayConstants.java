package com.muse.connect.constant;

/**
 * Telegram Bot API 中转协议、接口方法与请求/响应字段常量类。
 */
public final class TelegramRelayConstants {

    private TelegramRelayConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

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

    /** 目标聊天 ID 字段 */
    public static final String FIELD_CHAT_ID = "chat_id";
    /** 消息文本字段 */
    public static final String FIELD_TEXT = "text";
    /** 文本解析模式字段 */
    public static final String FIELD_PARSE_MODE = "parse_mode";
    /** 静音发送开关字段 */
    public static final String FIELD_DISABLE_NOTIFICATION = "disable_notification";
    /** 回复参数对象字段 */
    public static final String FIELD_REPLY_PARAMETERS = "reply_parameters";
    /** 回复目标消息 ID 字段 */
    public static final String FIELD_MESSAGE_ID = "message_id";
    /** 更新起始偏移量字段 */
    public static final String FIELD_OFFSET = "offset";
    /** 单次拉取数量上限字段 */
    public static final String FIELD_LIMIT = "limit";
    /** 长轮询超时秒数字段 */
    public static final String FIELD_TIMEOUT = "timeout";
    /** Telegram 响应成功标记字段 */
    public static final String FIELD_OK = "ok";
}
