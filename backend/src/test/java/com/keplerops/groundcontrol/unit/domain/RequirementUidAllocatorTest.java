package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementUidAllocator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link RequirementUidAllocator}. The allocator's allocation logic — prefix
 * validation, advisory-lock acquisition, the anchored max-suffix pattern, and the next-suffix
 * computation — is exercised here with mocked collaborators so it is covered without a database.
 * The concurrency / archived-row reservation behavior is proven separately in the Testcontainers
 * integration test, which the SonarCloud CI job does not run.
 */
@ExtendWith(MockitoExtension.class)
class RequirementUidAllocatorTest {

    private static final String ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";
    private static final int UID_LOCK_NAMESPACE = 0x47435549;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RequirementUidAllocator allocator() {
        return new RequirementUidAllocator(requirementRepository, jdbcTemplate);
    }

    @Test
    void allocatesNextSuffixAfterHighWaterMark() {
        var projectId = UUID.randomUUID();
        when(requirementRepository.findMaxUidSuffix(projectId, "^PLAT-[0-9]+$")).thenReturn(5L);

        assertThat(allocator().allocate(projectId, "PLAT")).isEqualTo("PLAT-6");
    }

    @Test
    void allocatesFirstSuffixWhenNoneExist() {
        var projectId = UUID.randomUUID();
        when(requirementRepository.findMaxUidSuffix(projectId, "^PLAT-[0-9]+$")).thenReturn(0L);

        assertThat(allocator().allocate(projectId, "PLAT")).isEqualTo("PLAT-1");
    }

    @Test
    void normalizesLowercasePrefixToUppercase() {
        var projectId = UUID.randomUUID();
        when(requirementRepository.findMaxUidSuffix(projectId, "^PLAT-[0-9]+$")).thenReturn(0L);

        assertThat(allocator().allocate(projectId, "plat")).isEqualTo("PLAT-1");
    }

    @Test
    void supportsHyphenatedPrefix() {
        var projectId = UUID.randomUUID();
        when(requirementRepository.findMaxUidSuffix(projectId, "^GC-GRC-[0-9]+$"))
                .thenReturn(2L);

        assertThat(allocator().allocate(projectId, "GC-GRC")).isEqualTo("GC-GRC-3");
    }

    @Test
    void acquiresAdvisoryLockBeforeReadingMax() {
        var projectId = UUID.randomUUID();
        when(requirementRepository.findMaxUidSuffix(projectId, "^PLAT-[0-9]+$")).thenReturn(0L);

        allocator().allocate(projectId, "PLAT");

        // The advisory lock is keyed on the namespace constant plus a per-(project,prefix) hash.
        verify(jdbcTemplate).queryForList(eq(ADVISORY_LOCK_SQL), eq(UID_LOCK_NAMESPACE), anyInt());
    }

    @Test
    void rejectsPrefixWithIllegalCharacters() {
        var projectId = UUID.randomUUID();

        assertThatThrownBy(() -> allocator().allocate(projectId, "bad prefix"))
                .isInstanceOf(DomainValidationException.class);
        // Validation happens before any lock or query, so no collaborator is touched.
        verifyNoInteractions(jdbcTemplate, requirementRepository);
    }

    @Test
    void rejectsTrailingHyphenPrefix() {
        var projectId = UUID.randomUUID();

        assertThatThrownBy(() -> allocator().allocate(projectId, "PLAT-"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsLeadingHyphenPrefix() {
        var projectId = UUID.randomUUID();

        assertThatThrownBy(() -> allocator().allocate(projectId, "-PLAT"))
                .isInstanceOf(DomainValidationException.class);
    }
}
