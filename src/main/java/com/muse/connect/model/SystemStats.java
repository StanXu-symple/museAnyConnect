package com.muse.connect.model;

public record SystemStats(
        long connectorCount,
        long enabledCount,
        long dbDataBytes,
        int activityCount,
        int activityBufferCapacity,
        long jvmUsedMemoryMb,
        long jvmMaxMemoryMb
) {
}
