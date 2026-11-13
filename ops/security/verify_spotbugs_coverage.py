#!/usr/bin/env python3
"""Fail closed if SpotBugs coverage warnings grow beyond a reviewed baseline.

Only the existing warning inventory is accepted. This does not make the
unresolved references harmless; it prevents silent regression while the
underlying bytecode/auxiliary-classpath issue is investigated.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BANNER = "The following classes needed for analysis were missing:"
# Strip GitHub Actions timestamp prefixes and ANSI codes (if present).
ANSI = re.compile(r"\x1b\[[0-9;]*m")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\S+\s+")
NAME = re.compile(r"^[A-Za-z_$][\w$]*(?:[./][A-Za-z_$][\w$]*)*$")


def normalized(line: str) -> str:
    return TIMESTAMP.sub("", ANSI.sub("", line)).strip()


def missing_names(log: str) -> set[str]:
    """Extract names printed in SpotBugs' 'classes needed' warning section."""
    found: set[str] = set()
    in_section = False
    for line in log.splitlines():
        entry = normalized(line)
        if entry == BANNER:
            in_section = True
            continue
        if in_section:
            if entry and NAME.fullmatch(entry):
                found.add(entry)
                continue
            in_section = False
    return found


def evaluate(log: str, xml: str, baseline: list[str]) -> dict:
    if "Done SpotBugs Analysis" not in log:
        raise ValueError("SpotBugs completion marker not found in the Maven log")
    if "No errors/warnings found" not in log:
        raise ValueError("SpotBugs check success marker not found")
    root = ET.fromstring(xml)
    if root.tag != "BugCollection":
        raise ValueError(f"Unexpected SpotBugs report root: {root.tag}")
    summary = root.find("FindBugsSummary")
    # The plugin can emit the minimal XML format without FindBugsSummary.
    # Project/Jar entries identify bytecode artifacts supplied to the analyzer.
    # They are an input-coverage signal, NOT a count of classes analyzed.
    project_jars = [
        (node.text or "").strip()
        for node in root.findall("./Project/Jar")
        if (node.text or "").strip()
    ]
    analyzed = None
    if summary is not None:
        analyzed = int(summary.attrib.get("total_classes", "0"))
        if analyzed < 1:
            raise ValueError("No analyzed classes in SpotBugs report")
    elif not project_jars:
        raise ValueError("SpotBugs report lacks both class summary and analyzed-project Jar entries")
    actual = missing_names(log)
    # Some versions also include fully qualified missing classes in the XML.
    for element in root.findall(".//MissingClass"):
        name = (element.text or "").strip()
        if name:
            actual.add(name)
    allowed = set(baseline)
    newly_missing = sorted(actual - allowed)
    return {
        "analyzed_classes": analyzed,
        "project_jar_count": len(project_jars),
        "report_evidence": "class_summary" if analyzed is not None else "project_jars",
        "known_missing": sorted(actual & allowed),
        "new_missing": newly_missing,
        "resolved_since_baseline": sorted(allowed - actual),
        "status": "fail" if newly_missing else "pass_with_known_warnings" if actual else "pass_clean",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    args = parser.parse_args()
    try:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))["allowed_missing"]
        if not isinstance(baseline, list) or not all(isinstance(x, str) for x in baseline):
            raise ValueError("Baseline allowed_missing must be a list of strings")
        result = evaluate(
            args.log.read_text(encoding="utf-8"),
            args.xml.read_text(encoding="utf-8"),
            baseline,
        )
    except (OSError, ET.ParseError, ValueError, TypeError, KeyError) as exc:
        print(f"SpotBugs coverage verification failed: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(result, indent=2, sort_keys=True))
    if result["new_missing"]:
        print("NEW unresolved SpotBugs references; investigate classpath/bytecode support.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
