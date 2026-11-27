package com.muse.connect.model;

import com.muse.connect.constant.ConnectorLabelConstants;

public enum ConnectorType {
    X(ConnectorLabelConstants.TYPE_X),
    TELEGRAM(ConnectorLabelConstants.TYPE_TELEGRAM),
    SSH(ConnectorLabelConstants.TYPE_SSH);

    private final String label;

    ConnectorType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
