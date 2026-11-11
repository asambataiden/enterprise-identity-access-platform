#!/usr/bin/env python3
"""Read-only structural validation for Milestone 00, independent of Java tooling."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
required = (
    'README.md','pom.xml','.java-version','docs/use-cases/UC-001-application-access-request.md',
    'docs/adr/0001-modular-monolith.md','docs/adr/0002-identity-provider-boundary.md',
    'docs/security/threat-model.md','docs/architecture/system-overview.md',
    'src/main/java/dev/asambataiden/iam/IamApplication.java',
    'src/main/java/dev/asambataiden/iam/bootstrap/BootstrapSecurityConfiguration.java',
    '.github/workflows/java-ci.yml',
)
missing=[p for p in required if not (root/p).is_file()]
for p in missing: print(f'MISSING: {p}',file=sys.stderr)
print(f'Foundation structure: {len(required)-len(missing)}/{len(required)} required files')
raise SystemExit(bool(missing))
