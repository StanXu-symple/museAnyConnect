package com.muse.connect.controller;

import com.muse.connect.aidoc.AiApiGroup;
import com.muse.connect.aidoc.AiDocMeta;
import com.muse.connect.aidoc.AiEndpoint;
import com.muse.connect.aidoc.AiField;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.HttpConstants;
import com.muse.connect.constant.RouteConstants;
import com.muse.connect.constant.SecurityConstants;
import com.muse.connect.constant.WebViewConstants;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.model.SystemStats;
import com.muse.connect.repository.RelayActivityBuffer;
import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.ConnectorException;
import com.muse.connect.service.ConnectorService;
import com.muse.connect.service.TelegramRelayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(RouteConstants.API_BASE)
@AiApiGroup(AiDocMeta.GROUP_CONNECTOR_DISCOVERY)
public class ConnectorApiController {

    private final ConnectorService connectorService;
    private final RelayActivityBuffer activityBuffer;
    private final AdminAuthService adminAuthService;
    private final TelegramRelayService telegramRelayService;

    public ConnectorApiController(
            ConnectorService connectorService,
            RelayActivityBuffer activityBuffer,
            AdminAuthService adminAuthService,
            TelegramRelayService telegramRelayService
    ) {
        this.connectorService = connectorService;
        this.activityBuffer = activityBuffer;
        this.adminAuthService = adminAuthService;
        this.telegramRelayService = telegramRelayService;
    }

    /** Telegram 个人账号交互登录发送验证码请求体 */
    public record TelegramAuthStartRequest(
            Integer apiId,
            String apiHash,
            String phone,
            String bridgeUrl,
            String bridgeToken,
            String proxyHost,
            Integer proxyPort
    ) {
    }

    /** Telegram 个人账号交互登录提交验证码或两步验证密码请求体 */
    public record TelegramAuthVerifyRequest(
            String loginId,
            String code,
            String password,
            String bridgeUrl,
            String bridgeToken
    ) {
    }

    @AiEndpoint(AiDocMeta.EP_CONNECTOR_LIST)
    @GetMapping(RouteConstants.API_CONNECTORS)
    public List<ConnectorProfile> list() {
        return connectorService.listMasked();
    }

    @AiEndpoint(AiDocMeta.EP_CONNECTOR_GET)
    @GetMapping(RouteConstants.API_CONNECTOR_BY_ID)
    public ConnectorProfile get(@PathVariable @AiField(AiDocMeta.FIELD_CONNECTOR_ID) String id) {
        return connectorService.getMasked(id);
    }

    @PostMapping(RouteConstants.API_CONNECTORS)
    public ConnectorProfile upsert(@Valid @RequestBody ConnectorProfile profile) {
        return connectorService.upsert(profile);
    }

    @PostMapping(RouteConstants.API_CONNECTOR_TOGGLE)
    public Map<String, Object> toggle(@PathVariable String id) {
        boolean enabled = connectorService.toggleEnabled(id);
        return Map.of(WebViewConstants.KEY_ID, id, WebViewConstants.KEY_ENABLED, enabled);
    }

    @AiEndpoint(AiDocMeta.EP_CONNECTOR_TEST)
    @PostMapping(RouteConstants.API_CONNECTOR_TEST)
    public RelayResponse test(@PathVariable @AiField(AiDocMeta.FIELD_CONNECTOR_ID) String id) {
        return connectorService.testConnection(id);
    }

    @DeleteMapping(RouteConstants.API_CONNECTOR_BY_ID)
    public Map<String, Object> delete(@PathVariable String id) {
        connectorService.delete(id);
        return Map.of(WebViewConstants.KEY_DELETED, true, WebViewConstants.KEY_ID, id);
    }

    @GetMapping(RouteConstants.API_CONNECTOR_EXPORT)
    public ResponseEntity<List<ConnectorProfile>> exportJson() {
        if (!adminAuthService.isAllowRawExport()) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, ExceptionMessageConstants.API_RAW_EXPORT_DISABLED);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, HttpConstants.CONTENT_DISPOSITION_EXPORT_JSON)
                .header(HttpHeaders.CACHE_CONTROL, SecurityConstants.VALUE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(connectorService.exportAllRaw());
    }

    @PostMapping(RouteConstants.API_CONNECTOR_IMPORT)
    public Map<String, Object> importJson(@RequestBody List<ConnectorProfile> profiles) {
        connectorService.importAll(profiles);
        return Map.of(WebViewConstants.KEY_IMPORTED, profiles != null ? profiles.size() : 0);
    }

    /** 发起 Telegram 个人账号验证码登录（由控制台或管理员 API 调用） */
    @PostMapping(RouteConstants.API_TELEGRAM_AUTH_START)
    public Object startTelegramUserAuth(@RequestBody TelegramAuthStartRequest req) {
        return telegramRelayService.startUserAuth(
                req.apiId(),
                req.apiHash(),
                req.phone(),
                req.bridgeUrl(),
                req.bridgeToken(),
                req.proxyHost(),
                req.proxyPort()
        );
    }

    /** 提交 Telegram 登录验证码及可选 2FA 云密码，换取持久化 Session 字符串 */
    @PostMapping(RouteConstants.API_TELEGRAM_AUTH_VERIFY)
    public Object verifyTelegramUserAuth(@RequestBody TelegramAuthVerifyRequest req) {
        return telegramRelayService.verifyUserAuth(
                req.loginId(),
                req.code(),
                req.password(),
                req.bridgeUrl(),
                req.bridgeToken()
        );
    }

    @AiEndpoint(AiDocMeta.EP_SYSTEM_STATS)
    @GetMapping(RouteConstants.API_STATS)
    public Map<String, Object> stats() {
        SystemStats stats = connectorService.getStats();
        return Map.of(
                WebViewConstants.KEY_STATS, stats,
                WebViewConstants.KEY_RECENT_ACTIVITY, activityBuffer.recent(20)
        );
    }
}
