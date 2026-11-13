#!/usr/bin/env python3
"""Create a small, nonsecret CI evidence manifest; never archive raw logs or reports."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SHA40 = re.compile(r"^[0-9a-f]{40}$")
KINDS = {"java", "sast"}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def test_totals(directory: Path) -> dict[str, int]:
    files = sorted(directory.glob("TEST-*.xml")) if directory.is_dir() else []
    if not files:
        raise ValueError(f"No test result XML files in {directory}")
    totals = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in files:
        root = ET.parse(path).getroot()
        if root.tag != "testsuite":
            raise ValueError(f"Unexpected test report root in {path.name}: {root.tag}")
        totals["suites"] += 1
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] += int(root.attrib.get(field, "0"))
    if totals["tests"] < 1 or totals["failures"] or totals["errors"]:
        raise ValueError("Test reports empty or contain failures/errors")
    return totals


def make_manifest(root: Path, kind: str, sha: str, run_id: str, attempt: str) -> dict:
    if kind not in KINDS or not SHA40.fullmatch(sha):
        raise ValueError("Invalid evidence kind or commit SHA")
    if not run_id.isdecimal() or not attempt.isdecimal() or int(run_id) < 1 or int(attempt) < 1:
        raise ValueError("Invalid GitHub run identity")
    manifest = {
        "schema_version": 1,
        "kind": kind,
        "commit_sha": sha,
        "workflow_run_id": run_id,
        "workflow_run_attempt": int(attempt),
        "repository": "asambataiden/enterprise-identity-access-platform",
        "source_digests_sha256": {},
    }
    if kind == "java":
        aggregate = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for folder in ("target/surefire-reports", "target/failsafe-reports"):
            location = root / folder
            if not location.is_dir() or not list(location.glob("TEST-*.xml")):
                # No Failsafe tests is not an error if Surefire did run.
                continue
            counts = test_totals(location)
            for key, value in counts.items():
                aggregate[key] += value
            for report in sorted(location.glob("TEST-*.xml")):
                manifest["source_digests_sha256"][str(report.relative_to(root))] = digest(report)
        if aggregate["tests"] < 1:
            raise ValueError("No Java test report evidence found")
        manifest["test_totals"] = aggregate
    else:
        report = root / "target/spotbugs.xml"
        coverage = root / "target/spotbugs-coverage.json"
        for path in (report, coverage):
            if not path.is_file():
                raise ValueError(f"Missing SAST evidence: {path.name}")
        doc = ET.parse(report).getroot()
        if doc.tag != "BugCollection":
            raise ValueError("Unexpected SAST report")
        summary = json.loads(coverage.read_text(encoding="utf-8"))
        if summary.get("status") not in ("pass_clean", "pass_with_known_warnings"):
            raise ValueError("Coverage guard not successful")
        if summary.get("new_missing") or int(summary.get("compiled_classes", 0)) < 1:
            raise ValueError("Missing-reference regression or empty compiled input")
        manifest["coverage"] = {
            "status": summary["status"],
            "compiled_class_inputs": int(summary["compiled_classes"]),
            "known_unresolved_name_count": len(summary.get("known_missing", [])),
            "analyzed_classes_reported": summary.get("analyzed_classes_reported"),
        }
        for path in (report, coverage):
            manifest["source_digests_sha256"][str(path.relative_to(root))] = digest(path)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", required=True, choices=sorted(KINDS))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", ""))
    parser.add_argument("--attempt", default=os.environ.get("GITHUB_RUN_ATTEMPT", ""))
    args = parser.parse_args()
    try:
        manifest = make_manifest(args.root.resolve(), args.kind, args.sha, args.run_id, args.attempt)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Evidence manifest created: {args.out} (no raw reports or logs included)")
        return 0
    except (ValueError, OSError, ET.ParseError, TypeError, KeyError) as exc:
        print(f"Evidence creation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
