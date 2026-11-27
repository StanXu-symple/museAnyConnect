package com.muse.connect.model;

public enum AuthMode {
    BEARER_TOKEN(ConnectorType.X, "Bearer / OAuth2 Token"),
    OAUTH1A(ConnectorType.X, "OAuth 1.0a (HMAC-SHA1)"),
    BOT_TOKEN(ConnectorType.TELEGRAM, "Bot API Token"),
    PASSWORD(ConnectorType.SSH, "SSH Password"),
    PRIVATE_KEY(ConnectorType.SSH, "SSH Private Key");

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
