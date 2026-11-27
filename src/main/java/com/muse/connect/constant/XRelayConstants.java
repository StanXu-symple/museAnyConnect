package com.muse.connect.constant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * X (Twitter) API v2、网页 Cookie 协议、GraphQL 操作、特性开关与事务签名常量类。
 */
public final class XRelayConstants {

    private XRelayConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    // =========================================================================
    // 1. 基础地址、内置公共凭据与默认 User-Agent
    // =========================================================================

    /** X 官方 API v2 默认基础地址 */
    public static final String DEFAULT_BASE_URL = "https://api.x.com";
    /** X 网页端默认基础地址 */
    public static final String DEFAULT_WEB_BASE_URL = "https://x.com";
    /** 官方 X 网页客户端内置公共 Bearer Token（配合 auth_token + ct0 使用） */
    public static final String DEFAULT_WEB_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";
    /** 模拟桌面浏览器的默认 User-Agent */
    public static final String DEFAULT_WEB_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** API 域名标识 */
    public static final String HOST_API_X = "api.x.com";
    public static final String HOST_API_TWITTER = "api.twitter.com";

    // =========================================================================
    // 2. X API 与网页内部接口路径
    // =========================================================================

    public static final String PATH_V2_TWEETS = "/2/tweets";
    public static final String PATH_V2_SEARCH_RECENT = "/2/tweets/search/recent";
    public static final String PATH_V2_USER_BY_USERNAME_PREFIX = "/2/users/by/username/";
    public static final String PATH_V2_USERS_ME = "/2/users/me";
    public static final String PATH_V2_PUBLIC_TEST_USER = "/2/users/by/username/X";
    public static final String PATH_WEB_ACCOUNT_SETTINGS = "/i/api/1.1/account/settings.json";
    public static final String PATH_WEB_VERIFY_CREDENTIALS = "/i/api/1.1/account/verify_credentials.json";
    public static final String PATH_WEB_GRAPHQL_PREFIX = "/i/api/graphql/";
    public static final String PATH_BOOTSTRAP_PAGE = "/tesla";
    public static final String URL_MIGRATE = "https://x.com/x/migrate";
    public static final String URL_CLIENT_WEB_PREFIX = "https://abs.twimg.com/responsive-web/client-web/";
    public static final String URL_JS_SUFFIX = ".js";

    // =========================================================================
    // 3. X 网页端请求头、Cookie 与查询参数名
    // =========================================================================

    public static final String HEADER_TRANSACTION_ID = "x-client-transaction-id";
    public static final String HEADER_X_CSRF_TOKEN = "x-csrf-token";
    public static final String HEADER_X_AUTH_TYPE = "x-twitter-auth-type";
    public static final String VALUE_OAUTH2_SESSION = "OAuth2Session";
    public static final String HEADER_X_ACTIVE_USER = "x-twitter-active-user";
    public static final String VALUE_YES = "yes";
    public static final String HEADER_X_CLIENT_LANGUAGE = "x-twitter-client-language";
    public static final String VALUE_LANG_EN = "en";

    public static final String COOKIE_AUTH_TOKEN_PREFIX = "auth_token=";
    public static final String COOKIE_CT0_SEPARATOR = "; ct0=";

    public static final String PARAM_QUERY = "query";
    public static final String PARAM_MAX_RESULTS = "max_results";
    public static final String PARAM_SKIP_STATUS = "skip_status";
    public static final String VALUE_TRUE = "true";
    public static final String PARAM_VARIABLES = "variables";
    public static final String PARAM_FEATURES = "features";
    public static final String PARAM_FIELD_TOGGLES = "fieldToggles";
    public static final String LEADING_AT_REGEX = "^@";
    public static final String QUERY_ID_SPLIT_REGEX = "[,;\\n]";

    // =========================================================================
    // 4. GraphQL 操作名称、默认 Query ID 与请求体字段
    // =========================================================================

    public static final String OP_CREATE_TWEET = "CreateTweet";
    public static final String OP_SEARCH_TIMELINE = "SearchTimeline";
    public static final String OP_USER_BY_SCREEN_NAME = "UserByScreenName";

    public static final String DEFAULT_QID_CREATE_TWEET = "l4hk8uVGvR-a8kzQ4nOhPA";
    public static final String DEFAULT_QID_SEARCH_TIMELINE = "vLHjqbDv7TPFMBkIIO1nOA";
    public static final String DEFAULT_QID_USER_BY_SCREEN_NAME = "AMIBMjtxEEATh4z8V9GtRg";

    public static final Map<String, String> DEFAULT_GRAPHQL_QUERY_IDS = Map.of(
            OP_CREATE_TWEET, DEFAULT_QID_CREATE_TWEET,
            OP_SEARCH_TIMELINE, DEFAULT_QID_SEARCH_TIMELINE,
            OP_USER_BY_SCREEN_NAME, DEFAULT_QID_USER_BY_SCREEN_NAME
    );

    public static final Set<String> TRACKED_GRAPHQL_OPS = Set.of(
            OP_CREATE_TWEET, OP_SEARCH_TIMELINE, OP_USER_BY_SCREEN_NAME
    );

    public static final String FIELD_TEXT = "text";
    public static final String FIELD_REPLY = "reply";
    public static final String FIELD_IN_REPLY_TO_TWEET_ID = "in_reply_to_tweet_id";
    public static final String FIELD_TWEET_TEXT = "tweet_text";
    public static final String FIELD_DARK_REQUEST = "dark_request";
    public static final String FIELD_MEDIA = "media";
    public static final String FIELD_MEDIA_ENTITIES = "media_entities";
    public static final String FIELD_POSSIBLY_SENSITIVE = "possibly_sensitive";
    public static final String FIELD_SEMANTIC_ANNOTATION_IDS = "semantic_annotation_ids";
    public static final String FIELD_EXCLUDE_REPLY_USER_IDS = "exclude_reply_user_ids";
    public static final String FIELD_QUERY_ID = "queryId";
    public static final String FIELD_RAW_QUERY = "rawQuery";
    public static final String FIELD_COUNT = "count";
    public static final String FIELD_QUERY_SOURCE = "querySource";
    public static final String VALUE_TYPED_QUERY = "typed_query";
    public static final String FIELD_PRODUCT = "product";
    public static final String VALUE_LATEST = "Latest";
    public static final String FIELD_ARTICLE_RICH_CONTENT = "withArticleRichContentState";
    public static final String FIELD_SCREEN_NAME = "screen_name";
    public static final String FIELD_SAFETY_MODE_USER_FIELDS = "withSafetyModeUserFields";

    // =========================================================================
    // 5. x-client-transaction-id 签名解析正则与标记常量
    // =========================================================================

    public static final String TRANSACTION_KEYWORD = "obfiowerehiring";
    public static final String META_VERIFICATION_NAME = "twitter-site-verification";
    public static final String DOC_LOCATION_MARKER = ">document.location =";
    public static final String DOC_LOCATION_START = "document.location = \"";
    public static final String DOUBLE_QUOTE = "\"";
    public static final String MIGRATE_FORM_MARKER = "action=\"https://x.com/x/migrate\" method=\"post\"";
    public static final String PATH_SEGMENT_MAIN = "/main.";
    public static final String PATH_SEGMENT_X_WEB = "/x-web/";
    public static final String SCRIPT_NAME_MAIN = "main";
    public static final String SCRIPT_HASH_SUFFIX_A = "a";
    public static final String SVG_CURVE_SPLIT = "C";
    public static final String NON_DIGIT_REGEX = "[^\\d]+";
    public static final String WHITESPACE_REGEX = "\\s+";
    public static final String DOT = ".";
    public static final String ZERO = "0";
    public static final String DOUBLE_ZERO = "00";
    public static final String CACHE_KEY_SEP = "|";
    public static final String PAYLOAD_SEP = "!";

    public static final String REGEX_META_TAG = "<meta\\b[^>]*>";
    public static final String REGEX_NAME_ATTR = "\\bname\\s*=\\s*[\"']([^\"']+)[\"']";
    public static final String REGEX_CONTENT_ATTR = "\\bcontent\\s*=\\s*[\"']([^\"']+)[\"']";
    public static final String REGEX_SVG_ANIM = "<svg\\b[^>]*\\bid\\s*=\\s*[\"']loading-x-anim[^\"']*[\"'][^>]*>(.*?)</svg>";
    public static final String REGEX_FIRST_G = "<g\\b[^>]*>(.*?)</g>";
    public static final String REGEX_PATH_D = "<path\\b[^>]*\\bd\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>";
    public static final String REGEX_ASSET_XWEB_URL = "https?://[\\w.:-]+/x-web/[\\w./-]+\\.js";
    public static final String REGEX_RESPONSIVE_WEB_URL = "https?://[\\w.:-]+/responsive-web/client-web/[\\w./-]+\\.js";
    public static final String REGEX_LEGACY_MAIN = "/client-web/main\\.([^.\"']+)\\.js";
    public static final String REGEX_HASH_MAP = "(\\d+):\"([0-9a-f]{7}|[0-9a-f]{16})\"";
    public static final String REGEX_NAME_MAP = "(\\d+):\"([^\"]+)\"";
    public static final String REGEX_HEX_HASH = "[0-9a-f]{7}|[0-9a-f]{16}";
    public static final String REGEX_LOGGED_OUT_ENTRY = "(?:^|/)entry-client-logged-out(?:[-.][^/?#]+)?\\.js(?:[?#].*)?$";
    public static final String REGEX_INDICES_FILE = "(?:\\.{0,2}/)?[\\w./-]*?\\b(?:ondemand\\.s|sign\\.o)[\\w.-]*\\.js";
    public static final String REGEX_LOCAL_SCRIPT_SRC = "<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"']";
    public static final String REGEX_INDICES = "(\\(\\w\\[(\\d{1,2})],\\s*16\\))+";
    public static final String REGEX_GRAPHQL_OP =
            "queryId:\"([A-Za-z0-9_-]+)\",operationName:\"(CreateTweet|SearchTimeline|UserByScreenName)\"(?:[^}]*?metadata:\\{[^}]*?featureSwitches:\\[([^\\]]*)\\])?";
    public static final String REGEX_QUOTED_STRING = "\"([^\"]+)\"";
    public static final String REGEX_INPUT_TAG = "<input\\b[^>]*>";
    public static final String REGEX_VALUE_ATTR = "\\bvalue\\s*=\\s*[\"']([^\"']*)[\"']";

    // =========================================================================
    // 6. X GraphQL 默认 Feature Switches（特性开关）
    // =========================================================================

    /** 推文相关 GraphQL 请求默认开启的特性开关列表 */
    public static final List<String> DEFAULT_TWEET_TRUE_FEATURES = List.of(
            "c9s_tweet_anatomy_moderator_badge_enabled",
            "communities_web_enable_tweet_community_results_fetch",
            "creator_subscriptions_tweet_preview_api_enabled",
            "freedom_of_speech_not_reach_fetch_enabled",
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
            "longform_notetweets_consumption_enabled",
            "longform_notetweets_inline_media_enabled",
            "longform_notetweets_rich_text_read_enabled",
            "responsive_web_edit_tweet_api_enabled",
            "responsive_web_graphql_exclude_directive_enabled",
            "responsive_web_graphql_timeline_navigation_enabled",
            "responsive_web_grok_show_grok_translated_post",
            "responsive_web_profile_redirect_enabled",
            "responsive_web_twitter_article_tweet_consumption_enabled",
            "rweb_tipjar_consumption_enabled",
            "rweb_video_screen_enabled",
            "rweb_video_timestamps_enabled",
            "standardized_nudges_misinfo",
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
            "tweetypie_unmention_optimization_enabled",
            "view_counts_everywhere_api_enabled"
    );

    /** 推文相关 GraphQL 请求默认关闭的特性开关列表 */
    public static final List<String> DEFAULT_TWEET_FALSE_FEATURES = List.of(
            "articles_preview_enabled",
            "content_disclosure_ai_generated_indicator_enabled",
            "content_disclosure_indicator_enabled",
            "creator_subscriptions_quote_tweet_preview_enabled",
            "post_ctas_fetch_enabled",
            "premium_content_api_read_enabled",
            "profile_label_improvements_pcf_label_in_post_enabled",
            "responsive_web_enhance_cards_enabled",
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
            "responsive_web_grok_analysis_button_from_backend",
            "responsive_web_grok_analyze_button_fetch_trends_enabled",
            "responsive_web_grok_analyze_post_followups_enabled",
            "responsive_web_grok_annotations_enabled",
            "responsive_web_grok_community_note_auto_translation_is_enabled",
            "responsive_web_grok_image_annotation_enabled",
            "responsive_web_grok_imagine_annotation_enabled",
            "responsive_web_grok_share_attachment_enabled",
            "responsive_web_jetfuel_frame",
            "responsive_web_made_on_x_label_enabled",
            "responsive_web_media_download_video_enabled",
            "responsive_web_nested_quote_preview_enabled",
            "rweb_cashtags_composer_attachment_enabled",
            "rweb_cashtags_enabled",
            "rweb_conversational_replies_downvote_enabled",
            "rweb_sports_post_context_enabled",
            "tweet_awards_web_tipping_enabled",
            "tweet_with_visibility_results_prefer_gql_media_interstitial_enabled",
            "verified_phone_label_enabled",
            "x_elections_post_context_enabled"
    );

    /** 用户资料 GraphQL 请求额外开启的特性开关列表 */
    public static final List<String> DEFAULT_USER_EXTRA_TRUE_FEATURES = List.of(
            "highlights_tweets_tab_ui_enabled",
            "hidden_profile_likes_enabled",
            "hidden_profile_subscriptions_enabled",
            "subscriptions_verification_info_verified_since_enabled"
    );

    /** 用户资料 GraphQL 请求额外关闭的特性开关列表 */
    public static final List<String> DEFAULT_USER_EXTRA_FALSE_FEATURES = List.of(
            "subscriptions_verification_info_is_identity_verified_enabled",
            "responsive_web_twitter_article_notes_tab_enabled",
            "subscriptions_feature_can_gift_premium",
            "profile_label_improvements_pcf_label_in_post_enabled"
    );
}
