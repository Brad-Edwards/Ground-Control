package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class StubDerivationAdapter implements DerivationAdapter {

    private static final DerivationAdapterDescriptor DESCRIPTOR = new DerivationAdapterDescriptor(
            "stub-static-derivation",
            "stub-deriver",
            "0.1.0",
            "stub-system-model",
            "2026.06",
            Set.of("java"),
            Set.of("application"),
            Set.of(DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET),
            Set.of(SystemModelFactKind.COMPONENT, SystemModelFactKind.ENTRY_POINT));

    @Override
    public DerivationAdapterDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public DerivationAdapterResult derive(DerivationAdapterRequest request) {
        var scope = request.scope();
        var derivedAt = Instant.now();
        var provenance = new DerivationFactProvenance(
                DESCRIPTOR.adapterId(),
                DESCRIPTOR.toolName(),
                DESCRIPTOR.toolVersion(),
                DESCRIPTOR.rulesetName(),
                DESCRIPTOR.rulesetVersion(),
                scope.commitSha(),
                derivedAt);
        var sourcePath = scope.paths().isEmpty() ? null : scope.paths().getFirst();
        return DerivationAdapterResult.facts(List.of(
                new DerivedSystemModelFact(
                        SystemModelFactKind.COMPONENT,
                        "component:" + request.projectIdentifier() + ":java-application",
                        "Java application component",
                        "Stub-derived Java application component for the requested repository scope.",
                        sourcePath,
                        Map.of(
                                "scopeMode", scope.mode().name(),
                                "languages", List.copyOf(scope.languages()),
                                "surfaces", List.copyOf(scope.surfaces())),
                        provenance),
                new DerivedSystemModelFact(
                        SystemModelFactKind.ENTRY_POINT,
                        "entry-point:" + request.projectIdentifier() + ":spring-rest-api",
                        "Spring REST API",
                        "Stub-derived HTTP entry point for Java application analysis.",
                        sourcePath,
                        Map.of(
                                "protocol", "HTTP",
                                "framework", "spring-web",
                                "scopeMode", scope.mode().name()),
                        provenance)));
    }
}
