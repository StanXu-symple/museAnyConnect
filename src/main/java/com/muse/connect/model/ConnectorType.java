package com.muse.connect.model;

public enum ConnectorType {
    X("X (Twitter)"),
    TELEGRAM("Telegram"),
    SSH("SSH Server");

    private final String label;

    ConnectorType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
