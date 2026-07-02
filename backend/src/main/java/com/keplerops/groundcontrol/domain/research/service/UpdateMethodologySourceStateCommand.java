package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;

/**
 * GC-RSCH-F006 — update the state of a methodology source.
 */
public record UpdateMethodologySourceStateCommand(MethodologySourceState state) {}
