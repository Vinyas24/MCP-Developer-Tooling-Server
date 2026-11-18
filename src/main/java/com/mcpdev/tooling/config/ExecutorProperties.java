package com.mcpdev.tooling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Async execution limits. Sized for the Phase 6 target: 1,000+ concurrent calls.
 *
 * <ul>
 *   <li>{@code maxConcurrentCalls} — hard ceiling on in-flight + queued calls;
 *       beyond it the server sheds load instead of growing without bound</li>
 *   <li>{@code queueWaitMillis} — how long a call may wait for capacity before it is
 *       rejected with DENIED_BACKPRESSURE</li>
 *   <li>{@code callTimeoutMillis} — one end-to-end deadline per call (queue + run)</li>
 *   <li>{@code statsIntervalSeconds} — executor gauges to stderr; 0 disables</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mcp.executor")
public record ExecutorProperties(
        int maxConcurrentCalls,
        long queueWaitMillis,
        long callTimeoutMillis,
        long statsIntervalSeconds) {

    public ExecutorProperties {
        if (maxConcurrentCalls <= 0) maxConcurrentCalls = 2048;
        if (queueWaitMillis <= 0) queueWaitMillis = 5000;
        if (callTimeoutMillis <= 0) callTimeoutMillis = 30_000;
        if (statsIntervalSeconds < 0) statsIntervalSeconds = 0;
    }
}
