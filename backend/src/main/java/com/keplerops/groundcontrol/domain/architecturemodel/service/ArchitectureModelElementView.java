package com.keplerops.groundcontrol.domain.architecturemodel.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;

public record ArchitectureModelElementView(
        ArchitectureModelElement element, ArchitectureModelElementState currentState) {}
