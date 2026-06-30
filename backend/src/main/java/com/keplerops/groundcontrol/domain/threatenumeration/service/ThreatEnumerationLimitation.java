package com.keplerops.groundcontrol.domain.threatenumeration.service;

import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatEnumerationLimitationReason;

/**
 * A non-fatal advisory surfaced by the enumeration engine when it cannot fully evaluate
 * an element or a pack resolution fails. Limitations appear in
 * {@link ThreatEnumerationResult#limitations()} rather than being silently dropped.
 */
public record ThreatEnumerationLimitation(
        ThreatEnumerationLimitationReason reason, String detail, String elementStableKey) {}
