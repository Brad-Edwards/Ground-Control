import copy
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.policy_fixtures import PolicyChecksFixture
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

class ControllerContracts2ChecksTest(PolicyChecksFixture):
    def test_controller_webmvctest_no_false_positive_on_same_name_collision(self):
        """A controller and its real companion (resolved by FQCN) satisfy the check
        even when another package has a same-named controller + test (issue #1167)."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            audit_controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audit/AuditController.java",
                "package com.keplerops.groundcontrol.api.audit;\nclass AuditController {}\n",
            )
            self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audits/AuditController.java",
                "package com.keplerops.groundcontrol.api.audits;\nclass AuditController {}\n",
            )
            audit_trail_test = self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditTrailControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audit.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditTrailControllerTest {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audits.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditControllerTest {}\n",
            )
            violations = run_controller_contracts(
                [audit_controller, audit_trail_test],
                root=root,
            )
            codes = {item.code for item in violations}
            self.assertNotIn(
                "controller-webmvctest-update",
                codes,
                "real companion AuditTrailControllerTest must satisfy api/audit/AuditController",
            )
    def test_controller_webmvctest_update_still_fires_without_companion_change(self):
        """Changing a controller without touching its real companion still fails
        (no regression in the real signal) despite a same-named test in another package."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audit/AuditController.java",
                "package com.keplerops.groundcontrol.api.audit;\nclass AuditController {}\n",
            )
            audits_controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/audits/AuditController.java",
                "package com.keplerops.groundcontrol.api.audits;\nclass AuditController {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditTrailControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audit.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditTrailControllerTest {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\n"
                "import com.keplerops.groundcontrol.api.audits.AuditController;\n"
                "@WebMvcTest(AuditController.class)\nclass AuditControllerTest {}\n",
            )
            violations = run_controller_contracts([audits_controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-update", codes)
    def test_controller_webmvctest_missing_when_no_slice_anywhere(self):
        """A controller with no @WebMvcTest slice referencing it raises -missing."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                "package com.keplerops.groundcontrol.api.foo;\nclass FooController {}\n",
            )
            violations = run_controller_contracts([controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-missing", codes)
    def test_controller_webmvctest_annotation_when_stem_test_is_not_a_slice(self):
        """A same-stem <Controller>Test.java that is not a @WebMvcTest raises -annotation."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = Path(tmp_dir)
            controller = self._write_file(
                root,
                "backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java",
                "package com.keplerops.groundcontrol.api.foo;\nclass FooController {}\n",
            )
            self._write_file(
                root,
                "backend/src/test/java/com/keplerops/groundcontrol/unit/api/FooControllerTest.java",
                "package com.keplerops.groundcontrol.unit.api;\nclass FooControllerTest {}\n",
            )
            violations = run_controller_contracts([controller], root=root)
            codes = {item.code for item in violations}
            self.assertIn("controller-webmvctest-annotation", codes)
