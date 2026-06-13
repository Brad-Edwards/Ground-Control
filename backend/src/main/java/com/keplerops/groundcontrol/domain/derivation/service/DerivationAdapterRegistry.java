package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DerivationAdapterRegistry {

    private final List<DerivationAdapter> adapters;

    public DerivationAdapterRegistry(List<DerivationAdapter> adapters) {
        this.adapters = adapters == null
                ? List.of()
                : adapters.stream()
                        .sorted(Comparator.comparing(
                                adapter -> adapter.descriptor().adapterId()))
                        .toList();
    }

    public List<DerivationAdapterDescriptor> listAdapters() {
        return adapters.stream().map(DerivationAdapter::descriptor).toList();
    }

    public DerivationRoutePlan route(DerivationScope scope, Instant capturedAt) {
        var applicable = adapters.stream()
                .filter(DerivationAdapter::isAvailable)
                .filter(adapter -> supportsRequestedScope(adapter.descriptor(), scope))
                .toList();
        return new DerivationRoutePlan(applicable, coverageLimits(scope, capturedAt));
    }

    private boolean supportsRequestedScope(DerivationAdapterDescriptor descriptor, DerivationScope scope) {
        return descriptor.supportsScopeMode(scope.mode())
                && intersectsLanguage(descriptor, scope)
                && intersectsSurface(descriptor, scope);
    }

    private boolean intersectsLanguage(DerivationAdapterDescriptor descriptor, DerivationScope scope) {
        return scope.languages().stream().anyMatch(descriptor::supportsLanguage);
    }

    private boolean intersectsSurface(DerivationAdapterDescriptor descriptor, DerivationScope scope) {
        return scope.surfaces().stream().anyMatch(descriptor::supportsSurface);
    }

    private List<DerivationCaptureLimitDraft> coverageLimits(DerivationScope scope, Instant capturedAt) {
        var limits = new ArrayList<DerivationCaptureLimitDraft>();
        for (String language : scope.languages()) {
            for (String surface : scope.surfaces()) {
                if (hasAvailableAdapterForPair(scope, language, surface)) {
                    continue;
                }
                limits.add(captureLimitForPair(scope, language, surface, capturedAt));
            }
        }
        return List.copyOf(limits);
    }

    private boolean hasAvailableAdapterForPair(DerivationScope scope, String language, String surface) {
        return adapters.stream()
                .filter(DerivationAdapter::isAvailable)
                .map(DerivationAdapter::descriptor)
                .anyMatch(descriptor -> descriptor.supportsLanguage(language)
                        && descriptor.supportsSurface(surface)
                        && descriptor.supportsScopeMode(scope.mode()));
    }

    private DerivationCaptureLimitDraft captureLimitForPair(
            DerivationScope scope, String language, String surface, Instant capturedAt) {
        var pairDescriptors = adapters.stream()
                .map(DerivationAdapter::descriptor)
                .filter(descriptor -> descriptor.supportsLanguage(language) && descriptor.supportsSurface(surface))
                .toList();

        var adapterId = pairDescriptors.stream()
                .map(DerivationAdapterDescriptor::adapterId)
                .findFirst()
                .orElse(null);
        var reason = reasonFor(scope, language, surface, pairDescriptors);
        return new DerivationCaptureLimitDraft(
                adapterId,
                reason,
                language,
                surface,
                detailFor(reason, language, surface, scope),
                scope.commitSha(),
                capturedAt);
    }

    private CaptureLimitReason reasonFor(
            DerivationScope scope, String language, String surface, List<DerivationAdapterDescriptor> pairDescriptors) {
        if (adapters.stream()
                .map(DerivationAdapter::descriptor)
                .noneMatch(descriptor -> descriptor.supportsLanguage(language))) {
            return CaptureLimitReason.UNSUPPORTED_LANGUAGE;
        }
        if (adapters.stream()
                .map(DerivationAdapter::descriptor)
                .noneMatch(descriptor -> descriptor.supportsSurface(surface))) {
            return CaptureLimitReason.UNSUPPORTED_SURFACE;
        }
        if (pairDescriptors.stream().noneMatch(descriptor -> descriptor.supportsScopeMode(scope.mode()))) {
            return CaptureLimitReason.SCOPE_UNSUPPORTED;
        }
        if (adapters.stream()
                .filter(adapter -> !adapter.isAvailable())
                .map(DerivationAdapter::descriptor)
                .anyMatch(descriptor -> descriptor.supportsLanguage(language)
                        && descriptor.supportsSurface(surface)
                        && descriptor.supportsScopeMode(scope.mode()))) {
            return CaptureLimitReason.TOOL_UNAVAILABLE;
        }
        return CaptureLimitReason.UNSUPPORTED_SURFACE;
    }

    private String detailFor(CaptureLimitReason reason, String language, String surface, DerivationScope scope) {
        return switch (reason) {
            case UNSUPPORTED_LANGUAGE -> "No derivation adapter is registered for language '" + language + "'";
            case UNSUPPORTED_SURFACE -> "No derivation adapter is registered for language '" + language
                    + "' on surface '" + surface + "'";
            case SCOPE_UNSUPPORTED -> "Registered derivation adapters for language '" + language + "' and surface '"
                    + surface + "' do not support scope mode " + scope.mode();
            case TOOL_UNAVAILABLE -> "A matching derivation adapter is registered but unavailable";
            case DISABLED_ADAPTER -> "A matching derivation adapter is disabled";
            case TOOL_EXECUTION_FAILED -> "A matching derivation adapter failed during execution";
        };
    }
}
