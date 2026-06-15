package com.keplerops.groundcontrol.api.requirements;

/** API representation of a single field-level change, optionally truncated. */
public record FieldChangeResponse(Object oldValue, Object newValue, boolean truncated) {}
