package com.muse.connect.model;

import com.muse.connect.constant.WebViewConstants;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record RelayExecutionRecord(
        long timestamp,
        String profileId,
        ConnectorType connectorType,
        String action,
        boolean success,
        int statusCode,
        long durationMs,
        String summary
) {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern(WebViewConstants.TIME_PATTERN_HMS)
            .withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return TIME_FMT.format(Instant.ofEpochMilli(timestamp));
    }
}
