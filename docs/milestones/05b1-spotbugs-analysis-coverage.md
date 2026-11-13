# Milestone 05B-1 — SpotBugs analysis coverage and warning-regression gate

## Goal
Make the security-analysis blind spot observable, while preserving the existing 05A Java SAST gate and avoiding speculative production changes. **This increment does not claim to fix the analyzer's unresolved-reference warnings.**

## Initial evidence (2026-11-13)
A successful SpotBugs + Find Security Bugs job from PR #42 logged:
- Analyzer: \`spotbugs-maven-plugin:4.10.5.0\`, Find Security Bugs 1.12.0, Java 25
- "The following classes needed for analysis were missing:" followed by nine names: \`toString\`, \`hashCode\`, \`equals\`, \`mapRow\`, \`customize\`, \`makeConcatWithConstants\`, \`apply\`, \`test\`, \`get\`
- "Done SpotBugs Analysis", "BugInstance size is 0", "Error size is 0", and "No errors/warnings found"

Some names resemble method names (including lambda/invokedynamic call sites). The diagnostic does **not** by itself prove that nine proper application classes were missing. Conversely, the green \`spotbugs:check\` result does not prove complete dependency/bytecode analysis.

## Implementation

\`\`\`mermaid
flowchart TD
    A[PR security job] --> B[Python parser unit tests]
    B --> C[Maven package + spotbugs:check]
    C -->|failure| D[Reject PR]
    C -->|success| E[Preserve complete Maven output]
    E --> F[Read target/spotbugs.xml]
    F --> G{Analysis summary exists with at least one analyzed class?}
    G -->|No| D
    G -->|Yes| H[Compare missing references to baseline inventory]
    H -->|New unresolved name| D
    H -->|Known warnings only / none| I[Pass with explicit warning record]
\`\`\`

- \`ops/security/verify_spotbugs_coverage.py\`: parses the SpotBugs warning section and XML report. Requires a completed analysis and successful SpotBugs check, a well-formed XML report, and a positive analyzed-class count.
- \`config/spotbugs-missing-baseline.json\`: nine **explicit known unresolved reference names** recorded from a previous successful CI run. They are a **technical-debt inventory, not a suppression list for vulnerability findings**.
- \`ops/security/test_verify_spotbugs_coverage.py\`: parser tests for known and new names, malformed or empty analysis, XML-only missing references, and timestamped runner logs.
- \`.github/workflows/security-ci.yml\`: keeps \`spotbugs:check\` as the security-finding gate; independently rejects *new* missing references or absent/empty SpotBugs report. The Maven log remains visible in the CI job.

No runtime dependency, SQL migration, IAM policy, production authentication code or exception rule is modified. Existing \`config/spotbugs-exclude.xml\` remains unchanged and must be reviewed separately.

## Important limitation
This is a **non-regression gate**. Known warnings remain and are allowed **temporarily**; the gate does not show that every method, class or taint flow has been analyzed. It does not quantify the fidelity of SAST coverage. A future investigation should reproduce the warning with \`mvn clean package spotbugs:spotbugs\` and then compare JDK 25 bytecode, the Maven auxiliary classpath, lambda/record/invokedynamic handling and the SpotBugs/Find Security Bugs versions before deciding on an upgrade or change.

Do not blindly add a new name to the baseline to make CI green. Instead record:
1. Newly unresolved symbol, owning bytecode/class if known, and relevant call site.
2. Whether this is a real missing dependency, an analyzer synthetic name, or an analysis limitation.
3. Evidence after any remediation: log before/after, XML report summary, test suite/CI run, and remaining unknowns.

## Validation

\`\`\`bash
python3 -m unittest discover -s ops/security -p 'test_*.py'
python3 -m unittest discover -s ops/keycloak -p 'test_*.py'
mvn -B --no-transfer-progress clean verify

set -o pipefail
mvn -B --no-transfer-progress -DskipTests package spotbugs:check 2>&1 | tee /tmp/iam-spotbugs.log
python3 ops/security/verify_spotbugs_coverage.py \
  --log /tmp/iam-spotbugs.log \
  --xml target/spotbugs.xml \
  --baseline config/spotbugs-missing-baseline.json
\`\`\`

**Acceptance criteria:** Python parser tests, Java CI, Gitleaks, Trivy, Java SAST and coverage gate all pass. The CI log must explicitly show known warnings or a clean analysis and the analyzed class count; any new unresolved reference fails the job.

## Security use cases
- **SAST-COV-01:** New Java dependency cannot be located: CI rejects analysis coverage regression despite \`spotbugs:check\` reporting no bugs.
- **SAST-COV-02:** Known synthetic method warning persists: CI makes the gap visible but allows baseline while being investigated.
- **SAST-COV-03:** Scanner silently generates an empty XML report: CI rejects rather than accepting an apparent clean scan.
- **SAST-COV-04:** Broken or missing XML result: CI rejects rather than relying on a happy-path Maven exit.
- **SAST-COV-05:** An engineer removes a known warning: CI reports it in \`resolved_since_baseline\`; remove that entry only after checking it is genuinely resolved.

## Interview preparation
**Why can a SAST exit code 0 be misleading?** A tool may complete and detect zero reportable findings while failing to resolve some bytecode references. Reliability of analysis and correctness of findings are separate questions.

**Why use a warning baseline instead of failing all currently known warnings immediately?** It prevents *new* regressions while preserving progress, but is only defensible with a documented owner, planned investigation and visibility.

**Why validate XML in addition to logs?** It confirms the analyzer produced a structured result with an actual class count. Logging alone can be misleading if a goal was skipped or a report was missing.

**What would make 05B-1 fully resolved?** Demonstrating meaningful classpath resolution for the warnings, or documenting proven benign synthetic names with traceable analyzer evidence. This increment supplies detection, not that final root-cause verdict.
