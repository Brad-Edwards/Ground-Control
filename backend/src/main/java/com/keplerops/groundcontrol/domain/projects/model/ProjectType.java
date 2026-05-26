package com.keplerops.groundcontrol.domain.projects.model;

/**
 * Closed enum of Ground Control project types. See ADR-056.
 *
 * <p>Adding a new type is an ADR + migration decision; the enum is closed at
 * the API boundary. SOFTWARE is the default for clients that omit `type` on
 * create, preserving backward compatibility for pre-ADR-056 callers.
 */
public enum ProjectType {
    SOFTWARE,
    GRC,
    RESEARCH
}
