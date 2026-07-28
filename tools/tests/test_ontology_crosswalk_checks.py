import copy
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from unittest.mock import patch

from tools.policy.checks import (
    DEFERRAL_CASES_PATH,
    ENUM_CONTRACT_INVENTORY,
    FRONTEND_API_TYPES_PATH,
    MCP_LIB_PATH,
    REPO_ROOT,
    Violation,
    _is_release_pr,
    _jsonpath_keys,
    _resolve_pr_refs,
    check_pr_body,
    classify_deferral_language,
    extract_step_section,
    main,
    parse_args,
    parse_const_string_array,
    parse_java_enum_constants,
    parse_ts_union_literals,
    read_changed_files,
    run_adr_guard,
    _trigger_is_in_scope,
    run_ci_strictness_contract,
    run_authz_matrix_sync_check,
    run_contract_invariant_enforcement_check,
    run_contract_surface_check,
    run_controller_contracts,
    run_deploy_artifact_consistency,
    run_deploy_compose_credential_passthrough,
    run_documentation_coverage_check,
    run_enum_contract_check,
    run_ghcr_namespace_drift,
    run_repo_identity_drift,
    run_measurement_catalogue_check,
    run_migration_policy,
    run_no_deferral_disposition_check,
    run_ontology_binding_check,
    run_ontology_crosswalk_check,
    run_pr_body_check,
    run_test_quality_decision_record_contract,
    run_traceability_reconciliation_gate_contract,
    run_version_mirror_consistency_check,
    run_workflow_routing_contract,
    run_implement_execution_contract,
)

if __name__ == "__main__":
    unittest.main()

class OntologyCrosswalkChecksTest(unittest.TestCase):
    """Fixture-driven coverage for run_ontology_crosswalk_check (ADR-084 §4)."""
    SNAPSHOT_REL = "contracts/ontology/external/aces-sdl/0.23.0/concept-families-v1.json"
    def _aces_snapshot(self) -> dict:
        return {
            "schema_version": "concept-families/v1",
            "families": {
                "assets": {"title": "Assets", "description": "Nodes and infra."},
                "observables": {"title": "Observables", "description": "Telemetry."},
                "time-and-apparatus": {"title": "Time and Apparatus", "description": "Clocks."},
            },
        }
    def _gc_catalog(self) -> dict:
        return {
            "schema_version": "gc-concept-families/v1",
            "owners": ["ground-control"],
            "families": {
                "architecture-and-boundaries": {
                    "title": "Architecture and Boundaries",
                    "description": "Assets and boundaries.",
                    "provenance": "native",
                    "owner": "ground-control",
                    "extension_scope": "Architecture participants.",
                    "relation_rules": ["Structural relations are directed."],
                    "non_ambiguity_constraints": ["An asset is not an observation."],
                },
                "evidence-and-observation": {
                    "title": "Evidence and Observation",
                    "description": "Evidence and observations.",
                    "provenance": "native",
                    "owner": "ground-control",
                    "extension_scope": "Observations and evidence.",
                    "relation_rules": ["Evidence points from subject to evidence."],
                    "non_ambiguity_constraints": ["The subject of evidence is not evidence."],
                },
            },
        }
    def _write_crosswalk_fixture(self, root: Path) -> Path:
        ontology = root / "contracts" / "ontology"
        (ontology / "crosswalks").mkdir(parents=True)
        (ontology / "gc-concept-families-v1.json").write_text(
            json.dumps(self._gc_catalog()), encoding="utf-8"
        )
        snapshot_path = root / self.SNAPSHOT_REL
        snapshot_path.parent.mkdir(parents=True)
        snapshot_bytes = json.dumps(self._aces_snapshot()).encode("utf-8")
        snapshot_path.write_bytes(snapshot_bytes)
        digest = hashlib.sha256(snapshot_bytes).hexdigest()
        crosswalk = {
            "schema_version": "aces-concept-families-crosswalk/v1",
            "gc_catalog": "contracts/ontology/gc-concept-families-v1.json",
            "gc_catalog_schema_version": "gc-concept-families/v1",
            "external_pin": {
                "authority": "aces-sdl",
                "distribution": "aces-sdl",
                "release_version": "0.23.0",
                "artifact_path": "aces_contracts/_corpus/concept-authority/concept-families-v1.json",
                "catalog_schema_version": "concept-families/v1",
                "sha256": digest,
                "reference_snapshot": self.SNAPSHOT_REL,
            },
            "review_scope": "Directional review; effect states the ACES family's effect on GC meaning.",
            "alignments": [
                {
                    "gc_family": "architecture-and-boundaries",
                    "aces_family": "assets",
                    "effect": "refines",
                    "rationale": "ACES assets is the narrower node/infrastructure slice.",
                    "divergences": ["GC folds operational assets into architecture-and-boundaries."],
                },
                {
                    "gc_family": "evidence-and-observation",
                    "aces_family": "observables",
                    "effect": "refines",
                    "rationale": "ACES observables is the telemetry slice.",
                    "divergences": ["GC also spans evidence artifacts and supersession."],
                },
            ],
            "omissions": [
                {
                    "topic": "time",
                    "aces_family": "time-and-apparatus",
                    "reason": "Ground Control has no time concept family; ADR-084 §5 makes time the Envers spine.",
                }
            ],
        }
        crosswalk_path = ontology / "crosswalks" / "aces-concept-families-v1.json"
        crosswalk_path.write_text(json.dumps(crosswalk), encoding="utf-8")
        return crosswalk_path
    def _read_crosswalk(self, root: Path) -> tuple[Path, dict]:
        path = root / "contracts" / "ontology" / "crosswalks" / "aces-concept-families-v1.json"
        return path, json.loads(path.read_text(encoding="utf-8"))
    def test_passes_minimal_complete_crosswalk(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_crosswalk_fixture(root)
            violations = run_ontology_crosswalk_check(root=root)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])
    def test_missing_crosswalk_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            violations = run_ontology_crosswalk_check(root=Path(tmp_dir))
        self.assertIn("ontology-crosswalk-missing", {v.code for v in violations})
    def test_fails_closed_on_malformed_json(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            path = self._write_crosswalk_fixture(root)
            path.write_text("{", encoding="utf-8")
            violations = run_ontology_crosswalk_check(root=root)
        self.assertIn("ontology-crosswalk-invalid-json", {v.code for v in violations})
    def test_rejects_duplicate_json_keys(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            path = self._write_crosswalk_fixture(root)
            payload = path.read_text(encoding="utf-8")
            duplicate = payload.replace(
                '"review_scope"', '"schema_version": "aces-concept-families-crosswalk/v1", "review_scope"', 1
            )
            path.write_text(duplicate, encoding="utf-8")
            violations = run_ontology_crosswalk_check(root=root)
        self.assertIn("ontology-crosswalk-invalid-json", {v.code for v in violations})
    def test_covers_validation_branches(self):
        cases = (
            ("bad-schema-version", "ontology-crosswalk-version-invalid"),
            ("hash-drift", "ontology-crosswalk-hash-drift"),
            ("missing-snapshot", "ontology-crosswalk-snapshot-missing"),
            ("snapshot-duplicate-keys", "ontology-crosswalk-snapshot-invalid-json"),
            ("unsafe-snapshot-path", "ontology-crosswalk-pin-invalid"),
            ("snapshot-outside-external-root", "ontology-crosswalk-pin-invalid"),
            ("layout-mismatch", "ontology-crosswalk-pin-invalid"),
            ("catalog-schema-mismatch", "ontology-crosswalk-pin-invalid"),
            ("gc-catalog-not-canonical", "ontology-crosswalk-gc-catalog-invalid"),
            ("gc-catalog-schema-mismatch", "ontology-crosswalk-gc-catalog-invalid"),
            ("gc-catalog-schema-drift", "ontology-crosswalk-gc-catalog-invalid"),
            ("time-alignment-forbidden", "ontology-crosswalk-time-alignment-forbidden"),
            ("time-omission-missing", "ontology-crosswalk-time-omission-required"),
            ("dangling-gc-family", "ontology-crosswalk-gc-family-missing"),
            ("dangling-aces-family", "ontology-crosswalk-aces-family-missing"),
            ("effect-outside-vocabulary", "ontology-crosswalk-effect-invalid"),
            ("aligns-with-divergences", "ontology-crosswalk-effect-divergence-mismatch"),
            ("refines-without-divergences", "ontology-crosswalk-effect-divergence-mismatch"),
            ("missing-rationale", "ontology-crosswalk-rationale-missing"),
            ("duplicate-alignment", "ontology-crosswalk-alignment-duplicate"),
            ("empty-alignments", "ontology-crosswalk-shape-invalid"),
            ("omission-dangling-family", "ontology-crosswalk-omission-family-missing"),
            ("omission-missing-reason", "ontology-crosswalk-omission-invalid"),
        )
        for case, expected_code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp_dir:
                root = Path(tmp_dir)
                self._write_crosswalk_fixture(root)
                path, crosswalk = self._read_crosswalk(root)
                if case == "bad-schema-version":
                    crosswalk["schema_version"] = "aces-concept-families-crosswalk/v2"
                elif case == "hash-drift":
                    crosswalk["external_pin"]["sha256"] = "0" * 64
                elif case == "missing-snapshot":
                    (root / self.SNAPSHOT_REL).unlink()
                elif case == "snapshot-duplicate-keys":
                    snap = root / self.SNAPSHOT_REL
                    dup = snap.read_text(encoding="utf-8").replace(
                        '{"schema_version"',
                        '{"schema_version": "concept-families/v1", "schema_version"',
                        1,
                    )
                    snap.write_text(dup, encoding="utf-8")
                    # Re-pin the hash so only the duplicate-key parse violation fires.
                    crosswalk["external_pin"]["sha256"] = hashlib.sha256(
                        dup.encode("utf-8")
                    ).hexdigest()
                elif case == "unsafe-snapshot-path":
                    crosswalk["external_pin"]["reference_snapshot"] = "../outside.json"
                elif case == "snapshot-outside-external-root":
                    crosswalk["external_pin"]["reference_snapshot"] = "contracts/ontology/gc-concept-families-v1.json"
                elif case == "layout-mismatch":
                    crosswalk["external_pin"]["release_version"] = "9.9.9"
                elif case == "catalog-schema-mismatch":
                    crosswalk["external_pin"]["catalog_schema_version"] = "concept-families/v2"
                elif case == "gc-catalog-not-canonical":
                    alt = root / "contracts" / "ontology" / "alt-families.json"
                    alt.write_text(json.dumps(self._gc_catalog()), encoding="utf-8")
                    crosswalk["gc_catalog"] = "contracts/ontology/alt-families.json"
                elif case == "gc-catalog-schema-mismatch":
                    crosswalk["gc_catalog_schema_version"] = "gc-concept-families/v2"
                elif case == "gc-catalog-schema-drift":
                    gc_path = root / "contracts" / "ontology" / "gc-concept-families-v1.json"
                    drifted = self._gc_catalog()
                    drifted["schema_version"] = "gc-concept-families/v2"
                    gc_path.write_text(json.dumps(drifted), encoding="utf-8")
                elif case == "time-alignment-forbidden":
                    crosswalk["alignments"].append(
                        {
                            "gc_family": "architecture-and-boundaries",
                            "aces_family": "time-and-apparatus",
                            "effect": "refines",
                            "rationale": "invalid: time has no GC family.",
                            "divergences": ["should be rejected"],
                        }
                    )
                elif case == "time-omission-missing":
                    crosswalk["omissions"] = []
                elif case == "dangling-gc-family":
                    crosswalk["alignments"][0]["gc_family"] = "no-such-family"
                elif case == "dangling-aces-family":
                    crosswalk["alignments"][0]["aces_family"] = "no-such-family"
                elif case == "effect-outside-vocabulary":
                    crosswalk["alignments"][0]["effect"] = "supersedes"
                elif case == "aligns-with-divergences":
                    crosswalk["alignments"][0]["effect"] = "aligns"
                elif case == "refines-without-divergences":
                    crosswalk["alignments"][0]["divergences"] = []
                elif case == "missing-rationale":
                    crosswalk["alignments"][0]["rationale"] = "  "
                elif case == "duplicate-alignment":
                    crosswalk["alignments"].append(dict(crosswalk["alignments"][0]))
                elif case == "empty-alignments":
                    crosswalk["alignments"] = []
                elif case == "omission-dangling-family":
                    crosswalk["omissions"][0]["aces_family"] = "no-such-family"
                elif case == "omission-missing-reason":
                    crosswalk["omissions"][0]["reason"] = ""
                path.write_text(json.dumps(crosswalk), encoding="utf-8")
                violations = run_ontology_crosswalk_check(root=root)
                self.assertIn(expected_code, {v.code for v in violations})
    def test_real_committed_crosswalk_passes(self):
        violations = run_ontology_crosswalk_check(root=REPO_ROOT)
        self.assertEqual(violations, [], msg=[v.render() for v in violations])
