# Evidence and Canonicalization Contract

## Stable Ordering

Evidence rows are sorted by the total tuple:

```text
(occurred_at, source_identity, evidence_kind, evidence_identity)
```

`ordering_version` is part of every page and cursor. A cursor is bound to `(component_id, result_revision, ordering_version)` and the final tuple. Reusing a cursor with a different result or ordering version returns `wealth.evidence_cursor_invalid`.

## Evidence Identity and Folding

Each evidence item has:

```text
evidence_identity
source_identity
source_revision
occurred_at
evidence_kind
contribution: Decimal | None
scope_fold_identity
safe_metadata
```

Evidence identity is immutable and workspace-qualified. The same source may appear on multiple daily points, but a weekly/monthly result folds it once per result-scoped contribution context. Monetary contributions are summed exactly once by `scope_fold_identity`; a gap item may have a null contribution and is retained in the page.

For a direct formal-fact contribution, the evidence item is the immutable source-manifest item captured by the build; component evidence manifests select it by canonical period/kind/group predicates. Implementations MUST NOT require a duplicate evidence row or per-component link for every direct source item. Derived/conflict/residual/gap evidence without a direct source item is stored separately and merged during paging.

For each component, the service verifies:

```text
sum(folded monetary contributions) == component.amount
```

or, for residual/coverage components, the equivalent explicit gap amount and evidence set. A mismatch fails the build rather than silently changing the component amount.

## Canonical Bytes

Canonical payloads are UTF-8 JSON with sorted object keys, compact separators and no floating-point values. Decimal amounts are plain strings without exponent notation. The digest is SHA-256 over canonical bytes. Arrays are pre-sorted by their contract order; PostgreSQL/SQLite JSON object ordering is never authoritative.

## Pagination Semantics

Pages are read from an immutable component result. Repeating a request with the same component/result/cursor returns the same ordered rows. A cursor is exclusive of its last tuple, so page concatenation has neither duplicates nor omissions.
