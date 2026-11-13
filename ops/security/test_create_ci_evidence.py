import json
import tempfile
import unittest
from pathlib import Path

from create_ci_evidence import make_manifest

SHA = "a" * 40


class EvidenceManifestTest(unittest.TestCase):
    def test_java_manifest_aggregates_tests_without_exposing_report_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            folder = root / "target/surefire-reports"
            folder.mkdir(parents=True)
            (folder / "TEST-Example.xml").write_text(
                '<testsuite tests="3" errors="0" failures="0" skipped="1"><properties>'
                '<property name="sensitive" value="never-export-this-value"/></properties></testsuite>'
            )
            result = make_manifest(root, "java", SHA, "123", "1")
            self.assertEqual(result["test_totals"]["tests"], 3)
            self.assertEqual(result["test_totals"]["skipped"], 1)
            self.assertEqual(len(result["source_digests_sha256"]), 1)
            self.assertNotIn("never-export-this-value", json.dumps(result))
            self.assertEqual(len(next(iter(result["source_digests_sha256"].values()))), 64)

    def test_java_empty_and_failure_reports_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, "No Java test report"):
                make_manifest(root, "java", SHA, "123", "1")
            folder = root / "target/failsafe-reports"
            folder.mkdir(parents=True)
            (folder / "TEST-Failing.xml").write_text(
                '<testsuite tests="1" errors="0" failures="1" skipped="0"/>')
            with self.assertRaisesRegex(ValueError, "failures/errors"):
                make_manifest(root, "java", SHA, "123", "1")

    def test_sast_manifest_records_counts_and_hashes_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "target"
            target.mkdir()
            (target / "spotbugs.xml").write_text('<BugCollection/>')
            (target / "spotbugs-coverage.json").write_text(json.dumps({
                "status": "pass_with_known_warnings", "compiled_classes": 48,
                "known_missing": ["get", "apply"], "new_missing": [],
                "analyzed_classes_reported": None,
                "private_note": "never-export-this-value",
            }))
            result = make_manifest(root, "sast", SHA, "987", "2")
            self.assertEqual(result["coverage"]["known_unresolved_name_count"], 2)
            self.assertEqual(result["coverage"]["compiled_class_inputs"], 48)
            self.assertEqual(len(result["source_digests_sha256"]), 2)
            self.assertNotIn("never-export-this-value", json.dumps(result))
            self.assertNotIn("apply", json.dumps(result))

    def test_new_unresolved_references_fail_sast_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "target"
            target.mkdir()
            (target / "spotbugs.xml").write_text('<BugCollection/>')
            (target / "spotbugs-coverage.json").write_text(json.dumps({
                "status": "fail", "compiled_classes": 48, "new_missing": ["Unexpected"]
            }))
            with self.assertRaisesRegex(ValueError, "Coverage guard not successful"):
                make_manifest(Path(tmp), "sast", SHA, "1", "1")

    def test_invalid_commit_or_run_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid evidence kind or commit SHA"):
            make_manifest(Path("."), "java", "not-a-sha", "1", "1")
        with self.assertRaisesRegex(ValueError, "Invalid GitHub run identity"):
            make_manifest(Path("."), "java", SHA, "0", "1")


if __name__ == "__main__":
    unittest.main()
