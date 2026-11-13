#!/usr/bin/env python3
"""Validate exact GitHub Actions commit pins against a reviewed inventory.

The inventory is a review-controlled allowlist; this script checks consistency,
not upstream provenance or whether a published action is malicious.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ACTION = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)", re.MULTILINE)
SHA = re.compile(r"^[0-9a-f]{40}$")
WORKFLOWS = (".github/workflows/java-ci.yml", ".github/workflows/security-ci.yml")


def validate_inventory(inventory: dict) -> list[str]:
    failures = []
    if inventory.get("schema_version") != 1:
        failures.append("unsupported inventory schema_version")
    actions = inventory.get("actions")
    if not isinstance(actions, dict) or not actions:
        failures.append("inventory actions must be a populated object")
        return failures
    for action, details in actions.items():
        if not isinstance(details, dict) or not SHA.fullmatch(str(details.get("sha", ""))):
            failures.append(f"{action}: missing or invalid full commit SHA")
        if not isinstance(details, dict) or not details.get("version"):
            failures.append(f"{action}: missing documented version")
    return failures


def validate_workflow(content: str, inventory: dict, name: str) -> list[str]:
    failures = []
    refs = ACTION.findall(content)
    if not refs:
        return [f"{name}: no GitHub Actions 'uses' found"]
    allowed = inventory["actions"]
    for ref in refs:
        if ref.startswith("./"):
            # Local actions are repository-controlled and need independent review.
            continue
        if ref.startswith("docker://"):
            failures.append(f"{name}: docker action must use immutable digest, not tag: {ref}")
            continue
        action, separator, sha = ref.partition("@")
        if not separator or not SHA.fullmatch(sha):
            failures.append(f"{name}: unpinned or non-SHA action: {ref}")
            continue
        expected = allowed.get(action)
        if expected is None:
            failures.append(f"{name}: action missing from reviewed inventory: {action}")
        elif sha != expected["sha"]:
            failures.append(f"{name}: pinned SHA differs from reviewed inventory: {action}")
    return failures


def validate_repo(root: Path) -> list[str]:
    try:
        inventory = json.loads((root / "config/github-actions-pins.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"cannot read action inventory: {exc}"]
    failures = validate_inventory(inventory)
    if failures:
        return failures
    for relative in WORKFLOWS:
        try:
            content = (root / relative).read_text(encoding="utf-8")
        except OSError as exc:
            failures.append(f"{relative}: {exc}")
            continue
        failures.extend(validate_workflow(content, inventory, relative))
    return failures


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    failures = validate_repo(root)
    if failures:
        for failure in failures:
            print(f"Action supply-chain policy violation: {failure}", file=sys.stderr)
        return 1
    print("Action supply-chain policy: all third-party uses match reviewed immutable SHA pins.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
