package com.keplerops.groundcontrol.infrastructure.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groundcontrol.temporal.worker", ignoreUnknownFields = false)
public record TemporalWorkerProperties(boolean enabled, String target, String namespace, String taskQueue) {

    public TemporalWorkerProperties {
        target = defaultIfBlank(target, "localhost:7233");
        namespace = defaultIfBlank(namespace, "ground-control");
        taskQueue = defaultIfBlank(taskQueue, "ground-control-implement");
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
