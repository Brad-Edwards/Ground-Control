package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-N006 / ADR-086 §2 — bounded privacy classification for research
 * material. The class is the enforcement input the egress policy checks before
 * any external service can see the material; unpublished papers, private
 * libraries, reviewer notes, and proprietary PDFs classify {@code CONFIDENTIAL}
 * or higher, and credentials/secrets classify {@code RESTRICTED}. Declaration
 * order is a monotonically increasing sensitivity axis (PUBLIC least sensitive).
 */
public enum ResearchDataClass {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
