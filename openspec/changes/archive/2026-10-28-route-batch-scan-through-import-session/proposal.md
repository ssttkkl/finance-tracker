## Why

Compose Web sends all selected statements through the batch scan API, but that request currently bypasses the temporary import-session service. The scan response therefore has no `import_token`, so preview stops before any statement can be reviewed or written. After that path is repaired, an atomic source component can still retain the statement's source-account key instead of the account selected in mapping. Route batch scan and password retry through the existing session service, and apply the confirmed mapping to each atomic component so the established cash import flow can finish safely.

## What Changes

- Ensure batch scan creates and returns a workspace-bound temporary import session.
- Ensure password retries reuse that same session and token.
- Apply the selected account mapping to an atomic component when the source account label differs from the target account name; preserve its amount and source evidence.
- Preserve the existing preview/confirmation boundary and avoid ledger writes during scanning.

Non-goals: changing statement parsers, the user's confirmed account mapping rules, transaction amounts, source grouping, storage backends, or user-visible UI.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `import/cash-import-session`: make the shared temporary session contract explicit for batch scanning and password retry.
- `import/statement-import`: explicitly require a confirmed mapping to apply to the account reference of a single atomic source component while preserving source evidence and amount.

## Impact

- Affected code: `src/ft/web/app.py` service dispatch, `src/ft/application/cash_ledger.py` component mapping, and focused application/web contract tests.
- Affected clients: Compose Web and Native share the same batch import API; no client UI or route changes are intended. The cash-import-session delta states the batch token and password-retry behavior that was previously only implicit in the implementation flow.
- Data: scanning and preview remain temporary; business database writes remain limited to explicit final confirmation. Confirmed source-account mappings apply to both the parent row and its atomic component while preserving the original source label, amount, and source row snapshot.
- Migration/rollback: no schema or data migration. Reverting the dispatch change restores the current failure; existing sessions are short-lived and expire by TTL.

## 后续现金导入重设计的同步边界

`cash-import-lifecycle-redesign` 已声明本变更部分导入条款的后续取代，具体范围见 [取代清单](../2026-10-28-cash-import-lifecycle-redesign/design.md#active-changes-取代与同步顺序)。新变更尚未实施时不改变当前行为；新合同生效后，本变更不得重新同步已经取代的旧流程或规则，须先清理或合并相应 delta。原未完成验证及用户演示仍按原记录保留，不因此自动完成。
