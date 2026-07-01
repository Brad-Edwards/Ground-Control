package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.research.model.MethodProfile;
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
import org.junit.jupiter.api.Test;

/**
 * GC-RSCH-F006 / ADR-078 — the backend-owned methodology catalog loads, validates,
 * and exposes the shipped method profiles as immutable reference data.
 */
class MethodologyCatalogTest {

    private final MethodologyCatalog catalog = new MethodologyCatalog();

    @Test
    void loadsAllSevenMethods_eachWithRequiredSources() {
        var profiles = catalog.allProfiles();
        assertThat(profiles).hasSize(7);
        assertThat(profiles)
                .extracting(MethodProfile::methodKey)
                .containsExactlyInAnyOrder(
                        "scoping",
                        "systematic",
                        "mapping",
                        "critical",
                        "narrative_conceptual",
                        "targeted_related_work",
                        "taxonomy_development");
        assertThat(profiles).allSatisfy(p -> {
            assertThat(p.label()).isNotBlank();
            assertThat(p.profileVersion()).isNotBlank();
            assertThat(p.requiredSources()).isNotEmpty();
            assertThat(p.requiredSources()).allSatisfy(s -> assertThat(s.ref()).isNotBlank());
        });
    }

    @Test
    void catalogVersion_isNotBlank() {
        assertThat(catalog.catalogVersion()).isNotBlank();
    }

    @Test
    void findProfile_present_returnsProfile() {
        var profile = catalog.findProfile("systematic");
        assertThat(profile).isPresent();
        assertThat(profile.get().methodKey()).isEqualTo("systematic");
        assertThat(profile.get().requiredSources()).extracting(s -> s.ref()).contains("FRM9HPNG", "MJX3HCT5");
    }

    @Test
    void findProfile_unknown_isEmpty() {
        assertThat(catalog.findProfile("does-not-exist")).isEmpty();
        assertThat(catalog.findProfile(null)).isEmpty();
    }

    @Test
    void requireProfile_unknown_throwsDomainValidation() {
        assertThatThrownBy(() -> catalog.requireProfile("nope"))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).getErrorCode())
                .isEqualTo("research_run_methodology_unknown_method");
    }

    @Test
    void missingResource_failsClosed() {
        assertThatThrownBy(() -> new MethodologyCatalog("research/does-not-exist.yaml"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankCatalogVersion_failsClosed() {
        assertThatThrownBy(() -> new MethodologyCatalog("research/methodology-catalog-blank-version.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog_version");
    }

    @Test
    void methodWithZeroRequiredSources_failsClosed() {
        assertThatThrownBy(() -> new MethodologyCatalog("research/methodology-catalog-no-sources.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required source");
    }
}
