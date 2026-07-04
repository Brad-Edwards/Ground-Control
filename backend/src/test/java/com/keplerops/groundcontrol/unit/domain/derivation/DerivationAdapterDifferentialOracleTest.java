package com.keplerops.groundcontrol.unit.domain.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.infrastructure.derivation.StubDerivationAdapter;
import com.keplerops.groundcontrol.test.oracle.DifferentialOracle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Tag;

@Tag("slow")
class DerivationAdapterDifferentialOracleTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000001292");

    @Property
    void stubDerivationAdapterMatchesReferenceModel(@ForAll("requests") DerivationAdapterRequest request) {
        var adapter = new StubDerivationAdapter();

        DifferentialOracle.assertEquivalent(
                "DerivationAdapter.stub",
                request,
                this::referenceFacts,
                candidate -> normalize(adapter.derive(candidate).facts()));
    }

    @Provide
    Arbitrary<DerivationAdapterRequest> requests() {
        var projects = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(value -> "gc-" + value);
        var paths = Arbitraries.of(
                List.<String>of(), List.of("backend/src/main/java/App.java"), List.of("frontend/src/pages/graph.tsx"));
        var modes = Arbitraries.of(DerivationScopeMode.values());

        return Combinators.combine(projects, paths, modes)
                .as((project, scopedPaths, mode) -> new DerivationAdapterRequest(
                        PROJECT_ID,
                        project,
                        new DerivationScope(
                                mode,
                                "25c991231cf2a1464792846b083d1bd885299b3c",
                                null,
                                scopedPaths,
                                Set.of("java"),
                                Set.of("application"))));
    }

    private List<ExpectedFact> referenceFacts(DerivationAdapterRequest request) {
        var scope = request.scope();
        var sourcePath = scope.paths().isEmpty() ? null : scope.paths().getFirst();
        return List.of(
                new ExpectedFact(
                        SystemModelFactKind.COMPONENT,
                        "component:" + request.projectIdentifier() + ":java-application",
                        "Java application component",
                        "Stub-derived Java application component for the requested repository scope.",
                        sourcePath,
                        Map.of(
                                "scopeMode", scope.mode().name(),
                                "languages", List.copyOf(scope.languages()),
                                "surfaces", List.copyOf(scope.surfaces()))),
                new ExpectedFact(
                        SystemModelFactKind.ENTRY_POINT,
                        "entry-point:" + request.projectIdentifier() + ":spring-rest-api",
                        "Spring REST API",
                        "Stub-derived HTTP entry point for Java application analysis.",
                        sourcePath,
                        Map.of(
                                "protocol",
                                "HTTP",
                                "framework",
                                "spring-web",
                                "scopeMode",
                                scope.mode().name())));
    }

    private List<ExpectedFact> normalize(
            List<com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact> facts) {
        return facts.stream()
                .map(fact -> new ExpectedFact(
                        fact.factKind(),
                        fact.factKey(),
                        fact.label(),
                        fact.summary(),
                        fact.sourcePath(),
                        fact.payload()))
                .toList();
    }

    record ExpectedFact(
            SystemModelFactKind kind,
            String key,
            String label,
            String summary,
            String sourcePath,
            Map<String, Object> payload) {}
}
