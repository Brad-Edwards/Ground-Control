package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackInstallRecord;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.repository.PackInstallRecordRepository;
import com.keplerops.groundcontrol.domain.packregistry.service.InstallPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.service.PackInstallOrchestrator;
import com.keplerops.groundcontrol.domain.packregistry.service.PackInstallRecordWriter;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityException;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerification;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerifier;
import com.keplerops.groundcontrol.domain.packregistry.service.PackOperationContext;
import com.keplerops.groundcontrol.domain.packregistry.service.PackOperationResult;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.packregistry.service.PackTypeHandler;
import com.keplerops.groundcontrol.domain.packregistry.service.PackTypeHandlerRegistry;
import com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack;
import com.keplerops.groundcontrol.domain.packregistry.service.TrustDecision;
import com.keplerops.groundcontrol.domain.packregistry.service.TrustEvaluator;
import com.keplerops.groundcontrol.domain.packregistry.state.InstallOutcome;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.packregistry.state.TrustOutcome;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PackInstallOrchestrator}, exercised through a lightweight in-test
 * {@link PackTypeHandler} double so the orchestration gates (resolution, compatibility,
 * integrity, trust) are tested independent of any specific pack type's install/upgrade
 * business logic (ADR-089 retired the only pack type — CONTROL_PACK — whose handler
 * performed a real install).
 */
@ExtendWith(MockitoExtension.class)
class PackInstallOrchestratorTest {

    @Mock
    private PackResolver packResolver;

    @Mock
    private PackIntegrityVerifier packIntegrityVerifier;

    @Mock
    private TrustEvaluator trustEvaluator;

    @Mock
    private PackInstallRecordRepository installRecordRepository;

    @Mock
    private PackInstallRecordWriter installRecordWriter;

    @Mock
    private ProjectService projectService;

    private AtomicInteger installCalls;
    private AtomicInteger upgradeCalls;
    private final AtomicReference<PackOperationContext> lastInstallContext = new AtomicReference<>();
    private final AtomicReference<PackOperationContext> lastUpgradeContext = new AtomicReference<>();
    private PackInstallOrchestrator orchestrator;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PACK_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final PackIntegrityVerification VERIFIED_INTEGRITY = new PackIntegrityVerification(
            "sha256:1a4bb65f2acb8f8c8eb81f3904fbac1af467b6510f0dc99fd45a8ed7e2d2f6d5", true, null, null);

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private ResolvedPack makeResolvedPack(Project project) {
        var entry = new PackRegistryEntry(project, "custom-pack", PackType.CUSTOM, "1.0.0");
        entry.setPublisher("Acme");
        entry.setSourceUrl("https://registry.example.com/custom-pack");
        return new ResolvedPack(entry, "1.0.0", "https://registry.example.com/custom-pack", "sha256:abc123", List.of());
    }

    @BeforeEach
    void setUp() {
        installCalls = new AtomicInteger();
        upgradeCalls = new AtomicInteger();
        lastInstallContext.set(null);
        lastUpgradeContext.set(null);
        PackTypeHandler testHandler = new PackTypeHandler() {
            @Override
            public PackType packType() {
                return PackType.CUSTOM;
            }

            @Override
            public void applyRegistrationContent(PackRegistryEntry entry, PackRegistrationContent content) {
                // not exercised by these tests
            }

            @Override
            public PackOperationResult install(PackOperationContext context) {
                installCalls.incrementAndGet();
                lastInstallContext.set(context);
                return new PackOperationResult(PACK_ENTITY_ID);
            }

            @Override
            public PackOperationResult upgrade(PackOperationContext context) {
                upgradeCalls.incrementAndGet();
                lastUpgradeContext.set(context);
                return new PackOperationResult(PACK_ENTITY_ID);
            }
        };
        orchestrator = new PackInstallOrchestrator(
                packResolver,
                packIntegrityVerifier,
                trustEvaluator,
                installRecordRepository,
                installRecordWriter,
                new PackTypeHandlerRegistry(List.of(testHandler)),
                projectService);
    }

    @Nested
    class InstallPack {

        @Test
        void resolvesAndInstallsWhenTrusted() {
            var project = makeProject();
            var resolved = makeResolvedPack(project);

            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "custom-pack", "^1.0.0")).thenReturn(resolved);
            when(packResolver.checkCompatibility(resolved)).thenReturn(true);
            when(packIntegrityVerifier.verify(resolved)).thenReturn(VERIFIED_INTEGRITY);
            when(trustEvaluator.evaluate(PROJECT_ID, resolved, VERIFIED_INTEGRITY))
                    .thenReturn(new TrustDecision(TrustOutcome.TRUSTED, "Trusted publisher", "policy-1"));
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "custom-pack", "^1.0.0", "admin");
            var record = orchestrator.installPack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.INSTALLED);
            assertThat(record.getTrustOutcome()).isEqualTo(TrustOutcome.TRUSTED);
            assertThat(record.getInstalledEntityId()).isEqualTo(PACK_ENTITY_ID);
            assertThat(record.getResolvedChecksum()).isEqualTo(VERIFIED_INTEGRITY.verifiedChecksum());
            assertThat(installCalls.get()).isEqualTo(1);

            // The handler is only as safe as the context the orchestrator builds for it: a
            // stale entry or the wrong project would otherwise install a pack into the wrong
            // scope while every outcome assertion above still passed.
            var context = lastInstallContext.get();
            assertThat(context).isNotNull();
            assertThat(context.projectId()).isEqualTo(PROJECT_ID);
            assertThat(context.entry()).isSameAs(resolved.entry());
            assertThat(context.resolvedPack()).isSameAs(resolved);
            assertThat(context.integrityVerification()).isEqualTo(VERIFIED_INTEGRITY);
        }

        @Test
        void createsRejectionRecordWhenUntrusted() {
            var project = makeProject();
            var resolved = makeResolvedPack(project);

            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "custom-pack", null)).thenReturn(resolved);
            when(packResolver.checkCompatibility(resolved)).thenReturn(true);
            when(packIntegrityVerifier.verify(resolved)).thenReturn(VERIFIED_INTEGRITY);
            when(trustEvaluator.evaluate(PROJECT_ID, resolved, VERIFIED_INTEGRITY))
                    .thenReturn(new TrustDecision(TrustOutcome.REJECTED, "Untrusted publisher", "policy-1"));
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "custom-pack", null, "admin");
            var record = orchestrator.installPack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.REJECTED);
            assertThat(record.getTrustOutcome()).isEqualTo(TrustOutcome.REJECTED);
            assertThat(installCalls.get()).isZero();
        }

        @Test
        void createsRejectionRecordWhenIntegrityVerificationFails() {
            var project = makeProject();
            var resolved = makeResolvedPack(project);

            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "custom-pack", null)).thenReturn(resolved);
            when(packResolver.checkCompatibility(resolved)).thenReturn(true);
            when(packIntegrityVerifier.verify(resolved))
                    .thenThrow(new PackIntegrityException(
                            "Pack signature verification failed", "sha256:bad", false, false));
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "custom-pack", null, "admin");
            var record = orchestrator.installPack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.REJECTED);
            assertThat(record.getTrustOutcome()).isEqualTo(TrustOutcome.REJECTED);
            assertThat(record.getSignatureVerified()).isFalse();
            assertThat(record.getResolvedChecksum()).isEqualTo("sha256:bad");
            assertThat(record.getErrorDetail()).contains("signature verification failed");
            verify(trustEvaluator, never()).evaluate(any(), any(), any());
            assertThat(installCalls.get()).isZero();
        }

        @Test
        void recordsFailureWhenResolutionFails() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "missing-pack", null)).thenThrow(new NotFoundException("Not found"));
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "missing-pack", null, "admin");
            var record = orchestrator.installPack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.FAILED);
            assertThat(record.getErrorDetail()).contains("Resolution failed");
            assertThat(installCalls.get()).isZero();
        }

        @Test
        void rejectsIncompatiblePack() {
            var project = makeProject();
            var resolved = makeResolvedPack(project);

            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "custom-pack", null)).thenReturn(resolved);
            when(packResolver.checkCompatibility(resolved)).thenReturn(false);
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "custom-pack", null, "admin");
            var record = orchestrator.installPack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.REJECTED);
            assertThat(record.getErrorDetail()).contains("not compatible");
            assertThat(installCalls.get()).isZero();
        }
    }

    @Nested
    class UpgradePack {

        @Test
        void resolvesAndUpgradesWhenTrusted() {
            var project = makeProject();
            var resolved = makeResolvedPack(project);

            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(packResolver.resolve(PROJECT_ID, "custom-pack", "^1.0.0")).thenReturn(resolved);
            when(packResolver.checkCompatibility(resolved)).thenReturn(true);
            when(packIntegrityVerifier.verify(resolved)).thenReturn(VERIFIED_INTEGRITY);
            when(trustEvaluator.evaluate(PROJECT_ID, resolved, VERIFIED_INTEGRITY))
                    .thenReturn(new TrustDecision(TrustOutcome.TRUSTED, "Trusted", "policy-1"));
            when(installRecordWriter.save(any(PackInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new InstallPackCommand(PROJECT_ID, "custom-pack", "^1.0.0", "admin");
            var record = orchestrator.upgradePack(command);

            assertThat(record.getInstallOutcome()).isEqualTo(InstallOutcome.UPGRADED);
            assertThat(record.getTrustOutcome()).isEqualTo(TrustOutcome.TRUSTED);
            assertThat(upgradeCalls.get()).isEqualTo(1);

            var context = lastUpgradeContext.get();
            assertThat(context).isNotNull();
            assertThat(context.projectId()).isEqualTo(PROJECT_ID);
            assertThat(context.entry()).isSameAs(resolved.entry());
            assertThat(context.resolvedPack()).isSameAs(resolved);
            assertThat(context.integrityVerification()).isEqualTo(VERIFIED_INTEGRITY);
        }
    }
}
