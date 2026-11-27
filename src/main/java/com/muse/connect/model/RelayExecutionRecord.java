package com.muse.connect.model;

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
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return TIME_FMT.format(Instant.ofEpochMilli(timestamp));
    }
}
