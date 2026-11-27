package com.muse.connect.constant;

/**
 * SQLite 存储表结构、SQL 语句、配置属性键及连接器 ID 规则常量类。
 */
public final class StorageConstants {

    private StorageConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. Spring 配置属性前缀与键名
    // =========================================================================

    /** 中转配置属性前缀 */
    public static final String PREFIX_MUSE_RELAY = "muse.relay";
    /** 管理员认证配置属性前缀 */
    public static final String PREFIX_MUSE_AUTH = "muse.auth";
    /** 数据根目录属性键 */
    public static final String PROP_DATA_DIR = "muse.data.dir";
    /** SQLite 目录属性键 */
    public static final String PROP_SQLITE_DIR = "muse.data.sqlite-dir";
    /** 日志目录属性键 */
    public static final String PROP_LOG_DIR = "muse.data.log-dir";
    /** Spring 数据源 URL 属性键 */
    public static final String PROP_DATASOURCE_URL = "spring.datasource.url";

    // =========================================================================
    // 2. SQLite JDBC URL 解析常量
    // =========================================================================

    public static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
    public static final String SQLITE_MEMORY_TARGET = ":memory:";
    public static final String SQLITE_FILE_MEMORY_PREFIX = "file::memory:";
    public static final String SQLITE_FILE_PREFIX = "file:";

    // =========================================================================
    // 3. 数据库列名
    // =========================================================================

    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_TYPE = "type";
    public static final String COL_AUTH_MODE = "auth_mode";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_CONFIG_JSON = "config_json";
    public static final String COL_UPDATED_AT = "updated_at";

    // =========================================================================
    // 4. SQL 语句
    // =========================================================================

    /** 查询全部连接器配置（按更新时间倒序） */
    public static final String SQL_FIND_ALL =
            "SELECT id, name, type, auth_mode, enabled, config_json, updated_at FROM connector_profile ORDER BY updated_at DESC";
    /** 按主键查询连接器配置 */
    public static final String SQL_FIND_BY_ID =
            "SELECT id, name, type, auth_mode, enabled, config_json, updated_at FROM connector_profile WHERE id = ?";
    /** 更新已有连接器配置 */
    public static final String SQL_UPDATE =
            "UPDATE connector_profile SET name = ?, type = ?, auth_mode = ?, enabled = ?, config_json = ?, updated_at = ? WHERE id = ?";
    /** 插入新连接器配置 */
    public static final String SQL_INSERT =
            "INSERT INTO connector_profile (id, name, type, auth_mode, enabled, config_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    /** 按主键删除连接器配置 */
    public static final String SQL_DELETE_BY_ID =
            "DELETE FROM connector_profile WHERE id = ?";
    /** 统计全部连接器总数 */
    public static final String SQL_COUNT =
            "SELECT COUNT(*) FROM connector_profile";
    /** 统计已启用连接器总数 */
    public static final String SQL_COUNT_ENABLED =
            "SELECT COUNT(*) FROM connector_profile WHERE enabled = 1";
    /** 统计连接器表有效数据字节数 */
    public static final String SQL_QUERY_DB_DATA_BYTES =
            "SELECT COALESCE(SUM(LENGTH(CAST(id AS BLOB)) + LENGTH(CAST(name AS BLOB)) + LENGTH(CAST(type AS BLOB)) + LENGTH(CAST(auth_mode AS BLOB)) + LENGTH(CAST(config_json AS BLOB)) + 9), 0) FROM connector_profile";

    // =========================================================================
    // 5. 连接器 ID 正则与生成规则
    // =========================================================================

    /** 连接器 ID 合法字符正则 */
    public static final String CONNECTOR_ID_PATTERN = "^[a-zA-Z0-9_-]{0,64}$";
    /** 替换非字母数字为连字符的正则 */
    public static final String SLUG_NON_ALNUM_REGEX = "[^a-z0-9]+";
    /** 去除首尾连字符的正则 */
    public static final String SLUG_EDGE_HYPHEN_REGEX = "^-+|-+$";
    /** 连字符 */
    public static final String HYPHEN = "-";
}
