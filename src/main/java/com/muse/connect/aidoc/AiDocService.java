package com.muse.connect.aidoc;

import com.muse.connect.aidoc.AiEndpointDescriptor.ParamDescriptor;
import com.muse.connect.config.RelayAuthFilter;
import com.muse.connect.constant.AiDocConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.service.ConnectorService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结合注解扫描出的接口端点与当前已启用（且严格脱敏）的连接器配置，
 * 动态生成面向 AI 可读的 Markdown 文档（{@code llms.txt}）与结构化目录。
 */
@Service
public class AiDocService {

    private final AiDocScanner scanner;
    private final ConnectorService connectorService;

    public AiDocService(AiDocScanner scanner, ConnectorService connectorService) {
        this.scanner = scanner;
        this.connectorService = connectorService;
    }

    public String renderMarkdown(String baseUrl, boolean onlyActive) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        List<ConnectorProfile> activeProfiles = getActiveMaskedProfiles();
        Map<String, List<ConnectorProfile>> profilesByType = groupProfilesByType(activeProfiles);
        List<AiEndpointDescriptor> endpoints = filterEndpoints(scanner.getEndpoints(), profilesByType.keySet(), onlyActive);

        StringBuilder md = new StringBuilder(4096);
        md.append(AiDocConstants.MD_TITLE);
        md.append(AiDocConstants.MD_SUBTITLE);

        md.append(AiDocConstants.MD_SECTION_AUTH);
        md.append(AiDocConstants.MD_BASE_URL_PREFIX).append(normalizedBaseUrl).append(AiDocConstants.MD_LINE_END_BACKTICK);
        md.append(AiDocConstants.MD_AUTH_HEADER_PREFIX).append(RelayAuthFilter.HEADER_NAME).append(AiDocConstants.MD_AUTH_HEADER_SUFFIX);
        md.append(AiDocConstants.MD_CONTENT_TYPE_LINE);
        md.append(AiDocConstants.MD_UNIFIED_RESPONSE_LINE);

        md.append(AiDocConstants.MD_SECTION_PROFILES);
        if (activeProfiles.isEmpty()) {
            md.append(AiDocConstants.MD_NO_PROFILES);
        } else {
            md.append(AiDocConstants.MD_PROFILE_TABLE_HEADER);
            md.append(AiDocConstants.MD_PROFILE_TABLE_DIVIDER);
            for (ConnectorProfile profile : activeProfiles) {
                md.append(AiDocConstants.MD_TABLE_ROW_PREFIX).append(escapeMd(profile.getId()))
                        .append(AiDocConstants.MD_TABLE_CODE_CLOSE_SEP)
                        .append(escapeMd(profile.getName())).append(AiDocConstants.MD_TABLE_CELL_SEP)
                        .append(profile.getType().name()).append(AiDocConstants.MD_TABLE_CODE_OPEN_SEP)
                        .append(profile.getAuthMode().name()).append(AiDocConstants.MD_TABLE_CODE_CLOSE_SEP)
                        .append(escapeMd(summarizeConstraints(profile))).append(AiDocConstants.MD_TABLE_ROW_SUFFIX);
            }
            md.append('\n');
        }

        md.append(AiDocConstants.MD_SECTION_ENDPOINTS);
        if (endpoints.isEmpty()) {
            md.append(AiDocConstants.MD_NO_ENDPOINTS);
            return md.toString();
        }

        Map<String, List<AiEndpointDescriptor>> grouped = new LinkedHashMap<>();
        for (AiEndpointDescriptor ep : endpoints) {
            grouped.computeIfAbsent(ep.groupName(), k -> new ArrayList<>()).add(ep);
        }

        for (Map.Entry<String, List<AiEndpointDescriptor>> entry : grouped.entrySet()) {
            List<AiEndpointDescriptor> groupEndpoints = entry.getValue();
            AiEndpointDescriptor first = groupEndpoints.get(0);

            md.append(AiDocConstants.MD_GROUP_HEADING_PREFIX).append(first.groupName()).append('\n');
            if (StringUtils.hasText(first.groupDescription())) {
                md.append(first.groupDescription()).append('\n');
            }

            List<ConnectorProfile> matchedProfiles = StringUtils.hasText(first.connectorType())
                    ? profilesByType.getOrDefault(first.connectorType(), List.of())
                    : List.of();
            if (StringUtils.hasText(first.connectorType())) {
                if (matchedProfiles.isEmpty()) {
                    md.append(AiDocConstants.MD_ACTIVE_PROFILES_NONE_PREFIX)
                            .append(first.connectorType()).append(AiDocConstants.MD_ACTIVE_PROFILES_NONE_SUFFIX);
                } else {
                    String ids = matchedProfiles.stream()
                            .map(p -> AiDocConstants.MD_BACKTICK + p.getId() + AiDocConstants.MD_BACKTICK)
                            .collect(Collectors.joining(AiDocConstants.MD_CODE_JOINER));
                    md.append(AiDocConstants.MD_ACTIVE_PROFILES_PREFIX).append(ids).append('\n');
                }
            }
            md.append('\n');

            String sampleProfileId = matchedProfiles.isEmpty()
                    ? AiDocConstants.PLACEHOLDER_PROFILE_ID_SAMPLE
                    : matchedProfiles.get(0).getId();

            for (AiEndpointDescriptor ep : groupEndpoints) {
                appendEndpoint(md, normalizedBaseUrl, ep, sampleProfileId);
            }
        }

        return md.toString();
    }

    public Map<String, Object> buildCatalog(String baseUrl, boolean onlyActive) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        List<ConnectorProfile> activeProfiles = getActiveMaskedProfiles();
        Map<String, List<ConnectorProfile>> profilesByType = groupProfilesByType(activeProfiles);
        List<AiEndpointDescriptor> endpoints = filterEndpoints(scanner.getEndpoints(), profilesByType.keySet(), onlyActive);

        List<Map<String, Object>> connectors = activeProfiles.stream()
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(AiDocConstants.KEY_PROFILE_ID, p.getId());
                    item.put(AiDocConstants.KEY_NAME, p.getName());
                    item.put(AiDocConstants.KEY_TYPE, p.getType().name());
                    item.put(AiDocConstants.KEY_AUTH_MODE, p.getAuthMode().name());
                    item.put(AiDocConstants.KEY_CONSTRAINTS, summarizeConstraints(p));
                    return item;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(AiDocConstants.KEY_SERVICE, AiDocConstants.SERVICE_NAME);
        result.put(AiDocConstants.KEY_BASE_URL, normalizedBaseUrl);
        result.put(AiDocConstants.KEY_AUTH_HEADER, RelayAuthFilter.HEADER_NAME);
        result.put(AiDocConstants.KEY_ACTIVE_CONNECTORS, connectors);
        result.put(AiDocConstants.KEY_ENDPOINTS, endpoints);
        return result;
    }

    private void appendEndpoint(StringBuilder md, String baseUrl, AiEndpointDescriptor ep, String sampleProfileId) {
        md.append(AiDocConstants.MD_ENDPOINT_HEADING_PREFIX).append(ep.httpMethod()).append(' ').append(ep.path())
                .append(AiDocConstants.MD_ENDPOINT_HEADING_MID)
                .append(ep.summary()).append('\n');

        if (StringUtils.hasText(ep.description())) {
            md.append(ep.description()).append('\n');
        }

        if (!ep.pathParams().isEmpty()) {
            md.append(AiDocConstants.MD_PATH_PARAMS_HEADER);
            for (ParamDescriptor p : ep.pathParams()) {
                appendParamBullet(md, p);
            }
        }

        if (!ep.queryParams().isEmpty()) {
            md.append(AiDocConstants.MD_QUERY_PARAMS_HEADER);
            for (ParamDescriptor p : ep.queryParams()) {
                appendParamBullet(md, p);
            }
        }

        if (StringUtils.hasText(ep.requestBodyType())) {
            md.append(AiDocConstants.MD_JSON_BODY_PREFIX).append(ep.requestBodyType())
                    .append(AiDocConstants.MD_PARAM_COMMA_SPACE)
                    .append(ep.requestBodyRequired() ? AiDocConstants.LABEL_REQUIRED : AiDocConstants.LABEL_OPTIONAL)
                    .append(AiDocConstants.MD_COLON_NEWLINE);
            for (ParamDescriptor f : ep.bodyFields()) {
                appendParamBullet(md, f);
            }
        }

        md.append(AiDocConstants.MD_CODE_BASH_OPEN);
        md.append(buildCurlExample(baseUrl, ep, sampleProfileId)).append('\n');
        md.append(AiDocConstants.MD_CODE_CLOSE);

        if (StringUtils.hasText(ep.exampleResponse())) {
            md.append(AiDocConstants.MD_EXAMPLE_RESPONSE_OPEN)
                    .append(ep.exampleResponse())
                    .append(AiDocConstants.MD_EXAMPLE_RESPONSE_CLOSE);
        }
    }

    private void appendParamBullet(StringBuilder md, ParamDescriptor p) {
        md.append(AiDocConstants.MD_PARAM_BULLET_PREFIX).append(p.name())
                .append(AiDocConstants.MD_PARAM_TYPE_OPEN).append(p.type())
                .append(AiDocConstants.MD_PARAM_COMMA_SPACE)
                .append(p.required() ? AiDocConstants.LABEL_REQUIRED : AiDocConstants.LABEL_OPTIONAL);
        if (StringUtils.hasText(p.defaultValue())) {
            md.append(AiDocConstants.MD_PARAM_DEFAULT_PREFIX).append(p.defaultValue()).append('`');
        }
        md.append(')');
        if (StringUtils.hasText(p.description())) {
            md.append(AiDocConstants.MD_PARAM_DESC_SEP).append(p.description());
        }
        if (StringUtils.hasText(p.example())) {
            md.append(AiDocConstants.MD_PARAM_EXAMPLE_OPEN).append(p.example()).append(AiDocConstants.MD_PARAM_EXAMPLE_CLOSE);
        }
        md.append('\n');
    }

    private String buildCurlExample(String baseUrl, AiEndpointDescriptor ep, String sampleProfileId) {
        String resolvedPath = ep.path().replace(AiDocConstants.PLACEHOLDER_PROFILE_ID_VAR, sampleProfileId);
        for (ParamDescriptor p : ep.pathParams()) {
            if (AiDocConstants.KEY_PROFILE_ID.equals(p.name())) {
                continue;
            }
            String sample = StringUtils.hasText(p.example())
                    ? p.example()
                    : AiDocConstants.PLACEHOLDER_OPEN + p.name() + AiDocConstants.PLACEHOLDER_CLOSE;
            resolvedPath = resolvedPath.replace(
                    AiDocConstants.PATH_VAR_OPEN + p.name() + AiDocConstants.PATH_VAR_CLOSE,
                    sample
            );
        }

        StringBuilder query = new StringBuilder();
        for (ParamDescriptor q : ep.queryParams()) {
            if (!q.required() && !StringUtils.hasText(q.example())) {
                continue;
            }
            String val = StringUtils.hasText(q.example())
                    ? q.example()
                    : (StringUtils.hasText(q.defaultValue())
                    ? q.defaultValue()
                    : AiDocConstants.PLACEHOLDER_OPEN + q.name() + AiDocConstants.PLACEHOLDER_CLOSE);
            query.append(query.isEmpty() ? '?' : '&')
                    .append(q.name())
                    .append('=')
                    .append(URLEncoder.encode(val, StandardCharsets.UTF_8));
        }

        StringBuilder curl = new StringBuilder();
        curl.append(AiDocConstants.CURL_CMD_PREFIX).append(ep.httpMethod())
                .append(AiDocConstants.CURL_URL_OPEN).append(baseUrl).append(resolvedPath).append(query).append('"')
                .append(AiDocConstants.CURL_HEADER_AUTH_PREFIX).append(RelayAuthFilter.HEADER_NAME)
                .append(AiDocConstants.CURL_HEADER_AUTH_SUFFIX);

        if (StringUtils.hasText(ep.exampleBody())
                && !HttpConstants.METHOD_GET.equals(ep.httpMethod())
                && !HttpConstants.METHOD_DELETE.equals(ep.httpMethod())) {
            String compactBody = ep.exampleBody().replaceAll(AiDocConstants.LINE_BREAK_INDENT_REGEX, HttpConstants.SPACE).trim();
            curl.append(AiDocConstants.CURL_HEADER_JSON)
                    .append(AiDocConstants.CURL_DATA_PREFIX).append(compactBody).append('\'');
        }
        return curl.toString();
    }

    private List<ConnectorProfile> getActiveMaskedProfiles() {
        return connectorService.listMasked().stream()
                .filter(ConnectorProfile::isEnabled)
                .toList();
    }

    private Map<String, List<ConnectorProfile>> groupProfilesByType(List<ConnectorProfile> profiles) {
        Map<String, List<ConnectorProfile>> grouped = new LinkedHashMap<>();
        for (ConnectorProfile p : profiles) {
            if (p.getType() != null) {
                grouped.computeIfAbsent(p.getType().name(), k -> new ArrayList<>()).add(p);
            }
        }
        return grouped;
    }

    private List<AiEndpointDescriptor> filterEndpoints(
            List<AiEndpointDescriptor> all,
            Set<String> activeTypes,
            boolean onlyActive
    ) {
        if (!onlyActive) {
            return all;
        }
        return all.stream()
                .filter(ep -> !StringUtils.hasText(ep.connectorType()) || activeTypes.contains(ep.connectorType()))
                .toList();
    }

    private String summarizeConstraints(ConnectorProfile profile) {
        ConnectorConfig cfg = profile.getConfig();
        if (cfg == null) {
            return AiDocConstants.CONSTRAINT_NONE;
        }
        if (profile.getType() == ConnectorType.SSH) {
            String prefixes = StringUtils.hasText(cfg.getAllowedCommandPrefixes())
                    ? AiDocConstants.CONSTRAINT_SSH_PREFIXES + cfg.getAllowedCommandPrefixes()
                    : AiDocConstants.CONSTRAINT_SSH_ALL_ALLOWED;
            return AiDocConstants.CONSTRAINT_SSH_PORT_PREFIX
                    + (cfg.getPort() != null ? cfg.getPort() : 22)
                    + AiDocConstants.CONSTRAINT_SEMICOLON_SPACE + prefixes;
        }
        if (profile.getType() == ConnectorType.TELEGRAM) {
            return StringUtils.hasText(cfg.getDefaultChatId())
                    ? AiDocConstants.CONSTRAINT_TG_DEFAULT_CHAT + cfg.getDefaultChatId()
                    : AiDocConstants.CONSTRAINT_TG_CHAT_REQUIRED;
        }
        if (profile.getType() == ConnectorType.X) {
            return switch (profile.getAuthMode()) {
                case OAUTH1A -> AiDocConstants.CONSTRAINT_X_OAUTH1A;
                case BEARER_TOKEN -> AiDocConstants.CONSTRAINT_X_BEARER;
                case COOKIE_AUTH -> AiDocConstants.CONSTRAINT_X_COOKIE;
                default -> profile.getAuthMode().name();
            };
        }
        return AiDocConstants.CONSTRAINT_NONE;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return HttpConstants.DEFAULT_LOCAL_BASE_URL;
        }
        return baseUrl.trim().replaceAll(HttpConstants.TRAILING_SLASH_REGEX, RouteConstants.EMPTY);
    }

    private String escapeMd(String value) {
        if (value == null) {
            return RouteConstants.EMPTY;
        }
        return value.replace(AiDocConstants.MD_PIPE, AiDocConstants.MD_ESCAPED_PIPE)
                .replace(RouteConstants.CHAR_LF, HttpConstants.SPACE)
                .replace(RouteConstants.CHAR_CR, RouteConstants.EMPTY);
    }
}
