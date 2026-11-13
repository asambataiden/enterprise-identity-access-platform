import json
import unittest
from pathlib import Path

from verify_github_actions_pins import validate_inventory, validate_repo, validate_workflow


PIN = "a" * 40
REVIEWED = {"schema_version": 1, "actions": {"actions/checkout": {"sha": PIN, "version": "v6"}}}


class ActionPinsTest(unittest.TestCase):
    def test_repository_is_fully_pinned(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual([], validate_repo(root))

    def test_reviewed_pin_is_accepted(self):
        self.assertEqual([], validate_workflow(f"steps:\n  - uses: actions/checkout@{PIN} # v6\n", REVIEWED, "ci.yml"))

    def test_moving_tag_is_rejected(self):
        failures = validate_workflow("steps:\n  - uses: actions/checkout@v6\n", REVIEWED, "ci.yml")
        self.assertTrue(any("non-SHA" in x for x in failures))

    def test_unreviewed_commit_rejected(self):
        failures = validate_workflow(f"steps:\n  - uses: actions/checkout@{'b' * 40}\n", REVIEWED, "ci.yml")
        self.assertTrue(any("differs" in x for x in failures))

    def test_unknown_action_rejected(self):
        failures = validate_workflow(f"steps:\n  - uses: other/example@{PIN}\n", REVIEWED, "ci.yml")
        self.assertTrue(any("missing from reviewed inventory" in x for x in failures))

    def test_tagged_docker_action_rejected(self):
        failures = validate_workflow("steps:\n  - uses: docker://alpine:latest\n", REVIEWED, "ci.yml")
        self.assertTrue(any("immutable digest" in x for x in failures))

    def test_missing_sha_in_manifest_rejected(self):
        invalid = json.loads(json.dumps(REVIEWED))
        invalid["actions"]["actions/checkout"]["sha"] = "v6"
        self.assertTrue(validate_inventory(invalid))

    def test_empty_workflow_does_not_silently_pass(self):
        self.assertTrue(validate_workflow("jobs: {}\n", REVIEWED, "ci.yml"))


if __name__ == "__main__":
    unittest.main()
