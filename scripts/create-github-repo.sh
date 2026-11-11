#!/usr/bin/env bash
set -Eeuo pipefail
repo='asambataiden/enterprise-identity-access-platform'
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v gh >/dev/null || fail 'GitHub CLI (gh) required'
command -v git >/dev/null || fail 'git required'
[[ ! -e .git ]] || fail 'Already a git repository; refusing to overwrite or re-initialize'
[[ -f pom.xml && -f docs/use-cases/UC-001-application-access-request.md ]] || fail 'Run from the extracted Milestone 00 project root'
login="$(gh api user --jq .login)" || fail 'GitHub CLI not authenticated'
[[ "$login" == asambataiden ]] || fail "Wrong authenticated GitHub account: $login"
if gh repo view "$repo" >/dev/null 2>&1; then fail "Repository already exists: $repo"; fi
printf 'About to create PUBLIC repository %s and publish these files.\n' "$repo"
printf 'Review source and secret hygiene first. Type CREATE PUBLIC IAM to continue: '
read -r confirmation
[[ "$confirmation" == 'CREATE PUBLIC IAM' ]] || fail 'Cancelled'
git init -b main
git add .
git diff --cached --check
git status --short
printf 'Confirm staged files contain no secrets. Type PUSH REVIEWED to continue: '
read -r second
[[ "$second" == 'PUSH REVIEWED' ]] || fail 'Stopped before push'
git commit -m 'chore: bootstrap enterprise IAM capstone milestone 00'
gh repo create "$repo" --public --source=. --remote=origin --push
visibility="$(gh repo view "$repo" --json visibility --jq .visibility)"
[[ "$visibility" == PUBLIC ]] || fail "Unexpected repository visibility: $visibility"
printf 'Created and verified: https://github.com/%s\n' "$repo"
