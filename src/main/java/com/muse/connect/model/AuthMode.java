package com.muse.connect.model;

import com.muse.connect.constant.ConnectorLabelConstants;

public enum AuthMode {
    BEARER_TOKEN(ConnectorType.X, ConnectorLabelConstants.AUTH_BEARER_TOKEN),
    OAUTH1A(ConnectorType.X, ConnectorLabelConstants.AUTH_OAUTH1A),
    COOKIE_AUTH(ConnectorType.X, ConnectorLabelConstants.AUTH_COOKIE_AUTH),
    BOT_TOKEN(ConnectorType.TELEGRAM, ConnectorLabelConstants.AUTH_BOT_TOKEN),
    USER_SESSION(ConnectorType.TELEGRAM, ConnectorLabelConstants.AUTH_USER_SESSION),
    PASSWORD(ConnectorType.SSH, ConnectorLabelConstants.AUTH_PASSWORD),
    PRIVATE_KEY(ConnectorType.SSH, ConnectorLabelConstants.AUTH_PRIVATE_KEY);

    private final ConnectorType connectorType;
    private final String label;

    AuthMode(ConnectorType connectorType, String label) {
        this.connectorType = connectorType;
        this.label = label;
    }

    public ConnectorType getConnectorType() {
        return connectorType;
    }

    public String getLabel() {
        return label;
    }

    public boolean supports(ConnectorType type) {
        return this.connectorType == type;
    }
}
