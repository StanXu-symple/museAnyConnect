package com.muse.connect.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ConnectorProfile {

    @Pattern(regexp = "^[a-zA-Z0-9_-]{0,64}$", message = "ID only allows letters, digits, '-' and '_'")
    private String id;

    @NotBlank(message = "Name is required")
    @Size(max = 64, message = "Name must be <= 64 characters")
    private String name;

    @NotNull(message = "Connector type is required")
    private ConnectorType type;

    @NotNull(message = "Auth mode is required")
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
