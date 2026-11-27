package com.muse.connect.constant;

/**
 * Web 控制台 Model 属性名、REST 响应字段键及页面提示消息常量类。
 */
public final class WebViewConstants {

    private WebViewConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. Thymeleaf Model / Flash 属性键名
    // =========================================================================

    public static final String ATTR_AUTH_ENABLED = "authEnabled";
    public static final String ATTR_ALLOW_RAW_EXPORT = "allowRawExport";
    public static final String ATTR_CURRENT_USER = "currentUser";
    public static final String ATTR_CSRF_TOKEN = "csrfToken";
    public static final String ATTR_STATS = "stats";
    public static final String ATTR_CONNECTORS = "connectors";
    public static final String ATTR_ACTIVITIES = "activities";
    public static final String ATTR_API_KEY_ENABLED = "apiKeyEnabled";
    public static final String ATTR_PROFILE = "profile";
    public static final String ATTR_EDITING = "editing";
    public static final String ATTR_CONNECTOR_TYPES = "connectorTypes";
    public static final String ATTR_AUTH_MODES = "authModes";
    public static final String ATTR_SELECTED_PROFILE_ID = "selectedProfileId";
    public static final String ATTR_ONLY_ACTIVE = "onlyActive";
    public static final String ATTR_LLMS_URL = "llmsUrl";
    public static final String ATTR_AI_MARKDOWN = "aiMarkdown";
    public static final String ATTR_NEXT = "next";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_MESSAGE = "message";
    public static final String ATTR_ERROR = "error";

    // =========================================================================
    // 2. 控制层通用 JSON 响应字段名
    // =========================================================================

    public static final String KEY_STATUS = "status";
    public static final String VALUE_UP = "UP";
    public static final String KEY_ID = "id";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_DELETED = "deleted";
    public static final String KEY_IMPORTED = "imported";
    public static final String KEY_STATS = "stats";
    public static final String KEY_RECENT_ACTIVITY = "recentActivity";

    // =========================================================================
    // 3. 控制台提示消息与格式化模板
    // =========================================================================

    /** 退出登录成功提示 */
    public static final String MSG_LOGGED_OUT = "已安全退出登录。";
    /** 登录 IP 锁定提示模板（填入剩余分钟数） */
    public static final String MSG_LOCKOUT_FMT = "登录失败次数过多，当前 IP 已临时锁定，请约 %d 分钟后再试。";
    /** 保存连接器成功提示模板（依次填入名称、ID） */
    public static final String MSG_CONNECTOR_SAVED_FMT = "已保存连接器 [%s] (%s)";
    /** 连接器启用提示模板 */
    public static final String MSG_CONNECTOR_ENABLED_FMT = "连接器 %s 已启用";
    /** 连接器停用提示模板 */
    public static final String MSG_CONNECTOR_DISABLED_FMT = "连接器 %s 已停用";
    /** 删除连接器成功提示模板 */
    public static final String MSG_CONNECTOR_DELETED_FMT = "已删除连接器 %s";
    /** 连通性测试成功提示模板（依次填入 ID、耗时毫秒、HTTP 状态码） */
    public static final String MSG_TEST_SUCCESS_FMT = "连通性测试成功 [%s]，耗时 %dms (HTTP %d)";
    /** 连通性测试失败提示模板（依次填入 ID、错误原因、HTTP 状态码） */
    public static final String MSG_TEST_FAILED_FMT = "连通性测试失败 [%s]: %s (HTTP %d)";
    /** 导入连接配置成功提示模板（填入导入条数） */
    public static final String MSG_IMPORT_SUCCESS_FMT = "成功导入 %d 条连接配置";

    /** 活动记录时间格式 */
    public static final String TIME_PATTERN_HMS = "HH:mm:ss";
    /** 参数校验错误拼接分隔符 */
    public static final String VALIDATION_ERROR_DELIMITER = "; ";
}
