package com.mcpdev.tooling.executor;

/**
 * Snapshot of executor state for load-test evidence and leak checks. Deliberately a
 * plain record — no Micrometer dependency for one gauge set.
 */
public record ExecutorStats(
        int active,
        int permitsInUse,
        int maxConcurrent,
        long started,
        long completed,
        long timedOut,
        long cancelled,
        long rejectedBusy) {
}
