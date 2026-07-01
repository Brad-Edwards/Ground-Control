package com.keplerops.groundcontrol.unit.domain.dataclassification;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationFlowRule;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLabel;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import org.junit.jupiter.api.Test;

/**
 * Construction and accessors of the data classification lattice JPA aggregate (GC-GRC-006). These
 * entities carry no logic, but the unit assertions document the stored shape and keep the persisted
 * contract (schema version default, project/lattice ownership) under test without a database.
 */
class DataClassificationEntityTest {

    private static final Project PROJECT = new Project("ground-control", "Ground Control");

    @Test
    void latticeRootStoresPolicyMetadataAndDefaultsSchemaVersion() {
        var root = new DataClassificationLattice(PROJECT, "dcl/abc123", DataClassificationSource.CUSTOM, 7, 12);

        assertThat(root.getProject()).isSameAs(PROJECT);
        assertThat(root.getPolicyVersion()).isEqualTo("dcl/abc123");
        assertThat(root.getSource()).isEqualTo(DataClassificationSource.CUSTOM);
        assertThat(root.getLabelCount()).isEqualTo(7);
        assertThat(root.getEdgeCount()).isEqualTo(12);
        assertThat(root.getSchemaVersion()).isEqualTo(DataClassificationLattice.SCHEMA_VERSION);
    }

    @Test
    void labelStoresTaxonomyEntry() {
        var root = new DataClassificationLattice(PROJECT, "dcl/abc123", DataClassificationSource.CUSTOM, 1, 1);
        var label = new DataClassificationLabel(PROJECT, root, "PII", "Personal Data", "Personal information.", 3);

        assertThat(label.getProject()).isSameAs(PROJECT);
        assertThat(label.getLattice()).isSameAs(root);
        assertThat(label.getLabelKey()).isEqualTo("PII");
        assertThat(label.getDisplayName()).isEqualTo("Personal Data");
        assertThat(label.getDescription()).isEqualTo("Personal information.");
        assertThat(label.getRank()).isEqualTo(3);
    }

    @Test
    void flowRuleStoresAPermittedEdge() {
        var root = new DataClassificationLattice(PROJECT, "dcl/abc123", DataClassificationSource.CUSTOM, 2, 1);
        var rule = new DataClassificationFlowRule(PROJECT, root, "PUBLIC", "INTERNAL");

        assertThat(rule.getProject()).isSameAs(PROJECT);
        assertThat(rule.getLattice()).isSameAs(root);
        assertThat(rule.getFromLabelKey()).isEqualTo("PUBLIC");
        assertThat(rule.getToLabelKey()).isEqualTo("INTERNAL");
    }
}
