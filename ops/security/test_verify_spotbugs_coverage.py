import unittest

from verify_spotbugs_coverage import evaluate, missing_names

XML = """<?xml version="1.0"?>
<BugCollection version="4.10.5">
  <Project><Jar>target/classes</Jar></Project>
  <FindBugsSummary total_classes="28" total_size="900" />
</BugCollection>
"""
LOG = """[INFO] --- spotbugs ---
The following classes needed for analysis were missing:
  toString
  makeConcatWithConstants
[INFO] Done SpotBugs Analysis....
[INFO] No errors/warnings found
"""


class CoverageTest(unittest.TestCase):
    def test_existing_warnings_are_visible_but_accepted(self):
        outcome = evaluate(LOG, XML, ["toString", "makeConcatWithConstants"])
        self.assertEqual(outcome["status"], "pass_with_known_warnings")
        self.assertEqual(outcome["analyzed_classes"], 28)
        self.assertFalse(outcome["new_missing"])

    def test_new_unresolved_reference_is_a_regression(self):
        outcome = evaluate(LOG.replace("  toString", "  unknownTarget"), XML, ["toString", "makeConcatWithConstants"])
        self.assertEqual(outcome["new_missing"], ["unknownTarget"])
        self.assertEqual(outcome["status"], "fail")

    def test_fully_resolved_report_is_clean(self):
        log = "[INFO] Done SpotBugs Analysis....\n[INFO] No errors/warnings found"
        outcome = evaluate(log, XML, ["toString"])
        self.assertEqual(outcome["status"], "pass_clean")
        self.assertEqual(outcome["resolved_since_baseline"], ["toString"])

    def test_report_with_zero_classes_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "No analyzed classes"):
            evaluate(LOG, XML.replace('total_classes="28"', 'total_classes="0"'), [])

    def test_report_without_summary_uses_project_jars(self):
        xml = '<BugCollection><Project><Jar>target/classes</Jar></Project></BugCollection>'
        outcome = evaluate(LOG, xml, ["toString", "makeConcatWithConstants"])
        self.assertIsNone(outcome["analyzed_classes"])
        self.assertEqual(outcome["project_jar_count"], 1)
        self.assertEqual(outcome["report_evidence"], "project_jars")

    def test_report_without_summary_or_jars_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "lacks both class summary"):
            evaluate(LOG, "<BugCollection/>", [])

    def test_missing_completion_marker_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "completion marker"):
            evaluate("No errors/warnings found", XML, [])

    def test_xml_missing_class_is_detected(self):
        report = XML.replace("</BugCollection>", "<Errors><MissingClass>some.example.Foo</MissingClass></Errors></BugCollection>")
        outcome = evaluate(LOG, report, ["toString", "makeConcatWithConstants"])
        self.assertIn("some.example.Foo", outcome["new_missing"])

    def test_timestamped_log_lines_are_parsed(self):
        log = """2026-11-13T13:04:08.3114245Z The following classes needed for analysis were missing:
2026-11-13T13:04:08.3128828Z   mapRow
2026-11-13T13:04:08.3256839Z [INFO] Done SpotBugs Analysis....
2026-11-13T13:04:08.6875541Z [INFO] No errors/warnings found
"""
        self.assertEqual(missing_names(log), {"mapRow"})


if __name__ == "__main__":
    unittest.main()
