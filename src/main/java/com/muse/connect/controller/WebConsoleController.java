package com.muse.connect.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.aidoc.AiDocService;
import com.muse.connect.config.AppProperties;
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

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("stats", connectorService.getStats());
        model.addAttribute("connectors", connectorService.listMasked());
        model.addAttribute("activities", activityBuffer.recent(25));
        model.addAttribute("apiKeyEnabled", StringUtils.hasText(appProperties.getApiKey()));
        return "dashboard";
    }

    @GetMapping("/connectors/new")
    public String newConnector(
            @RequestParam(defaultValue = "X") ConnectorType type,
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
        return "connector-form";
    }

    @GetMapping("/connectors/{id}/edit")
    public String editConnector(@PathVariable String id, Model model, RedirectAttributes ra) {
        try {
            ConnectorProfile profile = connectorService.getMasked(id);
            populateFormModel(model, profile, true);
            return "connector-form";
        } catch (ConnectorException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
            return "redirect:/";
        }
    }

    @PostMapping("/connectors/save")
    public String saveConnector(
            @ModelAttribute("profile") ConnectorProfile profile,
            @RequestParam(defaultValue = "false") boolean editing,
            Model model,
            RedirectAttributes ra
    ) {
        try {
            if (!StringUtils.hasText(profile.getName())) {
                throw new ConnectorException(org.springframework.http.HttpStatus.BAD_REQUEST, "连接名称不能为空");
            }
            ConnectorProfile saved = connectorService.upsert(profile);
            ra.addFlashAttribute("message", "已保存连接器 [" + saved.getName() + "] (" + saved.getId() + ")");
            return "redirect:/";
        } catch (ConnectorException ex) {
            model.addAttribute("error", ex.getMessage());
            populateFormModel(model, profile, editing);
            return "connector-form";
        }
    }

    @PostMapping("/connectors/{id}/toggle")
    public String toggle(@PathVariable String id, RedirectAttributes ra) {
        try {
            boolean enabled = connectorService.toggleEnabled(id);
            ra.addFlashAttribute("message", "连接器 " + id + (enabled ? " 已启用" : " 已停用"));
        } catch (ConnectorException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/connectors/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        try {
            connectorService.delete(id);
            ra.addFlashAttribute("message", "已删除连接器 " + id);
        } catch (ConnectorException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/connectors/{id}/test")
    public String test(@PathVariable String id, RedirectAttributes ra) {
        try {
            RelayResponse res = connectorService.testConnection(id);
            if (res.success()) {
                ra.addFlashAttribute("message", "连通性测试成功 [" + id + "]，耗时 " + res.durationMs() + "ms (HTTP " + res.statusCode() + ")");
            } else {
                ra.addFlashAttribute("error", "连通性测试失败 [" + id + "]: " + res.error() + " (HTTP " + res.statusCode() + ")");
            }
        } catch (ConnectorException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/connectors/export")
    public ResponseEntity<List<ConnectorProfile>> exportFromConsole() {
        if (!adminAuthService.isAllowRawExport()) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, "当前配置已禁止导出明文凭据 (MUSE_ALLOW_RAW_EXPORT=false)");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=muse-connectors.json")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(connectorService.exportAllRaw());
    }

    @PostMapping("/connectors/import")
    public String importJson(@RequestParam("jsonPayload") String jsonPayload, RedirectAttributes ra) {
        try {
            List<ConnectorProfile> list = objectMapper.readValue(jsonPayload, new TypeReference<>() {});
            connectorService.importAll(list);
            ra.addFlashAttribute("message", "成功导入 " + list.size() + " 条连接配置");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "JSON 导入失败: " + ex.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/playground")
    public String playground(@RequestParam(required = false) String profileId, Model model) {
        List<ConnectorProfile> connectors = connectorService.listMasked();
        model.addAttribute("connectors", connectors);
        model.addAttribute("selectedProfileId", profileId);
        model.addAttribute("apiKeyEnabled", StringUtils.hasText(appProperties.getApiKey()));
        return "playground";
    }

    @GetMapping("/ai-docs")
    public String aiDocs(@RequestParam(defaultValue = "false") boolean onlyActive, Model model) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String llmsUrl = baseUrl + "/api/ai/llms.txt" + (onlyActive ? "?onlyActive=true" : "");
        model.addAttribute("onlyActive", onlyActive);
        model.addAttribute("llmsUrl", llmsUrl);
        model.addAttribute("aiMarkdown", aiDocService.renderMarkdown(baseUrl, onlyActive));
        return "ai-docs";
    }

    private void populateFormModel(Model model, ConnectorProfile profile, boolean editing) {
        model.addAttribute("profile", profile);
        model.addAttribute("editing", editing);
        model.addAttribute("connectorTypes", ConnectorType.values());
        model.addAttribute("authModes", AuthMode.values());
    }

    private AuthMode defaultAuthMode(ConnectorType type) {
        return switch (type) {
            case X -> AuthMode.BEARER_TOKEN;
            case TELEGRAM -> AuthMode.BOT_TOKEN;
            case SSH -> AuthMode.PRIVATE_KEY;
        };
    }
}
