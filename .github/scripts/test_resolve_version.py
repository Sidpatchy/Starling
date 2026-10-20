"""Regression tests for the release-version resolver used by both workflows."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("resolve-version.sh")


class ResolveVersionTest(unittest.TestCase):
    def resolve(
        self,
        event_name,
        ref_type,
        ref_name,
        properties="starlingVersion=0.1.0-alpha.2\n",
    ):
        with tempfile.TemporaryDirectory(prefix="starling-version-test-") as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text(properties, encoding="utf-8")
            output = root / "github-output"
            output.write_text("previous=value\n", encoding="utf-8")
            environment = os.environ.copy()
            environment.update(
                GITHUB_EVENT_NAME=event_name,
                GITHUB_REF_TYPE=ref_type,
                GITHUB_REF_NAME=ref_name,
                GITHUB_OUTPUT=str(output),
            )
            result = subprocess.run(
                ["bash", str(SCRIPT.resolve())], cwd=root, env=environment,
                text=True, capture_output=True, timeout=10,
            )
            return result, output.read_text(encoding="utf-8")

    def test_tags_with_or_without_v_prefix_use_the_tag_version(self):
        for tag in ["v1.2.3", "1.2.3"]:
            with self.subTest(tag=tag):
                result, output = self.resolve("release", "tag", tag)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(
                    output,
                    f"previous=value\napp_version=1.2.3\npackage_version=1.2.3\nartifact_version={tag}\n",
                )

    def test_prerelease_labels_are_retained_only_in_app_version(self):
        result, output = self.resolve("release", "tag", "v0.2.0-alpha.3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            output,
            "previous=value\napp_version=0.2.0-alpha.3\npackage_version=0.2.0\n"
            "artifact_version=v0.2.0-alpha.3\n",
        )

    def test_branch_builds_use_the_last_gradle_property(self):
        result, output = self.resolve(
            "workflow_dispatch",
            "branch",
            "main",
            "starlingVersion=0.1.0\nstarlingVersion=0.3.0-beta.1\n",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            output,
            "previous=value\napp_version=0.3.0-beta.1\npackage_version=0.3.0\n"
            "artifact_version=0.3.0-beta.1_SNAPSHOT\n",
        )

    def test_manual_build_on_a_tag_is_still_a_snapshot(self):
        result, output = self.resolve("workflow_dispatch", "tag", "v1.2.3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            output,
            "previous=value\napp_version=0.1.0-alpha.2\npackage_version=0.1.0\n"
            "artifact_version=0.1.0-alpha.2_SNAPSHOT\n",
        )

    def test_invalid_tags_do_not_emit_version_outputs(self):
        for tag in ["", "vv1.2.3", "1.2", "1.2.3-", "1.2.3+build", "v1.2.3\nmalicious=value"]:
            with self.subTest(tag=tag):
                result, output = self.resolve("release", "tag", tag)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("must use semantic version", result.stderr)
                self.assertEqual(output, "previous=value\n")

    def test_missing_or_invalid_gradle_versions_do_not_emit_outputs(self):
        for properties in ["", "starlingVersion=invalid\n"]:
            with self.subTest(properties=properties):
                result, output = self.resolve("workflow_dispatch", "branch", "main", properties)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output, "previous=value\n")


if __name__ == "__main__":
    unittest.main()
