## Context

See proposal.md. YAML treats `: ` inside a plain scalar as mapping syntax, even when the shell command contains single quotes. GitHub therefore rejects both workflow files before creating jobs.

## Goals / Non-Goals

**Goals:** Make both workflow files parse and retain the existing verification commands.

**Non-Goals:** Change triggers, runners, job dependencies, CI coverage, or application code.

## Decisions

- Use `run: >-` block scalars for all four grep commands. YAML then treats the command as scalar content, and folded style preserves it as one shell command.
- Keep the grep patterns and file paths unchanged so the workflow behavior remains the same.

## Risks / Trade-offs

- A YAML parser check cannot prove GitHub Actions accepts every workflow field → inspect the pushed GitHub run and confirm the jobs are created.

## Migration Plan

No migration is required. Reverting the workflow-only commit restores the previous files if needed.
