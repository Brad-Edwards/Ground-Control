package com.keplerops.groundcontrol.domain.testcases.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.util.Map;

/**
 * Stateless helpers split out of {@link TestSuiteService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class TestSuiteServiceSupport {

    private TestSuiteServiceSupport() {}

    static void rejectAnyCriteriaPatchOnNonQuerySuite(UpdateTestSuiteCommand command, TestSuitePopulationMode mode) {
        if (command.criteriaStatus() != null
                || command.criteriaType() != null
                || command.criteriaPriority() != null
                || command.criteriaFormat() != null
                || command.criteriaFolderId() != null
                || command.criteriaTextSearch() != null
                || command.clearCriteriaStatus()
                || command.clearCriteriaType()
                || command.clearCriteriaPriority()
                || command.clearCriteriaFormat()
                || command.clearCriteriaFolderId()
                || command.clearCriteriaTextSearch()) {
            throw new DomainValidationException(
                    "criteria fields are only valid for QUERY_BASED suites",
                    "invalid_test_suite_mode_field",
                    Map.of("mode", mode.name()));
        }
    }

    static void validateCriteriaForMode(TestSuitePopulationMode mode, TestSuiteCriteriaCommand criteria) {
        if (mode == TestSuitePopulationMode.QUERY_BASED) {
            if (!criteria.hasAny()) {
                throw new DomainValidationException(
                        "QUERY_BASED test suite must have at least one criterion",
                        "invalid_test_suite_query",
                        Map.of());
            }
        } else if (criteria.hasAny()) {
            throw new DomainValidationException(
                    "criteria fields are only valid for QUERY_BASED suites",
                    "invalid_test_suite_mode_field",
                    Map.of("mode", mode.name()));
        }
    }

    static <T> T resolveNullable(boolean clear, T incoming, T current) {
        if (clear) {
            return null;
        }
        return incoming != null ? incoming : current;
    }
}
