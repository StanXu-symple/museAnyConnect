package com.muse.connect.constant;

/**
 * AI 接口文档扫描、Markdown (llms.txt) 渲染与结构化目录常量类。
 */
public final class AiDocConstants {

    private AiDocConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    /** Spring MVC 处理器映射 Bean 名称 */
    public static final String HANDLER_MAPPING_BEAN = "requestMappingHandlerMapping";

    // =========================================================================
    // 1. 服务标识与目录 JSON 字段键名
    // =========================================================================

    public static final String SERVICE_NAME = "MuseAnyConnect";
    public static final String KEY_SERVICE = "service";
    public static final String KEY_BASE_URL = "baseUrl";
    public static final String KEY_AUTH_HEADER = "authHeader";
    public static final String KEY_ACTIVE_CONNECTORS = "activeConnectors";
    public static final String KEY_ENDPOINTS = "endpoints";
    public static final String KEY_PROFILE_ID = "profileId";
    public static final String KEY_NAME = "name";
    public static final String KEY_TYPE = "type";
    public static final String KEY_AUTH_MODE = "authMode";
    public static final String KEY_CONSTRAINTS = "constraints";

    // =========================================================================
    // 2. Markdown (llms.txt) 文档模板片段
    // =========================================================================

    public static final String MD_TITLE = "# MuseAnyConnect — AI API Guide (llms.txt)\n\n";
    public static final String MD_SUBTITLE =
            "> Dynamically generated from controller `@AiApiGroup` / `@AiEndpoint` annotations and currently enabled connector profiles.\n\n";
    public static final String MD_SECTION_AUTH = "## 1. Connection & Authentication\n\n";
    public static final String MD_BASE_URL_PREFIX = "- **Base URL**: `";
    public static final String MD_AUTH_HEADER_PREFIX = "- **Auth Header**: `";
    public static final String MD_AUTH_HEADER_SUFFIX =
            ": <YOUR_MUSE_RELAY_API_KEY>` (required on all `/api/**` calls)\n";
    public static final String MD_CONTENT_TYPE_LINE =
            "- **Content-Type**: `application/json` for POST/PUT requests\n";
    public static final String MD_UNIFIED_RESPONSE_LINE =
            "- **Unified Relay Response**: `{\"success\": true|false, \"profileId\": \"...\", \"connectorType\": \"X|TELEGRAM|SSH\", \"statusCode\": 200, \"durationMs\": 123, \"data\": ..., \"error\": null}`\n\n";

    public static final String MD_SECTION_PROFILES = "## 2. Active Connector Profiles (Live, Secrets Masked)\n\n";
    public static final String MD_NO_PROFILES =
            "_No enabled connector profiles configured yet. Create and enable profiles in the web console first._\n\n";
    public static final String MD_PROFILE_TABLE_HEADER =
            "| profileId | Name | Type | Auth Mode | Capabilities / Constraints |\n";
    public static final String MD_PROFILE_TABLE_DIVIDER = "| :--- | :--- | :--- | :--- | :--- |\n";

    public static final String MD_SECTION_ENDPOINTS = "## 3. Available API Endpoints\n\n";
    public static final String MD_NO_ENDPOINTS = "_No endpoints available for the current filter._\n";
    public static final String MD_GROUP_HEADING_PREFIX = "### ";
    public static final String MD_ACTIVE_PROFILES_NONE_PREFIX = "- **Active `profileId`s**: _None currently enabled for type `";
    public static final String MD_ACTIVE_PROFILES_NONE_SUFFIX = "`_\n";
    public static final String MD_ACTIVE_PROFILES_PREFIX = "- **Active `profileId`s**: ";
    public static final String MD_ENDPOINT_HEADING_PREFIX = "#### `";
    public static final String MD_ENDPOINT_HEADING_MID = "` — ";
    public static final String MD_PATH_PARAMS_HEADER = "- **Path Params**:\n";
    public static final String MD_QUERY_PARAMS_HEADER = "- **Query Params**:\n";
    public static final String MD_JSON_BODY_PREFIX = "- **JSON Body** (`";
    public static final String MD_PARAM_BULLET_PREFIX = "  - `";
    public static final String MD_PARAM_TYPE_OPEN = "` (`";
    public static final String MD_PARAM_COMMA_SPACE = "`, ";
    public static final String MD_PARAM_DEFAULT_PREFIX = ", default=`";
    public static final String MD_PARAM_DESC_SEP = ": ";
    public static final String MD_PARAM_EXAMPLE_OPEN = " (e.g. `";
    public static final String MD_PARAM_EXAMPLE_CLOSE = "`)";
    public static final String MD_CODE_BASH_OPEN = "\n```bash\n";
    public static final String MD_CODE_CLOSE = "```\n\n";
    public static final String MD_EXAMPLE_RESPONSE_OPEN = "Example response:\n```json\n";
    public static final String MD_EXAMPLE_RESPONSE_CLOSE = "\n```\n\n";

    public static final String LABEL_REQUIRED = "required";
    public static final String LABEL_OPTIONAL = "optional";
    public static final String PLACEHOLDER_PROFILE_ID_VAR = "{profileId}";
    public static final String PLACEHOLDER_PROFILE_ID_SAMPLE = "<profileId>";
    public static final String CURL_CMD_PREFIX = "curl -X ";
    public static final String CURL_URL_OPEN = " \"";
    public static final String CURL_HEADER_AUTH_PREFIX = " \\\n  -H \"";
    public static final String CURL_HEADER_AUTH_SUFFIX = ": <YOUR_MUSE_RELAY_API_KEY>\"";
    public static final String CURL_HEADER_JSON = " \\\n  -H \"Content-Type: application/json\"";
    public static final String CURL_DATA_PREFIX = " \\\n  -d '";
    public static final String LINE_BREAK_INDENT_REGEX = "\\R\\s*";

    public static final String MD_TABLE_CELL_SEP = " | ";
    public static final String MD_TABLE_ROW_PREFIX = "| `";
    public static final String MD_TABLE_CODE_CLOSE_SEP = "` | ";
    public static final String MD_TABLE_CODE_OPEN_SEP = " | `";
    public static final String MD_TABLE_ROW_SUFFIX = " |\n";
    public static final String MD_PIPE = "|";
    public static final String MD_ESCAPED_PIPE = "\\|";
    public static final String MD_BACKTICK = "`";
    public static final String MD_CODE_JOINER = ", ";
    public static final String MD_LINE_END_BACKTICK = "`\n";
    public static final String MD_COLON_NEWLINE = "):\n";

    // =========================================================================
    // 3. 连接器能力与约束摘要文案
    // =========================================================================

    public static final String CONSTRAINT_NONE = "-";
    public static final String CONSTRAINT_SSH_PREFIXES = "allowedCommandPrefixes=";
    public static final String CONSTRAINT_SSH_ALL_ALLOWED = "all commands allowed";
    public static final String CONSTRAINT_SSH_PORT_PREFIX = "port=";
    public static final String CONSTRAINT_SEMICOLON_SPACE = "; ";
    public static final String CONSTRAINT_TG_DEFAULT_CHAT = "defaultChatId=";
    public static final String CONSTRAINT_TG_CHAT_REQUIRED = "chatId required per request";
    public static final String CONSTRAINT_X_OAUTH1A = "read & write (HMAC-SHA1 signed)";
    public static final String CONSTRAINT_X_BEARER = "Bearer / OAuth 2.0 token";
    public static final String CONSTRAINT_X_COOKIE = "web cookie session (auth_token + ct0, /i/api/**)";

    // =========================================================================
    // 4. 反射类型与 JSON 示例常量
    // =========================================================================

    public static final String TYPE_BOOLEAN_PRIMITIVE = "boolean";
    public static final String TYPE_BOOLEAN_WRAPPER = "Boolean";
    public static final String TYPE_INT_PRIMITIVE = "int";
    public static final String TYPE_INTEGER_WRAPPER = "Integer";
    public static final String TYPE_LONG_PRIMITIVE = "long";
    public static final String TYPE_LONG_WRAPPER = "Long";
    public static final String TYPE_MAP_PREFIX = "Map";
    public static final String TYPE_LIST_PREFIX = "List";
    public static final String TYPE_SET_PREFIX = "Set";
    public static final String TYPE_OBJECT = "Object";
    public static final String EMPTY_JSON_OBJECT = "{}";
    public static final String EMPTY_JSON_ARRAY = "[]";
    public static final String JSON_OBJ_START = "{";
    public static final String JSON_ARR_START = "[";
    public static final String PLACEHOLDER_OPEN = "<";
    public static final String PLACEHOLDER_CLOSE = ">";
    public static final String PATH_VAR_OPEN = "{";
    public static final String PATH_VAR_CLOSE = "}";
}
