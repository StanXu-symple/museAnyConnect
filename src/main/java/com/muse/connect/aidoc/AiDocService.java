package com.muse.connect.aidoc;

import com.muse.connect.aidoc.AiEndpointDescriptor.ParamDescriptor;
import com.muse.connect.config.RelayAuthFilter;
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
 * Generates dynamic, AI-readable Markdown documentation ({@code llms.txt}) and structured catalog
 * by combining annotation-scanned endpoints with live (strictly masked) enabled connector profiles.
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
        md.append("# MuseAnyConnect — AI API Guide (llms.txt)\n\n");
        md.append("> Dynamically generated from controller `@AiApiGroup` / `@AiEndpoint` annotations and currently enabled connector profiles.\n\n");

        md.append("## 1. Connection & Authentication\n\n");
        md.append("- **Base URL**: `").append(normalizedBaseUrl).append("`\n");
        md.append("- **Auth Header**: `").append(RelayAuthFilter.HEADER_NAME).append(": <YOUR_MUSE_RELAY_API_KEY>` (required on all `/api/**` calls)\n");
        md.append("- **Content-Type**: `application/json` for POST/PUT requests\n");
        md.append("- **Unified Relay Response**: `{\"success\": true|false, \"profileId\": \"...\", \"connectorType\": \"X|TELEGRAM|SSH\", \"statusCode\": 200, \"durationMs\": 123, \"data\": ..., \"error\": null}`\n\n");

        md.append("## 2. Active Connector Profiles (Live, Secrets Masked)\n\n");
        if (activeProfiles.isEmpty()) {
            md.append("_No enabled connector profiles configured yet. Create and enable profiles in the web console first._\n\n");
        } else {
            md.append("| profileId | Name | Type | Auth Mode | Capabilities / Constraints |\n");
            md.append("| :--- | :--- | :--- | :--- | :--- |\n");
            for (ConnectorProfile profile : activeProfiles) {
                md.append("| `").append(escapeMd(profile.getId())).append("` | ")
                        .append(escapeMd(profile.getName())).append(" | ")
                        .append(profile.getType().name()).append(" | `")
                        .append(profile.getAuthMode().name()).append("` | ")
                        .append(escapeMd(summarizeConstraints(profile))).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## 3. Available API Endpoints\n\n");
        if (endpoints.isEmpty()) {
            md.append("_No endpoints available for the current filter._\n");
            return md.toString();
        }

        Map<String, List<AiEndpointDescriptor>> grouped = new LinkedHashMap<>();
        for (AiEndpointDescriptor ep : endpoints) {
            grouped.computeIfAbsent(ep.groupName(), k -> new ArrayList<>()).add(ep);
        }

        for (Map.Entry<String, List<AiEndpointDescriptor>> entry : grouped.entrySet()) {
            List<AiEndpointDescriptor> groupEndpoints = entry.getValue();
            AiEndpointDescriptor first = groupEndpoints.get(0);

            md.append("### ").append(first.groupName()).append('\n');
            if (StringUtils.hasText(first.groupDescription())) {
                md.append(first.groupDescription()).append('\n');
            }

            List<ConnectorProfile> matchedProfiles = StringUtils.hasText(first.connectorType())
                    ? profilesByType.getOrDefault(first.connectorType(), List.of())
                    : List.of();
            if (StringUtils.hasText(first.connectorType())) {
                if (matchedProfiles.isEmpty()) {
                    md.append("- **Active `profileId`s**: _None currently enabled for type `")
                            .append(first.connectorType()).append("`_\n");
                } else {
                    String ids = matchedProfiles.stream()
                            .map(p -> "`" + p.getId() + "`")
                            .collect(Collectors.joining(", "));
                    md.append("- **Active `profileId`s**: ").append(ids).append('\n');
                }
            }
            md.append('\n');

            String sampleProfileId = matchedProfiles.isEmpty() ? "<profileId>" : matchedProfiles.get(0).getId();

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
                    item.put("profileId", p.getId());
                    item.put("name", p.getName());
                    item.put("type", p.getType().name());
                    item.put("authMode", p.getAuthMode().name());
                    item.put("constraints", summarizeConstraints(p));
                    return item;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "MuseAnyConnect");
        result.put("baseUrl", normalizedBaseUrl);
        result.put("authHeader", RelayAuthFilter.HEADER_NAME);
        result.put("activeConnectors", connectors);
        result.put("endpoints", endpoints);
        return result;
    }

    private void appendEndpoint(StringBuilder md, String baseUrl, AiEndpointDescriptor ep, String sampleProfileId) {
        md.append("#### `").append(ep.httpMethod()).append(' ').append(ep.path()).append("` — ")
                .append(ep.summary()).append('\n');

        if (StringUtils.hasText(ep.description())) {
            md.append(ep.description()).append('\n');
        }

        if (!ep.pathParams().isEmpty()) {
            md.append("- **Path Params**:\n");
            for (ParamDescriptor p : ep.pathParams()) {
                appendParamBullet(md, p);
            }
        }

        if (!ep.queryParams().isEmpty()) {
            md.append("- **Query Params**:\n");
            for (ParamDescriptor p : ep.queryParams()) {
                appendParamBullet(md, p);
            }
        }

        if (StringUtils.hasText(ep.requestBodyType())) {
            md.append("- **JSON Body** (`").append(ep.requestBodyType()).append("`, ")
                    .append(ep.requestBodyRequired() ? "required" : "optional").append("):\n");
            for (ParamDescriptor f : ep.bodyFields()) {
                appendParamBullet(md, f);
            }
        }

        md.append("\n```bash\n");
        md.append(buildCurlExample(baseUrl, ep, sampleProfileId)).append('\n');
        md.append("```\n\n");

        if (StringUtils.hasText(ep.exampleResponse())) {
            md.append("Example response:\n```json\n")
                    .append(ep.exampleResponse())
                    .append("\n```\n\n");
        }
    }

    private void appendParamBullet(StringBuilder md, ParamDescriptor p) {
        md.append("  - `").append(p.name()).append("` (`").append(p.type()).append("`, ")
                .append(p.required() ? "required" : "optional");
        if (StringUtils.hasText(p.defaultValue())) {
            md.append(", default=`").append(p.defaultValue()).append('`');
        }
        md.append(')');
        if (StringUtils.hasText(p.description())) {
            md.append(": ").append(p.description());
        }
        if (StringUtils.hasText(p.example())) {
            md.append(" (e.g. `").append(p.example()).append("`)");
        }
        md.append('\n');
    }

    private String buildCurlExample(String baseUrl, AiEndpointDescriptor ep, String sampleProfileId) {
        String resolvedPath = ep.path().replace("{profileId}", sampleProfileId);
        for (ParamDescriptor p : ep.pathParams()) {
            if ("profileId".equals(p.name())) {
                continue;
            }
            String sample = StringUtils.hasText(p.example()) ? p.example() : "<" + p.name() + ">";
            resolvedPath = resolvedPath.replace("{" + p.name() + "}", sample);
        }

        StringBuilder query = new StringBuilder();
        for (ParamDescriptor q : ep.queryParams()) {
            if (!q.required() && !StringUtils.hasText(q.example())) {
                continue;
            }
            String val = StringUtils.hasText(q.example())
                    ? q.example()
                    : (StringUtils.hasText(q.defaultValue()) ? q.defaultValue() : "<" + q.name() + ">");
            query.append(query.isEmpty() ? '?' : '&')
                    .append(q.name())
                    .append('=')
                    .append(URLEncoder.encode(val, StandardCharsets.UTF_8));
        }

        StringBuilder curl = new StringBuilder();
        curl.append("curl -X ").append(ep.httpMethod())
                .append(" \"").append(baseUrl).append(resolvedPath).append(query).append("\"")
                .append(" \\\n  -H \"").append(RelayAuthFilter.HEADER_NAME).append(": <YOUR_MUSE_RELAY_API_KEY>\"");

        if (StringUtils.hasText(ep.exampleBody()) && !"GET".equals(ep.httpMethod()) && !"DELETE".equals(ep.httpMethod())) {
            String compactBody = ep.exampleBody().replaceAll("\\R\\s*", " ").trim();
            curl.append(" \\\n  -H \"Content-Type: application/json\"")
                    .append(" \\\n  -d '").append(compactBody).append('\'');
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
            return "-";
        }
        if (profile.getType() == ConnectorType.SSH) {
            String prefixes = StringUtils.hasText(cfg.getAllowedCommandPrefixes())
                    ? "allowedCommandPrefixes=" + cfg.getAllowedCommandPrefixes()
                    : "all commands allowed";
            return "port=" + (cfg.getPort() != null ? cfg.getPort() : 22) + "; " + prefixes;
        }
        if (profile.getType() == ConnectorType.TELEGRAM) {
            return StringUtils.hasText(cfg.getDefaultChatId())
                    ? "defaultChatId=" + cfg.getDefaultChatId()
                    : "chatId required per request";
        }
        if (profile.getType() == ConnectorType.X) {
            return switch (profile.getAuthMode()) {
                case OAUTH1A -> "read & write (HMAC-SHA1 signed)";
                case BEARER_TOKEN -> "Bearer / OAuth 2.0 token";
                case COOKIE_AUTH -> "web cookie session (auth_token + ct0, /i/api/**)";
                default -> profile.getAuthMode().name();
            };
        }
        return "-";
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "http://localhost:8080";
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private String escapeMd(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }
}
