package com.muse.connect.repository;

import com.muse.connect.config.AppProperties;
import com.muse.connect.model.ConnectorType;
import com.muse.connect.model.RelayExecutionRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded JVM ring buffer for recent relay calls so SQLite database never grows with audit logs.
 */
@Component
public class RelayActivityBuffer {

    private final ConcurrentLinkedDeque<RelayExecutionRecord> deque = new ConcurrentLinkedDeque<>();
    private final int maxSize;

    public RelayActivityBuffer(AppProperties properties) {
        this.maxSize = properties.getActivityBufferSize();
    }

    public void record(
            String profileId,
            ConnectorType type,
            String action,
            boolean success,
            int statusCode,
            long durationMs,
            String summary
    ) {
        String safeSummary = summary == null ? "" : (summary.length() > 160 ? summary.substring(0, 157) + "..." : summary);
        deque.addFirst(new RelayExecutionRecord(
                System.currentTimeMillis(),
                profileId,
                type,
                action,
                success,
                statusCode,
                durationMs,
                safeSummary
        ));
        while (deque.size() > maxSize) {
            deque.pollLast();
        }
    }

    public List<RelayExecutionRecord> recent(int limit) {
        List<RelayExecutionRecord> result = new ArrayList<>(Math.min(limit, maxSize));
        int count = 0;
        for (RelayExecutionRecord item : deque) {
            if (count++ >= limit) {
                break;
            }
            result.add(item);
        }
        return result;
    }

    public int size() {
        return deque.size();
    }

    public int capacity() {
        return maxSize;
    }
}
