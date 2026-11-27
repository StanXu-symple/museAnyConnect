package com.muse.connect.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.model.AuthMode;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import com.muse.connect.model.ConnectorType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ConnectorProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ConnectorProfile> rowMapper;

    public ConnectorProfileRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> {
            ConnectorProfile profile = new ConnectorProfile();
            profile.setId(rs.getString("id"));
            profile.setName(rs.getString("name"));
            profile.setType(ConnectorType.valueOf(rs.getString("type")));
            profile.setAuthMode(AuthMode.valueOf(rs.getString("auth_mode")));
            profile.setEnabled(rs.getBoolean("enabled"));
            profile.setUpdatedAt(rs.getLong("updated_at"));
            String json = rs.getString("config_json");
            try {
                profile.setConfig(objectMapper.readValue(json, ConnectorConfig.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize connector config for id=" + profile.getId(), e);
            }
            return profile;
        };
    }

    public List<ConnectorProfile> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, type, auth_mode, enabled, config_json, updated_at FROM connector_profile ORDER BY updated_at DESC",
                rowMapper
        );
    }

    public Optional<ConnectorProfile> findById(String id) {
        List<ConnectorProfile> list = jdbcTemplate.query(
                "SELECT id, name, type, auth_mode, enabled, config_json, updated_at FROM connector_profile WHERE id = ?",
                rowMapper,
                id
        );
        return list.stream().findFirst();
    }

    public ConnectorProfile save(ConnectorProfile profile) {
        String json;
        try {
            json = objectMapper.writeValueAsString(profile.getConfig());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid connector config", e);
        }

        int updated = jdbcTemplate.update(
                "UPDATE connector_profile SET name = ?, type = ?, auth_mode = ?, enabled = ?, config_json = ?, updated_at = ? WHERE id = ?",
                profile.getName(),
                profile.getType().name(),
                profile.getAuthMode().name(),
                profile.isEnabled(),
                json,
                profile.getUpdatedAt(),
                profile.getId()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO connector_profile (id, name, type, auth_mode, enabled, config_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    profile.getId(),
                    profile.getName(),
                    profile.getType().name(),
                    profile.getAuthMode().name(),
                    profile.isEnabled(),
                    json,
                    profile.getUpdatedAt()
            );
        }
        return profile;
    }

    public boolean deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM connector_profile WHERE id = ?", id) > 0;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM connector_profile", Long.class);
        return count != null ? count : 0L;
    }

    public long countEnabled() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM connector_profile WHERE enabled = TRUE", Long.class);
        return count != null ? count : 0L;
    }

    public long queryH2DataBytes() {
        try {
            Long bytes = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(OCTET_LENGTH(id) + OCTET_LENGTH(name) + OCTET_LENGTH(type) + OCTET_LENGTH(auth_mode) + OCTET_LENGTH(config_json) + 9), 0) FROM connector_profile",
                    Long.class
            );
            return bytes != null ? bytes : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }
}
