## Why

PR pushes currently create failed GitHub Actions runs with no jobs because two workflow files contain invalid YAML. The workflow parser stops before any CI checks can start.

## What Changes

- Represent the four Compose demo-mode verification commands as YAML block scalars in `pr-checks.yml` and `compose-ci.yml`.
- Preserve existing workflow triggers, jobs, checks, and shell command behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. This is a CI tooling repair and does not change product behavior; `.openspec.yaml` sets `skip_specs: true`.

## Impact

- Affected systems: `.github/workflows/pr-checks.yml` and `.github/workflows/compose-ci.yml`.
- No API, dependency, product behavior, or data changes. Rollback is reverting the workflow formatting change.
