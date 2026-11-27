package com.muse.connect.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.StorageConstants;
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
            profile.setId(rs.getString(StorageConstants.COL_ID));
            profile.setName(rs.getString(StorageConstants.COL_NAME));
            profile.setType(ConnectorType.valueOf(rs.getString(StorageConstants.COL_TYPE)));
            profile.setAuthMode(AuthMode.valueOf(rs.getString(StorageConstants.COL_AUTH_MODE)));
            profile.setEnabled(rs.getBoolean(StorageConstants.COL_ENABLED));
            profile.setUpdatedAt(rs.getLong(StorageConstants.COL_UPDATED_AT));
            String json = rs.getString(StorageConstants.COL_CONFIG_JSON);
            try {
                profile.setConfig(objectMapper.readValue(json, ConnectorConfig.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        ExceptionMessageConstants.REPO_DESERIALIZE_FAILED_PREFIX + profile.getId(), e);
            }
            return profile;
        };
    }

    public List<ConnectorProfile> findAll() {
        return jdbcTemplate.query(StorageConstants.SQL_FIND_ALL, rowMapper);
    }

    public Optional<ConnectorProfile> findById(String id) {
        List<ConnectorProfile> list = jdbcTemplate.query(StorageConstants.SQL_FIND_BY_ID, rowMapper, id);
        return list.stream().findFirst();
    }

    public ConnectorProfile save(ConnectorProfile profile) {
        String json;
        try {
            json = objectMapper.writeValueAsString(profile.getConfig());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(ExceptionMessageConstants.REPO_INVALID_CONFIG, e);
        }

        int updated = jdbcTemplate.update(
                StorageConstants.SQL_UPDATE,
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
                    StorageConstants.SQL_INSERT,
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
        return jdbcTemplate.update(StorageConstants.SQL_DELETE_BY_ID, id) > 0;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject(StorageConstants.SQL_COUNT, Long.class);
        return count != null ? count : 0L;
    }

    public long countEnabled() {
        Long count = jdbcTemplate.queryForObject(StorageConstants.SQL_COUNT_ENABLED, Long.class);
        return count != null ? count : 0L;
    }

    public long queryDbDataBytes() {
        try {
            Long bytes = jdbcTemplate.queryForObject(StorageConstants.SQL_QUERY_DB_DATA_BYTES, Long.class);
            return bytes != null ? bytes : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }
}
