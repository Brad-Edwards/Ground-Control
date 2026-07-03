package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import java.util.List;

/**
 * GC-RSCH-N006 / ADR-084 §2 — pure, default-deny evaluator for a research run's
 * snapshotted egress policy. A request to keep material {@code LOCAL}, or to move
 * only {@code NONE} data, is always permitted; every other (dataClass,
 * destinationClass, form) is permitted only when a snapshotted
 * {@link ResearchEgressAllowance} covers it. Absence of any allow rule is deny
 * (local-only). No side effects, no external input — the decision is a function
 * of the snapshotted policy alone, so untrusted content cannot influence it.
 */
public final class EgressPolicyEvaluator {

    private EgressPolicyEvaluator() {}

    /** Outcome of an egress evaluation with the human-readable basis recorded on the authorization. */
    public record EgressDecision(boolean permitted, String basis) {}

    public static EgressDecision evaluate(
            List<ResearchEgressAllowance> policy,
            ResearchDataClass dataClass,
            ResearchDestinationClass destination,
            ResearchDataForm form) {
        if (destination == ResearchDestinationClass.LOCAL) {
            return new EgressDecision(true, "local");
        }
        if (form == null || form == ResearchDataForm.NONE) {
            return new EgressDecision(true, "no_egress");
        }
        if (policy != null) {
            for (var allowance : policy) {
                if (allowance != null && allowance.covers(dataClass, destination, form)) {
                    return new EgressDecision(
                            true, "allow:" + dataClass + "->" + destination + "@" + allowance.allowedForm());
                }
            }
        }
        return new EgressDecision(false, "default_deny");
    }
}
