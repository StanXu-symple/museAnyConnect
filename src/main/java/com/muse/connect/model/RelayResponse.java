package com.muse.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayResponse(
        boolean success,
        int statusCode,
        String profileId,
        ConnectorType connectorType,
        long durationMs,
        Object data,
        String error
) {
    public static RelayResponse ok(String profileId, ConnectorType type, int statusCode, long durationMs, Object data) {
        return new RelayResponse(true, statusCode, profileId, type, durationMs, data, null);
    }

    public static RelayResponse fail(String profileId, ConnectorType type, int statusCode, long durationMs, String error, Object data) {
        return new RelayResponse(false, statusCode, profileId, type, durationMs, data, error);
    }
}
