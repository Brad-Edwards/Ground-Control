package com.keplerops.groundcontrol.infrastructure.age;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for AGE graph snapshot publication (ADR-062). New publication knobs are bound
 * here through validated {@link ConfigurationProperties} rather than ad hoc {@code @Value}
 * fields, and the record is registered via the application's {@code @ConfigurationPropertiesScan}.
 *
 * @param retainedSnapshots total number of snapshots kept after a publication — the active
 *     snapshot plus the most-recent superseded ones. Must be at least 2 so the snapshot that was
 *     active immediately before a swap survives cleanup long enough for any in-flight reader that
 *     already resolved it; only snapshots older than the retention window are dropped.
 * @param minRetainedAgeSeconds grace period: a superseded snapshot is dropped only once it is BOTH
 *     beyond {@code retainedSnapshots} AND was retired (stopped being the active snapshot) more
 *     than this many seconds ago. The count bound alone is not enough — a reader can resolve a
 *     snapshot and then two rapid publications can push it beyond the count and drop it mid-read
 *     (repeatable-read protects the metadata lookup, not the AGE graph object's lifetime). The
 *     grace is measured from retirement, not publication, because a snapshot can be active for a
 *     long time and then be superseded while a reader is using it. The grace must exceed the
 *     maximum read duration, which is itself bounded by the graph traversal and projection-size
 *     caps. Default 300s is far larger than any bounded read, so a snapshot a live reader resolved
 *     is never dropped before that reader finishes.
 */
@Validated
@ConfigurationProperties(prefix = "groundcontrol.age.publication")
public record GraphPublicationProperties(
        @DefaultValue("2") @Min(2) int retainedSnapshots, @DefaultValue("300") @Min(0) long minRetainedAgeSeconds) {}
