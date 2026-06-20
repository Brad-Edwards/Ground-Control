package com.keplerops.groundcontrol.integration.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.derivation.repository.DerivationCaptureLimitRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.SystemModelFactRepository;
import com.keplerops.groundcontrol.domain.derivation.service.CreateDerivationRunCommand;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.integration.BaseIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class DerivationServiceIntegrationTest extends BaseIntegrationTest {

    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DerivationService derivationService;

    @Autowired
    private SystemModelFactRepository factRepository;

    @Autowired
    private DerivationCaptureLimitRepository captureLimitRepository;

    @Test
    @Transactional
    void stubAdapterPersistsFactsWithProvenanceAndQueryableCaptureLimits() {
        var project = projectRepository.save(
                new Project("derivation-it-" + UUID.randomUUID().toString().substring(0, 8), "Derivation IT"));

        var result = derivationService.run(new CreateDerivationRunCommand(
                project.getId(),
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("backend/src/main/java/com/example/App.java"),
                List.of("java", "terraform"),
                List.of("application", "iac")));

        assertThat(result.run().getFactCount()).isEqualTo(2);
        assertThat(result.run().getCaptureLimitCount()).isEqualTo(3);
        assertThat(result.facts()).extracting("toolName").containsOnly("stub-deriver");
        assertThat(result.facts()).extracting("commitSha").containsOnly(COMMIT);
        assertThat(result.facts())
                .extracting("factKind")
                .containsExactlyInAnyOrder(SystemModelFactKind.COMPONENT, SystemModelFactKind.ENTRY_POINT);
        assertThat(result.captureLimits())
                .extracting("reason")
                .contains(CaptureLimitReason.UNSUPPORTED_SURFACE, CaptureLimitReason.UNSUPPORTED_LANGUAGE);

        var persistedFacts = factRepository.findByProjectIdAndDerivationRunIdOrderByDerivedAtDesc(
                project.getId(), result.run().getId());
        var persistedLimits = captureLimitRepository.findByProjectIdAndDerivationRunIdOrderByCapturedAtDesc(
                project.getId(), result.run().getId());
        assertThat(persistedFacts).hasSize(2);
        assertThat(persistedLimits).hasSize(3);
    }

    @Test
    @Transactional
    void pathSetRejectsTrailingSlash() {
        var project = projectRepository.save(new Project(
                "derivation-path-" + UUID.randomUUID().toString().substring(0, 8), "Derivation Path Validation"));

        assertThatThrownBy(() -> derivationService.run(new CreateDerivationRunCommand(
                        project.getId(),
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("backend/src/main/java/"),
                        List.of("java"),
                        List.of("application"))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("empty or parent-directory segments");
    }
}
