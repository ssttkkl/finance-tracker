## Context

See `proposal.md` for the user impact. The route resolves methods through `WorkspaceServices.__getattr__`. Batch scan, batch password retry, batch preview and batch confirmation methods already exist on `CashImportSessionService`, but the resolver did not send all of them through that service. The Compose clients therefore received a legacy scan result without the temporary session token, and preview/confirmation methods could not resolve. The cash-import-session delta records the shared token and retry behavior; the route dispatch remains an implementation detail. A second issue appears after mapping: rows with one atomic component can keep the parser's source-account key as the component account name even though the user selected a differently named target account. The statement-import delta requires the confirmed mapping to apply to both the business row and its atomic component.

## Goals / Non-Goals

**Goals:** Route initial batch scanning, batch password retries, batch preview and batch confirmation through the existing session service, preserving one token and current session boundaries. For a row with one atomic source component, apply the resolved account ID/name to that component while retaining its amount, original source label, currency, and source row snapshot.

**Non-Goals:** Change parsing, the user's account mapping choices, multi-component allocation rules, statement data, staging storage, database schema, or client UI.

## Decisions

- Add `scan_import_batch`, `scan_import_batch_session`, `preview_import_batch_session` and `commit_import_batch_session` to the existing session-service dispatch allowlist. The route already calls these method names, so no API payload or client changes are needed.
- Keep the existing `CashImportSessionService` and staging store as the only owners of temporary batch data. This avoids introducing a second session mechanism or duplicating scan logic.
- Add a focused resolver regression test for all four method names. Existing service tests cover batch token creation, password retry, preview readiness and confirmation behavior; the new test protects the missing dispatch boundary.
- Record in the cash-import-session delta that batch password retry uses the original session token and does not require re-uploading already staged files.
- When a source row has one component, build its component draft from the already resolved mapping even when the user did not provide a separate allocation override. This updates the persisted component's account reference without inferring or changing any amount. Keep the existing explicit allocation path for rows with multiple components.
- Add a regression test where the parser source key and mapped target account name differ; confirm preview and commit store the component under the mapped account and retain its source label and amount.
- Alternatives considered: change the route to call a separately constructed session service, or add client-side token recovery. Both duplicate responsibilities or cannot recover the missing server token, so the resolver dispatch is the smallest correct boundary.

## Risks / Trade-offs

- A missing staging store will now fail batch scan with the existing session-storage configuration error rather than silently returning a legacy result. This is intentional failure-closed behavior.
- Batch scanning can create temporary staged objects. Existing TTL cleanup and explicit confirmation behavior remain unchanged; no business database writes occur during scan or retry.
- A stale component account key currently makes final import fail when the mapped target is renamed or normalized. Remapping only the single atomic component preserves current financial meaning; multi-component rows continue to require complete, conserved allocations.

## Migration Plan

No database migration or client deployment coordination is required. Restart the local backend to load the dispatch and component mapping changes. Rollback by reverting the service dispatch and atomic component mapping; existing staged sessions expire under their configured TTL.

## Cross-platform impact

- Web: affected because Compose Web currently uses batch scanning and is blocked before preview; the same mapping fix also allows source labels to map to the chosen account.
- Native: shared Compose import flow uses the same API contract and application service, so both corrections apply without a Native-only UI change.
- Shared layer: no source change expected; run relevant shared import workflow tests if available.
