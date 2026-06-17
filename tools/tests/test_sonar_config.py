import pathlib
import re
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
BACKEND_GRADLE = REPO_ROOT / "backend" / "build.gradle.kts"
SONAR_PROJECT = REPO_ROOT / "sonar-project.properties"


class SonarConfigTest(unittest.TestCase):
    def test_gradle_sonar_targets_active_autarchy_ai_project(self) -> None:
        gradle_config = BACKEND_GRADLE.read_text(encoding="utf-8")

        self.assertIn('property("sonar.projectKey", "autarchy-ai_Ground-Control")', gradle_config)
        self.assertIn('property("sonar.organization", "autarchy-ai")', gradle_config)
        self.assertNotIn("KeplerOps_Ground-Control", gradle_config)
        self.assertNotIn('property("sonar.organization", "keplerops")', gradle_config)
        self.assertNotIn("Brad-Edwards_Ground-Control", gradle_config)
        self.assertNotIn('property("sonar.organization", "brad-edwards")', gradle_config)

    def test_gradle_sonar_scope_is_explicit(self) -> None:
        gradle_config = BACKEND_GRADLE.read_text(encoding="utf-8")

        self.assertRegex(gradle_config, re.compile(r'property\("sonar\.sources",\s*"[^"]+"\)'))
        self.assertRegex(gradle_config, re.compile(r'property\("sonar\.tests",\s*"[^"]+"\)'))
        self.assertIn("backend/bin/**", gradle_config)
        self.assertIn("workflow/releases/**", gradle_config)

    def test_repo_sonar_properties_match_gradle_project_identity(self) -> None:
        properties = SONAR_PROJECT.read_text(encoding="utf-8")

        self.assertIn("sonar.projectKey=autarchy-ai_Ground-Control", properties)
        self.assertIn("sonar.organization=autarchy-ai", properties)
        self.assertNotIn("Brad-Edwards_Ground-Control", properties)
        self.assertNotIn("sonar.organization=brad-edwards", properties)


if __name__ == "__main__":
    unittest.main()
