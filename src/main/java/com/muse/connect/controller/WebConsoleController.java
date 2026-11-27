package com.muse.connect.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.aidoc.AiDocService;
import com.muse.connect.config.AppProperties;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
import com.muse.connect.constant.WebViewConstants;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.repository.RelayActivityBuffer;
import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.ConnectorException;
import com.muse.connect.service.ConnectorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Controller
public class WebConsoleController {

    private final ConnectorService connectorService;
    private final RelayActivityBuffer activityBuffer;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final AdminAuthService adminAuthService;
    private final AiDocService aiDocService;

    public WebConsoleController(
            ConnectorService connectorService,
            RelayActivityBuffer activityBuffer,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            AdminAuthService adminAuthService,
            AiDocService aiDocService
    ) {
        this.connectorService = connectorService;
        this.activityBuffer = activityBuffer;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.adminAuthService = adminAuthService;
        this.aiDocService = aiDocService;
    }

    @GetMapping(RouteConstants.ROOT)
    public String dashboard(Model model) {
        model.addAttribute(WebViewConstants.ATTR_STATS, connectorService.getStats());
        model.addAttribute(WebViewConstants.ATTR_CONNECTORS, connectorService.listMasked());
        model.addAttribute(WebViewConstants.ATTR_ACTIVITIES, activityBuffer.recent(25));
        model.addAttribute(WebViewConstants.ATTR_API_KEY_ENABLED, StringUtils.hasText(appProperties.getApiKey()));
        return RouteConstants.VIEW_DASHBOARD;
    }

    @GetMapping(RouteConstants.WEB_CONNECTORS_NEW)
    public String newConnector(
            @RequestParam(defaultValue = RouteConstants.DEFAULT_CONNECTOR_TYPE_X) ConnectorType type,
            Model model
    ) {
        ConnectorProfile profile = new ConnectorProfile();
        profile.setType(type);
        profile.setAuthMode(defaultAuthMode(type));
        profile.setEnabled(true);
        ConnectorConfig cfg = new ConnectorConfig();
        if (type == ConnectorType.SSH) {
            cfg.setPort(22);
        }
        profile.setConfig(cfg);

        populateFormModel(model, profile, false);
        return RouteConstants.VIEW_CONNECTOR_FORM;
    }

    @GetMapping(RouteConstants.WEB_CONNECTORS_EDIT)
    public String editConnector(@PathVariable String id, Model model, RedirectAttributes ra) {
        try {
            ConnectorProfile profile = connectorService.getMasked(id);
            populateFormModel(model, profile, true);
            return RouteConstants.VIEW_CONNECTOR_FORM;
        } catch (ConnectorException ex) {
            ra.addFlashAttribute(WebViewConstants.ATTR_ERROR, ex.getMessage());
            return RouteConstants.REDIRECT_ROOT;
        }
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_SAVE)
    public String saveConnector(
            @ModelAttribute(WebViewConstants.ATTR_PROFILE) ConnectorProfile profile,
            @RequestParam(defaultValue = RouteConstants.DEFAULT_FALSE) boolean editing,
            Model model,
            RedirectAttributes ra
    ) {
        try {
            if (!StringUtils.hasText(profile.getName())) {
                throw new ConnectorException(HttpStatus.BAD_REQUEST, ExceptionMessageConstants.WEB_CONNECTOR_NAME_REQUIRED);
            }
            ConnectorProfile saved = connectorService.upsert(profile);
            ra.addFlashAttribute(
                    WebViewConstants.ATTR_MESSAGE,
                    WebViewConstants.MSG_CONNECTOR_SAVED_FMT.formatted(saved.getName(), saved.getId())
            );
            return RouteConstants.REDIRECT_ROOT;
        } catch (ConnectorException ex) {
            model.addAttribute(WebViewConstants.ATTR_ERROR, ex.getMessage());
            populateFormModel(model, profile, editing);
            return RouteConstants.VIEW_CONNECTOR_FORM;
        }
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_TOGGLE)
    public String toggle(@PathVariable String id, RedirectAttributes ra) {
        try {
            boolean enabled = connectorService.toggleEnabled(id);
            String msg = enabled
                    ? WebViewConstants.MSG_CONNECTOR_ENABLED_FMT.formatted(id)
                    : WebViewConstants.MSG_CONNECTOR_DISABLED_FMT.formatted(id);
            ra.addFlashAttribute(WebViewConstants.ATTR_MESSAGE, msg);
        } catch (ConnectorException ex) {
            ra.addFlashAttribute(WebViewConstants.ATTR_ERROR, ex.getMessage());
        }
        return RouteConstants.REDIRECT_ROOT;
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_DELETE)
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        try {
            connectorService.delete(id);
            ra.addFlashAttribute(WebViewConstants.ATTR_MESSAGE, WebViewConstants.MSG_CONNECTOR_DELETED_FMT.formatted(id));
        } catch (ConnectorException ex) {
            ra.addFlashAttribute(WebViewConstants.ATTR_ERROR, ex.getMessage());
        }
        return RouteConstants.REDIRECT_ROOT;
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_TEST)
    public String test(@PathVariable String id, RedirectAttributes ra) {
        try {
            RelayResponse res = connectorService.testConnection(id);
            if (res.success()) {
                ra.addFlashAttribute(
                        WebViewConstants.ATTR_MESSAGE,
                        WebViewConstants.MSG_TEST_SUCCESS_FMT.formatted(id, res.durationMs(), res.statusCode())
                );
            } else {
                ra.addFlashAttribute(
                        WebViewConstants.ATTR_ERROR,
                        WebViewConstants.MSG_TEST_FAILED_FMT.formatted(id, res.error(), res.statusCode())
                );
            }
        } catch (ConnectorException ex) {
            ra.addFlashAttribute(WebViewConstants.ATTR_ERROR, ex.getMessage());
        }
        return RouteConstants.REDIRECT_ROOT;
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_EXPORT)
    public ResponseEntity<List<ConnectorProfile>> exportFromConsole() {
        if (!adminAuthService.isAllowRawExport()) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, ExceptionMessageConstants.WEB_RAW_EXPORT_DISABLED);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, HttpConstants.CONTENT_DISPOSITION_EXPORT_JSON)
                .header(HttpHeaders.CACHE_CONTROL, SecurityConstants.VALUE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(connectorService.exportAllRaw());
    }

    @PostMapping(RouteConstants.WEB_CONNECTORS_IMPORT)
    public String importJson(@RequestParam(RouteConstants.PARAM_JSON_PAYLOAD) String jsonPayload, RedirectAttributes ra) {
        try {
            List<ConnectorProfile> list = objectMapper.readValue(jsonPayload, new TypeReference<>() {});
            connectorService.importAll(list);
            ra.addFlashAttribute(WebViewConstants.ATTR_MESSAGE, WebViewConstants.MSG_IMPORT_SUCCESS_FMT.formatted(list.size()));
        } catch (Exception ex) {
            ra.addFlashAttribute(WebViewConstants.ATTR_ERROR, ExceptionMessageConstants.WEB_JSON_IMPORT_FAILED_PREFIX + ex.getMessage());
        }
        return RouteConstants.REDIRECT_ROOT;
    }

    @GetMapping(RouteConstants.WEB_PLAYGROUND)
    public String playground(@RequestParam(required = false) String profileId, Model model) {
        List<ConnectorProfile> connectors = connectorService.listMasked();
        model.addAttribute(WebViewConstants.ATTR_CONNECTORS, connectors);
        model.addAttribute(WebViewConstants.ATTR_SELECTED_PROFILE_ID, profileId);
        model.addAttribute(WebViewConstants.ATTR_API_KEY_ENABLED, StringUtils.hasText(appProperties.getApiKey()));
        return RouteConstants.VIEW_PLAYGROUND;
    }

    @GetMapping(RouteConstants.WEB_AI_DOCS)
    public String aiDocs(@RequestParam(defaultValue = RouteConstants.DEFAULT_FALSE) boolean onlyActive, Model model) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String llmsUrl = baseUrl + RouteConstants.API_AI_LLMS_TXT_FULL
                + (onlyActive ? RouteConstants.QUERY_ONLY_ACTIVE_TRUE : RouteConstants.EMPTY);
        model.addAttribute(WebViewConstants.ATTR_ONLY_ACTIVE, onlyActive);
        model.addAttribute(WebViewConstants.ATTR_LLMS_URL, llmsUrl);
        model.addAttribute(WebViewConstants.ATTR_AI_MARKDOWN, aiDocService.renderMarkdown(baseUrl, onlyActive));
        return RouteConstants.VIEW_AI_DOCS;
    }

    private void populateFormModel(Model model, ConnectorProfile profile, boolean editing) {
        model.addAttribute(WebViewConstants.ATTR_PROFILE, profile);
        model.addAttribute(WebViewConstants.ATTR_EDITING, editing);
        model.addAttribute(WebViewConstants.ATTR_CONNECTOR_TYPES, ConnectorType.values());
        model.addAttribute(WebViewConstants.ATTR_AUTH_MODES, AuthMode.values());
    }

    private AuthMode defaultAuthMode(ConnectorType type) {
        return switch (type) {
            case X -> AuthMode.BEARER_TOKEN;
            case TELEGRAM -> AuthMode.BOT_TOKEN;
            case SSH -> AuthMode.PRIVATE_KEY;
        };
    }
}
