package com.keplerops.groundcontrol.domain.compliance.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Synchronous event published by the GC-I004 evidence-expiry sweep job once
 * per artifact, the first time the sweep observes
 * {@code artifact.expiresAt <= now}.
 *
 * <p>Per the ReassessmentSignalService contract referenced in the cluster
 * scope, the matched listener uses {@code @EventListener}, not
 * {@code @TransactionalEventListener}: the listener failing rolls back the
 * publishing transaction so the event cannot silently disappear. The sweep
 * job wraps each per-artifact dispatch in its own {@code REQUIRES_NEW}
 * {@code TransactionTemplate} execution so a per-artifact listener failure
 * only rolls back that single artifact's drift-event write — the rest of
 * the sweep batch continues to dispatch.
 */
public record EvidenceExpiryEvent(UUID projectId, UUID evidenceArtifactId, String uid, Instant expiresAt) {}
