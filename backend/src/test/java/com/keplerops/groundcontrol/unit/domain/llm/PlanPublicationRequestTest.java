package com.keplerops.groundcontrol.unit.domain.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PlanPublicationRequestTest {

    private static final LlmCompletion COMPLETION = new LlmCompletion("plan text", 10, 20);

    @Test
    void rejectsNullCompletion() {
        var uids = List.of("GC-O009");

        assertThatThrownBy(() -> new PlanPublicationRequest("gc", 1280, uids, "issue-1280:plan", null))
                .isInstanceOf(DomainValidationException.class);
    }

    /**
     * Both halves of the {@code x == null || x.isBlank()} guard. Exercising only the blank half leaves the
     * null half untested: delete {@code x == null ||} and a null argument throws NullPointerException out of
     * {@code isBlank()} instead of the intended DomainValidationException, with no test to notice.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsAMissingIdempotencyKey(String idempotencyKey) {
        assertThatThrownBy(() -> new PlanPublicationRequest("gc", 1280, List.of(), idempotencyKey, COMPLETION))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsAMissingProject(String project) {
        assertThatThrownBy(() -> new PlanPublicationRequest(project, 1280, List.of(), "issue-1280:plan", COMPLETION))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void normalizesNullRequirementUidsToEmptyList() {
        var request = new PlanPublicationRequest("gc", 1280, null, "issue-1280:plan", COMPLETION);

        assertThat(request.requirementUids()).isEmpty();
    }

    @Test
    void accessorsReturnConstructedValues() {
        var request = new PlanPublicationRequest("gc", 1280, List.of("GC-O009"), "issue-1280:plan", COMPLETION);

        assertThat(request.project()).isEqualTo("gc");
        assertThat(request.issueNumber()).isEqualTo(1280);
        assertThat(request.requirementUids()).containsExactly("GC-O009");
        assertThat(request.idempotencyKey()).isEqualTo("issue-1280:plan");
        assertThat(request.completion()).isEqualTo(COMPLETION);
    }
}
