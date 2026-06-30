package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.LabelInput;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;

/**
 * The default data classification lattice that ships for every project (GC-GRC-006 clause a). It is
 * expressed as data — a label set plus a covering permitted-flow relation — not as a Java enum that
 * drives evaluation, so projects can replace it with an arbitrary (including non-linear) lattice
 * without code churn.
 *
 * <p>The default models {@code PUBLIC ⊑ INTERNAL ⊑ CONFIDENTIAL ⊑ {PII, CREDENTIALS, SECRETS,
 * REGULATED}}, where the four most-sensitive labels are mutually <em>incomparable</em>: data may
 * flow "up" to an equal-or-more-protected sink, but a flow from a sensitive label to a lower-trust
 * sink (e.g. PII into a PUBLIC log) — or across two distinct sensitive categories — is a violation.
 */
public final class DefaultDataClassificationLattice {

    private static final String PUBLIC = "PUBLIC";
    private static final String INTERNAL = "INTERNAL";
    private static final String CONFIDENTIAL = "CONFIDENTIAL";
    private static final String PII = "PII";
    private static final String CREDENTIALS = "CREDENTIALS";
    private static final String SECRETS = "SECRETS";
    private static final String REGULATED = "REGULATED";

    private static final DataClassificationLatticeCommand COMMAND = new DataClassificationLatticeCommand(
            List.of(
                    new LabelInput(PUBLIC, "Public", "Non-sensitive, freely shareable data.", 0),
                    new LabelInput(INTERNAL, "Internal", "Internal-only business data.", 1),
                    new LabelInput(CONFIDENTIAL, "Confidential", "Confidential business data.", 2),
                    new LabelInput(PII, "Personally Identifiable Information", "Personal data about individuals.", 3),
                    new LabelInput(CREDENTIALS, "Credentials", "Authentication credentials.", 3),
                    new LabelInput(SECRETS, "Secrets", "Cryptographic keys and secret material.", 3),
                    new LabelInput(REGULATED, "Regulated", "Data under regulatory controls.", 3)),
            List.of(
                    new FlowInput(PUBLIC, INTERNAL),
                    new FlowInput(INTERNAL, CONFIDENTIAL),
                    new FlowInput(CONFIDENTIAL, PII),
                    new FlowInput(CONFIDENTIAL, CREDENTIALS),
                    new FlowInput(CONFIDENTIAL, SECRETS),
                    new FlowInput(CONFIDENTIAL, REGULATED)));

    private static final DataClassificationLatticeDefinition DEFINITION =
            DataClassificationLatticeFactory.build(DataClassificationSource.DEFAULT, COMMAND);

    private DefaultDataClassificationLattice() {
        // constants holder
    }

    /** The shipped default lattice, with its permitted-flow closure already computed. */
    public static DataClassificationLatticeDefinition definition() {
        return DEFINITION;
    }

    /** The raw default taxonomy + covering relation, useful as a starting point for a custom lattice. */
    public static DataClassificationLatticeCommand command() {
        return COMMAND;
    }
}
