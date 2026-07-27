# Branch protection baseline (external decision required)

This document records the branch protection settings Relay expects on
`main` and `agent/zero-operation-relay`. Repository settings cannot be
version-controlled, so applying them is an **owner action**; CI cannot verify
them and this document does not claim they are active.

## Required settings

| Setting | Value | Reason |
|---|---|---|
| Require a pull request before merging | ON, 1 approval | No direct pushes to protected branches |
| Dismiss stale approvals | ON | Re-review after force-push/new commits |
| Require status checks to pass | ON | Fail-closed merge gate |
| Required checks | `Build Android and Gateway`, `Shared unit tests`, `Android Lint`, `Staff console Playwright E2E`, `Compose UI smoke tests`, `Accessibility and UI safety contract`, `Readiness status single source of truth`, `Secret scan (gitleaks)`, `Workflow lint (actionlint + zizmor)`, `Verify all actions are SHA-pinned`, `CodeQL analyze (java-kotlin)`, `Review dependency changes` | Every merge passes the security baseline |
| Require branches to be up to date | ON | Checks run against merged state |
| Require signed commits | Recommended | Commit provenance |
| Require linear history | ON | Auditable history |
| Restrict force pushes | ON (nobody) | History immutability |
| Restrict deletions | ON | Branch cannot be removed |
| Enforce for administrators | ON | No bypass |

## Repository-level settings

- **Secret scanning + push protection**: enable GitHub secret scanning and
  push protection (Settings → Code security). Gitleaks in CI is a second,
  independent layer.
- **Private vulnerability reporting**: enable so external researchers can
  report privately (see SECURITY policy work in later phases).
- **Actions permissions**: restrict to actions pinned by the workflows;
  default workflow token permissions: read-only.

## Verification

After applying, verify with:

```
gh api repos/NEGI46/Relay/branches/agent/zero-operation-relay/protection
```

Status tracking: `docs/readiness/status.yml` feature `branch-protection`
stays `BLOCKED_EXTERNAL` until the owner confirms these settings are active;
a repository administrator applying them is the dated external record.
