package com.muse.connect.model;

public record SystemStats(
        long connectorCount,
        long enabledCount,
        long h2DataBytes,
        int activityCount,
        int activityBufferCapacity,
        long jvmUsedMemoryMb,
        long jvmMaxMemoryMb
) {
}
