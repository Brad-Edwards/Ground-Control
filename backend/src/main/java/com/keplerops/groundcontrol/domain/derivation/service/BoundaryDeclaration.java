package com.keplerops.groundcontrol.domain.derivation.service;

import java.util.List;

public record BoundaryDeclaration(
        String key, String name, String description, List<String> pathSelectors, List<String> surfaces) {

    public BoundaryDeclaration {
        pathSelectors = pathSelectors == null ? List.of() : List.copyOf(pathSelectors);
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
    }
}
