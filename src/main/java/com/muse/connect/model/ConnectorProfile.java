package com.muse.connect.model;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.StorageConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ConnectorProfile {

    @Pattern(regexp = StorageConstants.CONNECTOR_ID_PATTERN, message = ExceptionMessageConstants.VALIDATION_ID_PATTERN)
    private String id;

    @NotBlank(message = ExceptionMessageConstants.VALIDATION_NAME_REQUIRED)
    @Size(max = 64, message = ExceptionMessageConstants.VALIDATION_NAME_MAX_LENGTH)
    private String name;

    @NotNull(message = ExceptionMessageConstants.VALIDATION_TYPE_REQUIRED)
    private ConnectorType type;

    @NotNull(message = ExceptionMessageConstants.VALIDATION_AUTH_MODE_REQUIRED)
    private AuthMode authMode;

    private boolean enabled = true;

    private ConnectorConfig config = new ConnectorConfig();

    private long updatedAt;

    public ConnectorProfile maskedCopy() {
        ConnectorProfile copy = new ConnectorProfile();
        copy.setId(this.id);
        copy.setName(this.name);
        copy.setType(this.type);
        copy.setAuthMode(this.authMode);
        copy.setEnabled(this.enabled);
        copy.setConfig(this.config != null ? this.config.maskedCopy() : new ConnectorConfig());
        copy.setUpdatedAt(this.updatedAt);
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null ? id.trim() : null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public ConnectorType getType() {
        return type;
    }

    public void setType(ConnectorType type) {
        this.type = type;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ConnectorConfig getConfig() {
        return config;
    }

    public void setConfig(ConnectorConfig config) {
        this.config = config != null ? config : new ConnectorConfig();
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
