package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.MethodologyProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CrosswalkVocabularySurface;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NormalizedConcept;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MethodologyProfileServiceTest {

    @Mock
    private MethodologyProfileRepository repository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private MethodologyProfileService service;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void createPersistsConfiguredProfile() {
        var command = new CreateMethodologyProfileCommand(
                projectId,
                "FAIR_V3_0",
                "FAIR",
                "3.0",
                MethodologyFamily.FAIR,
                "Quantitative method",
                Map.of("type", "object"),
                Map.of("result", "object"),
                MethodologyProfileStatus.DEPRECATED,
                Map.of("RESIDUAL_TRANSFER", Map.of()),
                null);
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "FAIR_V3_0", "3.0"))
                .thenReturn(false);
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(command);

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getProfileKey()).isEqualTo("FAIR_V3_0");
        assertThat(result.getDescription()).isEqualTo("Quantitative method");
        assertThat(result.getStatus()).isEqualTo(MethodologyProfileStatus.DEPRECATED);
        assertThat(result.getTreatmentStrategyVocabulary()).containsKey("RESIDUAL_TRANSFER");
    }

    @Test
    void createRejectsDuplicateProfileVersion() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "FAIR_V3_0", "3.0"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateMethodologyProfileCommand(
                        projectId,
                        "FAIR_V3_0",
                        "FAIR",
                        "3.0",
                        MethodologyFamily.FAIR,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listByProjectSeedsDefaultsBeforeReading() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        var result = service.listByProject(projectId);

        assertThat(result).isEmpty();
        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        assertThat(savedCaptor.getAllValues())
                .extracting(MethodologyProfile::getProfileKey)
                .containsExactlyInAnyOrder("LEGACY_QUALITATIVE_V1", "FAIR_V3_0", "NIST_SP800_30_R1", "ISO_27005_V2022");
        assertThat(savedCaptor.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getProject()).isSameAs(project));
        verify(repository).findByProjectIdOrderByNameAscVersionDesc(projectId);
    }

    @Test
    void seededNistProfileExposesFullRev1Decomposition() {
        // GC-T014: the seeded NIST profile must expose the threat-source / threat-event /
        // vulnerability / predisposing-condition / multi-likelihood / impact / timeframe
        // vocabulary, not just generic likelihood × impact.
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        var nist = savedCaptor.getAllValues().stream()
                .filter(p -> "NIST_SP800_30_R1".equals(p.getProfileKey()))
                .findFirst()
                .orElseThrow();
        assertThat(nist.getInputSchema()).containsKeys("properties", "semantics");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) nist.getInputSchema().get("properties");
        assertThat(properties)
                .containsKeys(
                        "threat_source",
                        "threat_event",
                        "threat_event_kind",
                        "threat_source_relevance",
                        "vulnerabilities",
                        "predisposing_conditions",
                        "likelihood_initiation",
                        "likelihood_adverse_impact",
                        "likelihood_overall",
                        "impact_level",
                        "assessment_timeframe");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputProps =
                (Map<String, Object>) nist.getOutputSchema().get("properties");
        assertThat(outputProps)
                .containsKeys("overall_likelihood", "impact_level", "risk_level", "matrix_cell", "derivation");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputSemantics =
                (Map<String, Object>) nist.getOutputSchema().get("semantics");
        assertThat(outputSemantics).containsEntry("derivation_method", "nist-sp800-30-rev1-5x5-matrix-v1");
    }

    @Test
    void getByIdThrowsWhenProfileIsMissing() {
        var profileId = UUID.randomUUID();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, profileId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateMutatesAllMutableFields() {
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var updated = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(
                        "Updated FAIR",
                        "3.1",
                        MethodologyFamily.CUSTOM,
                        "Updated description",
                        Map.of("factor", "schema"),
                        Map.of("output", "schema"),
                        MethodologyProfileStatus.DEPRECATED,
                        Map.of("RESIDUAL_TRANSFER", Map.of()),
                        null));

        assertThat(updated.getName()).isEqualTo("Updated FAIR");
        assertThat(updated.getVersion()).isEqualTo("3.1");
        assertThat(updated.getFamily()).isEqualTo(MethodologyFamily.CUSTOM);
        assertThat(updated.getInputSchema()).containsEntry("factor", "schema");
        assertThat(updated.getStatus()).isEqualTo(MethodologyProfileStatus.DEPRECATED);
        assertThat(updated.getTreatmentStrategyVocabulary()).containsKey("RESIDUAL_TRANSFER");
    }

    @Test
    void deleteRemovesResolvedProfile() {
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        service.delete(projectId, profileId);

        verify(repository).delete(profile);
    }

    // -------------------------------------------------------------------------
    // GC-T012 crosswalk entries — seeded profiles
    // -------------------------------------------------------------------------

    @Test
    void seededFairProfileHasCrosswalkEntries() {
        // C1 + C2 + C3: seeded FAIR profile carries well-formed crosswalk entries
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        var fair = savedCaptor.getAllValues().stream()
                .filter(p -> "FAIR_V3_0".equals(p.getProfileKey()))
                .findFirst()
                .orElseThrow();
        assertThat(fair.getCrosswalkEntries()).isNotNull().isNotEmpty();
        var concepts = fair.getCrosswalkEntries().stream()
                .map(CrosswalkEntry::normalizedConcept)
                .toList();
        assertThat(concepts)
                .contains(
                        NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                        NormalizedConcept.PRIMARY_LOSS_MAGNITUDE,
                        NormalizedConcept.SECONDARY_LOSS_MAGNITUDE,
                        NormalizedConcept.THREAT_EVENT,
                        NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                        NormalizedConcept.CONTROL);
        // All entries must have INPUT_SCHEMA or OUTPUT_SCHEMA surface
        assertThat(fair.getCrosswalkEntries()).allSatisfy(e -> assertThat(e.vocabularySurface())
                .isIn(CrosswalkVocabularySurface.INPUT_SCHEMA, CrosswalkVocabularySurface.OUTPUT_SCHEMA));
        // All entries must have a non-blank source field path
        assertThat(fair.getCrosswalkEntries())
                .allSatisfy(e -> assertThat(e.sourceFieldPath()).isNotBlank());
    }

    @Test
    void seededNistProfileHasCrosswalkEntries() {
        // C1 + C2 + C3: seeded NIST profile carries well-formed crosswalk entries
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        var nist = savedCaptor.getAllValues().stream()
                .filter(p -> "NIST_SP800_30_R1".equals(p.getProfileKey()))
                .findFirst()
                .orElseThrow();
        assertThat(nist.getCrosswalkEntries()).isNotNull().isNotEmpty();
        var concepts = nist.getCrosswalkEntries().stream()
                .map(CrosswalkEntry::normalizedConcept)
                .toList();
        assertThat(concepts)
                .contains(
                        NormalizedConcept.THREAT_SOURCE,
                        NormalizedConcept.THREAT_EVENT,
                        NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                        NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                        NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE);
    }

    @Test
    void seededIso27005ProfileHasCrosswalkEntries() {
        // C1 + C2 + C3: seeded ISO profile carries well-formed crosswalk entries
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        var iso = savedCaptor.getAllValues().stream()
                .filter(p -> "ISO_27005_V2022".equals(p.getProfileKey()))
                .findFirst()
                .orElseThrow();
        assertThat(iso.getCrosswalkEntries()).isNotNull().isNotEmpty();
        var concepts = iso.getCrosswalkEntries().stream()
                .map(CrosswalkEntry::normalizedConcept)
                .toList();
        assertThat(concepts)
                .contains(
                        NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                        NormalizedConcept.CONSEQUENCE_OR_EFFECT,
                        NormalizedConcept.ASSET,
                        NormalizedConcept.CONTROL);
    }

    @Test
    void seededLegacyProfileHasNoCrosswalkEntries() {
        // LEGACY_QUALITATIVE_V1 gets no seed entries (additionalProperties: true)
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        var legacy = savedCaptor.getAllValues().stream()
                .filter(p -> "LEGACY_QUALITATIVE_V1".equals(p.getProfileKey()))
                .findFirst()
                .orElseThrow();
        assertThat(legacy.getCrosswalkEntries()).isNullOrEmpty();
    }

    // -------------------------------------------------------------------------
    // GC-T012 crosswalk entries — validation
    // -------------------------------------------------------------------------

    private MethodologyProfile makeFairProfile() {
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        return profile;
    }

    private MethodologyProfile makeProfileWithInputSchema(Map<String, Object> inputSchema) {
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setInputSchema(inputSchema);
        return profile;
    }

    @Test
    void updateRejectsDuplicateCrosswalkTuple() {
        // Duplicate (normalizedConcept, vocabularySurface, sourceFieldPath) in the same profile
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = makeFairProfile();
        var inputSchema =
                Map.<String, Object>of("properties", Map.<String, Object>of("threat_source", Map.<String, Object>of()));
        profile.setInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command = new UpdateMethodologyProfileCommand(
                null, null, null, null, null, null, null, null, List.of(entry, entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void updateRejectsInputSchemaSurfaceWhenNoInputSchema() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = makeProfileWithInputSchema(null);
        profile.setInputSchema(null);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void updateRejectsOutputSchemaSurfaceWhenNoOutputSchema() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                CrosswalkVocabularySurface.OUTPUT_SCHEMA,
                "risk_level",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setOutputSchema(null);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void updateRejectsTreatmentSurfaceWhenNoTreatmentVocabulary() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.TREATMENT,
                CrosswalkVocabularySurface.TREATMENT_STRATEGY_VOCABULARY,
                "strategy",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setTreatmentStrategyVocabulary(null);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void updateRejectsTreatmentSurfacePathNotInVocabulary() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.TREATMENT,
                CrosswalkVocabularySurface.TREATMENT_STRATEGY_VOCABULARY,
                "nonexistent_strategy",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setTreatmentStrategyVocabulary(Map.of("MITIGATE", Map.of("label", "Mitigate")));
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("nonexistent_strategy");
    }

    @Test
    void updateAcceptsTreatmentSurfacePathInVocabulary() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.TREATMENT,
                CrosswalkVocabularySurface.TREATMENT_STRATEGY_VOCABULARY,
                "MITIGATE",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setTreatmentStrategyVocabulary(Map.of("MITIGATE", Map.of("label", "Mitigate")));
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
        assertThat(result.getCrosswalkEntries().get(0).sourceFieldPath()).isEqualTo("MITIGATE");
    }

    @Test
    void updateRejectsConversionRuleWithoutScaleOrUnits() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "loss_event_frequency",
                null,
                null,
                null, // scale null
                null, // units null
                "LEF = TEF × Vulnerability", // conversionRule non-null
                null);
        var profile = makeProfileWithInputSchema(Map.of("properties", Map.of("loss_event_frequency", Map.of())));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("scale");
    }

    @Test
    void updateRejectsUnknownFieldPath() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "nonexistent_field",
                null,
                null,
                null,
                null,
                null,
                null);
        // Profile has input_schema with known properties but NOT "nonexistent_field"
        var profile = makeProfileWithInputSchema(
                Map.of("type", "object", "properties", Map.of("threat_source", Map.of("type", "string"))));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var command =
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry));
        assertThatThrownBy(() -> service.update(projectId, profileId, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("nonexistent_field");
    }

    @Test
    void updateNullCrosswalkEntriesIsNoOp() {
        // null = no change; existing crosswalk is preserved
        var existing = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = makeFairProfile();
        profile.setCrosswalkEntries(List.of(existing));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var updated = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(
                        null, null, null, null, null, null, null, null, null)); // null = no change

        assertThat(updated.getCrosswalkEntries()).containsExactly(existing);
    }

    @Test
    void updateEmptyCrosswalkEntriesClearsTheList() {
        // empty list = clear
        var existing = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = makeFairProfile();
        profile.setCrosswalkEntries(List.of(existing));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var updated = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(
                        null, null, null, null, null, null, null, null, List.of())); // empty = clear

        assertThat(updated.getCrosswalkEntries()).isEmpty();
    }

    @Test
    void updateReplacesCrosswalkEntriesWithNewList() {
        var existing = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                null,
                null,
                null,
                null,
                null,
                null);
        var replacement = new CrosswalkEntry(
                NormalizedConcept.THREAT_EVENT,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_event",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of(
                "type",
                "object",
                "properties",
                Map.<String, Object>of(
                        "threat_source", Map.<String, Object>of(),
                        "threat_event", Map.<String, Object>of()));
        var profile = makeProfileWithInputSchema(inputSchema);
        profile.setCrosswalkEntries(List.of(existing));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var updated = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(
                        null, null, null, null, null, null, null, null, List.of(replacement)));

        assertThat(updated.getCrosswalkEntries()).containsExactly(replacement);
    }

    @Test
    void createPersistsCrosswalkEntries() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                "Threat Source",
                null,
                null,
                null,
                null,
                "5-level ordinal, no continuous frequency");
        var inputSchema = Map.<String, Object>of(
                "type", "object", "properties", Map.<String, Object>of("threat_source", Map.<String, Object>of()));
        var command = new CreateMethodologyProfileCommand(
                projectId,
                "NIST_SP800_30_R1",
                "NIST",
                "1",
                MethodologyFamily.NIST_SP800_30_R1,
                "desc",
                inputSchema,
                null,
                MethodologyProfileStatus.ACTIVE,
                null,
                List.of(entry));
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "NIST_SP800_30_R1", "1"))
                .thenReturn(false);
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(command);

        assertThat(result.getCrosswalkEntries()).containsExactly(entry);
    }

    @Test
    void updateAcceptsValidOutputSchemaPath() {
        var entry = new CrosswalkEntry(
                NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                CrosswalkVocabularySurface.OUTPUT_SCHEMA,
                "risk_level",
                null,
                null,
                null,
                null,
                null,
                null);
        var outputSchema = Map.<String, Object>of(
                "type", "object", "properties", Map.<String, Object>of("risk_level", Map.<String, Object>of()));
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setOutputSchema(outputSchema);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateAcceptsMultiSegmentNestedPropertiesPath() {
        // FAIR-CAM-style fair_cam.control_strength: descend nested properties
        var entry = new CrosswalkEntry(
                NormalizedConcept.CONTROL,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "fair_cam.control_strength",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of(
                "type",
                "object",
                "properties",
                Map.<String, Object>of(
                        "fair_cam",
                        Map.<String, Object>of(
                                "type",
                                "object",
                                "properties",
                                Map.<String, Object>of("control_strength", Map.<String, Object>of()))));
        var profile = makeProfileWithInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateAcceptsMultiSegmentArrayItemsPath() {
        // NIST vulnerabilities-style path: descend through items.properties
        var entry = new CrosswalkEntry(
                NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "vulnerabilities.severity",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of(
                "type",
                "object",
                "properties",
                Map.<String, Object>of(
                        "vulnerabilities",
                        Map.<String, Object>of(
                                "type",
                                "array",
                                "items",
                                Map.<String, Object>of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.<String, Object>of("severity", Map.<String, Object>of())))));
        var profile = makeProfileWithInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateSkipsValidationWhenSchemaPermitsAdditionalProperties() {
        // additionalProperties: true at the root means any path is valid; no throw
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "anything_goes",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of("type", "object", "additionalProperties", true);
        var profile = makeProfileWithInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateSkipsValidationWhenSchemaHasNoPropertiesKey() {
        // No properties node means we cannot validate; pass through
        var entry = new CrosswalkEntry(
                NormalizedConcept.ASSET,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "free_form_path",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of("type", "object");
        var profile = makeProfileWithInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateSkipsNestedValidationWhenChildHasAdditionalProperties() {
        // Nested additionalProperties: true short-circuits the descent
        var entry = new CrosswalkEntry(
                NormalizedConcept.CONTROL,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "open_obj.anything",
                null,
                null,
                null,
                null,
                null,
                null);
        var inputSchema = Map.<String, Object>of(
                "type",
                "object",
                "properties",
                Map.<String, Object>of(
                        "open_obj", Map.<String, Object>of("type", "object", "additionalProperties", true)));
        var profile = makeProfileWithInputSchema(inputSchema);
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateAcceptsConversionRuleWhenScaleIsSet() {
        // conversionRule + scale (no units) is valid
        var entry = new CrosswalkEntry(
                NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "loss_event_frequency",
                null,
                null,
                "continuous",
                null,
                "LEF = TEF × Vulnerability",
                null);
        var profile = makeProfileWithInputSchema(Map.of("properties", Map.of("loss_event_frequency", Map.of())));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateAcceptsConversionRuleWhenUnitsIsSet() {
        // conversionRule + units (no scale) is also valid
        var entry = new CrosswalkEntry(
                NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "loss_event_frequency",
                null,
                null,
                null,
                "annual events",
                "LEF = TEF × Vulnerability",
                null);
        var profile = makeProfileWithInputSchema(Map.of("properties", Map.of("loss_event_frequency", Map.of())));
        var profileId = profile.getId();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void updateAcceptsNestedTreatmentVocabularyPath() {
        // treatment_vocabulary.MITIGATE.options.tactical descends through Map values
        var entry = new CrosswalkEntry(
                NormalizedConcept.TREATMENT,
                CrosswalkVocabularySurface.TREATMENT_STRATEGY_VOCABULARY,
                "MITIGATE.options",
                null,
                null,
                null,
                null,
                null,
                null);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        profile.setTreatmentStrategyVocabulary(
                Map.of("MITIGATE", Map.of("options", Map.of("tactical", "Tactical mitigation"))));
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(null, null, null, null, null, null, null, null, List.of(entry)));

        assertThat(result.getCrosswalkEntries()).hasSize(1);
    }

    @Test
    void listByProjectSkipsSeedingProfilesThatAlreadyExist() {
        // exercises the seedIfMissing early-return when a profile already exists
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(true);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        service.listByProject(projectId);

        verify(repository, times(0)).save(any(MethodologyProfile.class));
    }
}
