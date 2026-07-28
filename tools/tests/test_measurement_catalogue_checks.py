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

class MeasurementCatalogueChecksTest(unittest.TestCase):
    """ADR-090 / GC-O014: the station catalogue is the authority for station identity.

    Each drift assertion is paired with a mutation that must make the check fire. A
    check that silently scans nothing also reports zero violations, so proving the
    negative is the only thing that distinguishes "resolved" from "never looked".
    """
    CATALOGUE_PATH = REPO_ROOT / "contracts/measurement/gc-station-catalogue-v2.json"
    FROZEN_V1_CATALOGUE_PATH = REPO_ROOT / "contracts/measurement/gc-station-catalogue-v1.json"
    RECORD_SCHEMA_PATH = REPO_ROOT / "contracts/schemas/measurement/measurement-record.v1.schema.json"
    CATALOGUE_SCHEMA_PATH = REPO_ROOT / "contracts/schemas/measurement/station-catalogue.v2.schema.json"
    FINDING_SCHEMA_PATH = REPO_ROOT / "contracts/schemas/measurement/gate-finding.v1.schema.json"
    def _catalogue(self) -> dict:
        return json.loads(self.CATALOGUE_PATH.read_text(encoding="utf-8"))
    def _finding_schema(self) -> dict:
        return json.loads(self.FINDING_SCHEMA_PATH.read_text(encoding="utf-8"))
    def _record_schema(self) -> dict:
        return json.loads(self.RECORD_SCHEMA_PATH.read_text(encoding="utf-8"))
    def _mutations(self):
        def duplicate_station_id(cat):
            cat["stations"].append(copy.deepcopy(cat["stations"][0]))
            return cat, None

        def marker_shadows_station(cat):
            cat["lifecycle_markers"][0]["marker_id"] = cat["stations"][0]["station_id"]
            return cat, None

        def ambiguous_alias(cat):
            cat["stations"][1]["aliases"].setdefault("mcp_action", []).append("bootstrap")
            return cat, None

        def undeclared_alias_kind(cat):
            cat["stations"][0]["aliases"]["invented_kind"] = ["something"]
            return cat, None

        def undeclared_emitter_station(cat):
            cat["stations"] = [s for s in cat["stations"] if s["station_id"] != "ci"]
            return cat, None

        def undeclared_phase_marker(cat):
            cat["lifecycle_markers"] = [
                m for m in cat["lifecycle_markers"] if m["marker_id"] != "pre_merge"
            ]
            return cat, None

        def unresolved_routing_stage(cat):
            config = (REPO_ROOT / ".ground-control.yaml").read_text(encoding="utf-8")
            # Append a stage the catalogue cannot resolve, inside the routing.stages block.
            config = config.replace(
                "  stages:\n",
                "  stages:\n    invented_stage:\n      tier: low\n",
                1,
            )
            return cat, config

        return {
            "measurement-catalogue-duplicate-id": duplicate_station_id,
            "measurement-catalogue-station-marker-overlap": marker_shadows_station,
            "measurement-catalogue-ambiguous-alias": ambiguous_alias,
            "measurement-catalogue-undeclared-alias-kind": undeclared_alias_kind,
            "measurement-catalogue-emitter-drift": undeclared_emitter_station,
            "measurement-catalogue-phase-marker-drift": undeclared_phase_marker,
            "measurement-catalogue-routing-stage-drift": unresolved_routing_stage,
        }
    def _mirror(self, tmp: str, catalogue: dict, config: str | None = None) -> Path:
        """Mirror the repo paths the check reads, with a mutated catalogue and optional config."""
        root = Path(tmp)
        (root / "contracts/measurement").mkdir(parents=True)
        (root / "contracts/measurement/gc-station-catalogue-v2.json").write_text(
            json.dumps(catalogue), encoding="utf-8"
        )
        (root / "mcp/ground-control").mkdir(parents=True)
        for name in ("gc-implement-mechanical.js", "lib.js", "gate-finding-adapters.js"):
            shutil.copy(
                REPO_ROOT / "mcp/ground-control" / name,
                root / "mcp/ground-control" / name,
            )
        shutil.copytree(REPO_ROOT / "mcp/ground-control/lib", root / "mcp/ground-control/lib")
        shutil.copytree(
            REPO_ROOT / "mcp/ground-control/implement", root / "mcp/ground-control/implement"
        )
        if config is None:
            shutil.copy(REPO_ROOT / ".ground-control.yaml", root / ".ground-control.yaml")
        else:
            (root / ".ground-control.yaml").write_text(config, encoding="utf-8")
        return root
    def test_station_catalogue_ids_and_aliases_are_unique(self):
        catalogue = self._catalogue()
        station_ids = [s["station_id"] for s in catalogue["stations"]]
        marker_ids = [m["marker_id"] for m in catalogue["lifecycle_markers"]]

        self.assertEqual(len(station_ids), len(set(station_ids)))
        self.assertEqual(len(marker_ids), len(set(marker_ids)))
        self.assertEqual(set(station_ids) & set(marker_ids), set())
        self.assertEqual(run_measurement_catalogue_check(), [])
    def test_every_catalogue_violation_code_fires_on_its_mutation(self):
        for code, mutate in self._mutations().items():
            with self.subTest(code=code):
                catalogue, config = mutate(self._catalogue())
                with tempfile.TemporaryDirectory() as tmp:
                    root = self._mirror(tmp, catalogue, config=config)
                    violations = run_measurement_catalogue_check(root=root)
                self.assertIn(
                    code,
                    {v.code for v in violations},
                    f"{code} never fired; its detection may be scanning nothing",
                )
    def test_emitter_drift_fires_for_a_station_emitted_from_the_review_path(self):
        """The same drift code must fire whichever emitter source declares the station.

        `_mutations` holds one case per code, so this pins the second source explicitly:
        dropping a station lib.js emits has to fail exactly as dropping one
        gc-implement-mechanical.js emits does.
        """
        catalogue = self._catalogue()
        catalogue["stations"] = [
            s for s in catalogue["stations"] if s["station_id"] != "codex_review"
        ]

        with tempfile.TemporaryDirectory() as tmp:
            violations = run_measurement_catalogue_check(root=self._mirror(tmp, catalogue))

        self.assertIn("measurement-catalogue-emitter-drift", {v.code for v in violations})
    def test_catalogue_check_reports_a_missing_catalogue(self):
        with tempfile.TemporaryDirectory() as tmp:
            violations = run_measurement_catalogue_check(root=Path(tmp))

        self.assertTrue(any(v.code == "measurement-catalogue-missing" for v in violations))
    def test_catalogue_check_reports_malformed_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "contracts/measurement").mkdir(parents=True)
            (root / "contracts/measurement/gc-station-catalogue-v2.json").write_text(
                "{ not json", encoding="utf-8"
            )
            violations = run_measurement_catalogue_check(root=root)

        self.assertTrue(any(v.code == "measurement-catalogue-json-invalid" for v in violations))
    def test_v1_catalogue_stays_frozen_as_published(self):
        """The v1 catalogue is a published contract version, so v2 is a successor file.

        Editing v1 in place would silently retroactively change what every record
        already written against it means. The nine stations it published are exactly
        the nine it must still publish.
        """
        v1 = json.loads(self.FROZEN_V1_CATALOGUE_PATH.read_text(encoding="utf-8"))

        self.assertEqual(v1["schema_version"], "gc.measurement.station-catalogue/v1")
        self.assertEqual(
            {s["station_id"] for s in v1["stations"]},
            {
                "issue_branch_resolution",
                "architecture_preflight",
                "completion_gate",
                "codex_review",
                "test_quality_review",
                "precommit",
                "git_publish",
                "ci",
                "sonarcloud",
            },
        )
    def test_v2_catalogue_is_a_superset_of_v1(self):
        """A successor catalogue may add stations; it may not drop or rename one.

        Dropping an id would orphan every record that already carries it, which is the
        breaking change ADR-082's versioning exists to make visible.
        """
        v1 = json.loads(self.FROZEN_V1_CATALOGUE_PATH.read_text(encoding="utf-8"))
        v2 = self._catalogue()

        self.assertEqual(v2["schema_version"], "gc.measurement.station-catalogue/v2")
        for key in ("stations", "lifecycle_markers"):
            id_field = "station_id" if key == "stations" else "marker_id"
            self.assertLessEqual(
                {e[id_field] for e in v1[key]},
                {e[id_field] for e in v2[key]},
                f"v2 dropped a published {id_field}",
            )
    def test_v2_catalogue_declares_the_sub_gate_stations(self):
        """SpotBugs, policy, and Vale are separate gates with separate rework profiles.

        Folding them into `completion_gate` would make per-gate first-pass yield
        meaningless for exactly the three gates issue #1355 names.
        """
        station_ids = {s["station_id"] for s in self._catalogue()["stations"]}

        for station_id in ("spotbugs", "policy", "vale"):
            self.assertIn(station_id, station_ids)
    def test_violation_without_details_constructs_and_renders(self):
        """A violation that carries no detail lines must still be constructible.

        `details` was a required field, so every call site that omitted it raised
        TypeError at the exact moment it tried to report a violation: the policy gate
        crashed instead of failing cleanly. Seven call sites were in that shape.
        """
        violation = Violation(code="example-code", message="Example message.")

        self.assertEqual(violation.details, [])
        self.assertEqual(violation.render(), "[example-code] Example message.")
    def test_every_declared_violation_code_has_a_mutation_case(self):
        """Structural guard: a new violation code without a mutation is a test failure.

        This is what closes the category rather than the six instances that opened it —
        without it the same gap reappears the next time a code is added.
        """
        source = "\n".join(
            f.read_text(encoding="utf-8") for f in sorted((REPO_ROOT / "tools/policy").glob("*.py"))
        )
        declared = set(re.findall(r'code="(measurement-catalogue-[a-z-]+)"', source))
        self.assertGreaterEqual(len(declared), 7, "code extraction found too little to be meaningful")

        # The two file-guard codes are covered by their own dedicated tests above.
        file_guards = {"measurement-catalogue-missing", "measurement-catalogue-json-invalid"}
        self.assertEqual(declared - file_guards - set(self._mutations()), set())
    def test_station_catalogue_separates_stations_from_lifecycle_markers(self):
        catalogue = self._catalogue()
        # A station says what it inspects; a marker says what it records. ready_for_review
        # and post_merge inspect nothing, so classifying them as stations would manufacture
        # pass/fail data for a gate that does not exist.
        for station in catalogue["stations"]:
            self.assertTrue(station["inspects"].strip())
        marker_ids = {m["marker_id"] for m in catalogue["lifecycle_markers"]}
        self.assertIn("ready_for_review", marker_ids)
        self.assertIn("post_merge", marker_ids)
        self.assertIn("traceability_reconciled", marker_ids)

        station_ids = {s["station_id"] for s in catalogue["stations"]}
        self.assertEqual(station_ids & marker_ids, set())
    def test_station_catalogue_covers_live_emitter_station_ids(self):
        source = "\n".join(
            [(REPO_ROOT / "mcp/ground-control/gc-implement-mechanical.js").read_text(encoding="utf-8")]
            + [
                f.read_text(encoding="utf-8")
                for f in sorted((REPO_ROOT / "mcp/ground-control/implement").glob("*.js"))
            ]
        )
        tables = re.findall(
            r"(?:STATION_BY_[A-Z_]+|MARKER_BY_[A-Z_]+)\s*=\s*Object\.freeze\(\{(.*?)\}\)",
            source,
            re.DOTALL,
        )
        # Both tables, because `readiness` and `post_merge` are still emitted — as the
        # lifecycle markers they are, rather than as stations with unobservable verdicts.
        self.assertGreaterEqual(len(tables), 2, "station/marker tables must be discoverable")
        emitted: set[str] = set()
        for block in tables:
            emitted |= set(re.findall(r"^\s*[\"a-z_-]+\s*:\s*\"([a-z0-9_]+)\"\s*,?\s*$", block, re.MULTILINE))
        emitted |= set(re.findall(r"\.station\(\s*\"([a-z_]+)\"", source))
        emitted |= set(re.findall(r"stationId:\s*\"([a-z0-9_]+)\"", source))

        # Guards the guard: an extraction that finds nothing would make the check vacuous.
        self.assertGreaterEqual(len(emitted), 7)

        catalogue = self._catalogue()
        declared = {s["station_id"] for s in catalogue["stations"]} | {
            m["marker_id"] for m in catalogue["lifecycle_markers"]
        }
        self.assertEqual(emitted - declared, set())
    def test_station_catalogue_covers_review_emitter_station_ids(self):
        """The review stations are emitted from lib.js, so the drift scan must read it.

        Scanning only gc-implement-mechanical.js left every station id emitted from the
        review path invisible to the gate — a live emitter the gate does not name is a
        hole in the gate (ADR-090 section 8).
        """
        source = "\n".join(
            f.read_text(encoding="utf-8")
            for f in sorted((REPO_ROOT / "mcp/ground-control/lib").glob("*.js"))
        )
        # The review path names its stations through a lookup table rather than an inline
        # literal, so a scan that only understood `.station("x")` would read zero and pass
        # vacuously — which is precisely how this hole stayed open.
        emitted: set[str] = set(re.findall(r"\.station\(\s*\"([a-z_]+)\"", source))
        for block in re.findall(
            r"(?:STATION_BY_[A-Z_]+|MARKER_BY_[A-Z_]+|[A-Z_]*STATION[A-Z_]*)\s*=\s*Object\.freeze\(\{(.*?)\}\)",
            source,
            re.DOTALL,
        ):
            emitted |= set(re.findall(r"^\s*[\"a-z_-]+\s*:\s*\"([a-z0-9_]+)\"\s*,?\s*$", block, re.MULTILINE))

        # Guards the guard: if the review path stops emitting, this check must fail
        # loudly rather than pass by scanning an empty set.
        self.assertGreaterEqual(len(emitted), 2)

        catalogue = self._catalogue()
        declared = {s["station_id"] for s in catalogue["stations"]} | {
            m["marker_id"] for m in catalogue["lifecycle_markers"]
        }
        self.assertEqual(emitted - declared, set())
        self.assertIn("codex_review", emitted)
        self.assertIn("test_quality_review", emitted)
    def test_station_catalogue_covers_issue_thread_phase_markers(self):
        source = "\n".join(
            f.read_text(encoding="utf-8")
            for f in sorted((REPO_ROOT / "mcp/ground-control/lib").glob("*.js"))
        )
        written = set(re.findall(r"gc:phase\s+phase=\\?\"([a-z_]+)\\?\"", source))
        written |= {m for m in re.findall(r"phase:\s*\"([a-z_]+)\"", source)}
        self.assertGreaterEqual(len(written), 4)

        catalogue = self._catalogue()
        resolvable = {s["station_id"] for s in catalogue["stations"]} | {
            m["marker_id"] for m in catalogue["lifecycle_markers"]
        }
        for entry in catalogue["stations"] + catalogue["lifecycle_markers"]:
            resolvable |= set(entry["aliases"].get("issue_thread_marker", []))

        self.assertEqual(written - resolvable, set())
    def test_station_catalogue_covers_routing_stages(self):
        catalogue = self._catalogue()
        excused = {e["adr036_stage"] for e in catalogue["non_station_stages"]}
        aliased: set[str] = set()
        for entry in catalogue["stations"] + catalogue["lifecycle_markers"]:
            aliased |= set(entry["aliases"].get("adr036_stage", []))

        # Scope to the routing.stages block: other 4-space key lists exist in this file
        # (architecture.vocabulary among them), and sweeping the whole file would compare
        # the catalogue against keys that are not stages at all.
        config = (REPO_ROOT / ".ground-control.yaml").read_text(encoding="utf-8")
        block = re.search(r"^  stages:\s*$(.*?)(?=^  \S|\Z)", config, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(block, "routing.stages must be discoverable or this check scans nothing")
        stages = set(re.findall(r"^    ([a-z0-9_]+):\s*$", block.group(1), re.MULTILINE))
        # Floor guards the guard: an extraction that silently matches nothing would make
        # the set-difference assertion below trivially true.
        self.assertGreaterEqual(len(stages), 20)

        self.assertEqual(stages - aliased - excused, set())
    def test_measurement_record_outcome_axes_share_no_value(self):
        defs = self._record_schema()["$defs"]
        operation = set(defs["OperationOutcome"]["enum"])
        station = set(defs["StationResult"]["enum"])
        run_state = set(defs["RunState"]["enum"])
        run_outcome = set(defs["RunOutcome"]["enum"])

        # ADR-090 section 3 by construction: `pass` can never reach the operation axis and
        # `ok` can never reach the station axis, so no aggregate can read a tool succeeding
        # as a gate passing.
        self.assertEqual(operation & station, set())
        self.assertEqual(operation & run_state, set())
        self.assertEqual(station & run_state, set())
        self.assertEqual(station & run_outcome, set())
        self.assertIn("pass", station)
        self.assertNotIn("pass", operation)
        self.assertIn("ok", operation)
        self.assertNotIn("ok", station)
        self.assertIn("unobserved", station)
    def test_measurement_record_declares_three_separate_outcome_properties(self):
        schema = self._record_schema()
        properties = schema["properties"]
        for axis in ("operationOutcome", "stationResult", "runState", "runOutcome"):
            self.assertIn(axis, properties)
        # One shared `outcome` field would let the axes be substituted for one another.
        self.assertNotIn("outcome", properties)
    def test_measurement_record_station_id_resolves_against_catalogue(self):
        schema = self._record_schema()
        self.assertIn("stationId", schema["properties"])
        catalogue = self._catalogue()
        self.assertTrue(catalogue["stations"])
        for station in catalogue["stations"]:
            self.assertRegex(station["station_id"], r"^[a-z][a-z0-9_]*$")
    def test_gate_finding_schema_admits_no_prose_fields(self):
        """A measurement projection must not become a second copy of the issue record.

        Admitting a body or a path would leak review prose and source content into a
        reporting store and set up a rival to ADR-029's durable narrative.
        """
        schema = self._finding_schema()

        self.assertFalse(schema["additionalProperties"])
        forbidden = {
            "title",
            "body",
            "message",
            "description",
            "remediation",
            "rationale",
            "path",
            "file",
            "line",
            "snippet",
            "output",
            "stackTrace",
            "sweepEvidence",
            "instances",
        }
        self.assertEqual(forbidden & set(schema["properties"]), set())
    def test_gate_finding_schema_makes_unattestable_dimensions_optional(self):
        """Codex findings carry no severity; a required field would force a guess."""
        schema = self._finding_schema()

        for optional in ("category", "severity", "classification"):
            self.assertIn(optional, schema["properties"])
            self.assertNotIn(optional, schema["required"])
    def test_gate_finding_disposition_matches_adr029_outcomes(self):
        """Measurement reuses ADR-029's outcomes rather than growing a second vocabulary.

        `defer` is absent by construction: ADR-029 forbids deferral, so a schema that
        could express it would let the projection record something the workflow bans.
        """
        disposition = set(self._finding_schema()["$defs"]["Disposition"]["enum"])

        self.assertEqual(disposition, {"open", "fixed", "wontfix", "not-applicable"})
        self.assertNotIn("defer", disposition)
    def test_measurement_record_requires_only_provenance_fields(self):
        schema = self._record_schema()
        # Absence over synthesis: an emitter that cannot attest a dimension omits it rather
        # than defaulting it into something that reads as a real observation.
        self.assertEqual(sorted(schema["required"]), ["emitter", "measurementVersion", "observedAt"])
    def test_measurement_schemas_declare_enforced_invariants(self):
        for path in (self.RECORD_SCHEMA_PATH, self.CATALOGUE_SCHEMA_PATH):
            schema = json.loads(path.read_text(encoding="utf-8"))
            invariants = schema["x-ground-control-invariants"]
            self.assertTrue(invariants)
            for entry in invariants:
                self.assertTrue(entry["enforcedBy"])
