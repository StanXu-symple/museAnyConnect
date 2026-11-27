package com.muse.connect.controller;

import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.RelayResponse;
import com.muse.connect.model.SystemStats;
import com.muse.connect.repository.RelayActivityBuffer;
import com.muse.connect.service.AdminAuthService;
import com.muse.connect.service.ConnectorException;
import com.muse.connect.service.ConnectorService;
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
@RequestMapping("/api")
public class ConnectorApiController {

    private final ConnectorService connectorService;
    private final RelayActivityBuffer activityBuffer;
    private final AdminAuthService adminAuthService;

    public ConnectorApiController(
            ConnectorService connectorService,
            RelayActivityBuffer activityBuffer,
            AdminAuthService adminAuthService
    ) {
        this.connectorService = connectorService;
        this.activityBuffer = activityBuffer;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping("/connectors")
    public List<ConnectorProfile> list() {
        return connectorService.listMasked();
    }

    @GetMapping("/connectors/{id}")
    public ConnectorProfile get(@PathVariable String id) {
        return connectorService.getMasked(id);
    }

    @PostMapping("/connectors")
    public ConnectorProfile upsert(@Valid @RequestBody ConnectorProfile profile) {
        return connectorService.upsert(profile);
    }

    @PostMapping("/connectors/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable String id) {
        boolean enabled = connectorService.toggleEnabled(id);
        return Map.of("id", id, "enabled", enabled);
    }

    @PostMapping("/connectors/{id}/test")
    public RelayResponse test(@PathVariable String id) {
        return connectorService.testConnection(id);
    }

    @DeleteMapping("/connectors/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        connectorService.delete(id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/connectors/export")
    public ResponseEntity<List<ConnectorProfile>> exportJson() {
        if (!adminAuthService.isAllowRawExport()) {
            throw new ConnectorException(HttpStatus.FORBIDDEN, "Raw credential export is disabled (MUSE_ALLOW_RAW_EXPORT=false)");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=muse-connectors.json")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(connectorService.exportAllRaw());
    }

    @PostMapping("/connectors/import")
    public Map<String, Object> importJson(@RequestBody List<ConnectorProfile> profiles) {
        connectorService.importAll(profiles);
        return Map.of("imported", profiles != null ? profiles.size() : 0);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        SystemStats stats = connectorService.getStats();
        return Map.of(
                "stats", stats,
                "recentActivity", activityBuffer.recent(20)
        );
    }
}
