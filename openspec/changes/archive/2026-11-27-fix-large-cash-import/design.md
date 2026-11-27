## Context

CLI sends the complete logical JSON. The HTTP reader bounds received and decoded bytes. The compact representation also bounds expansion. Each limit is 64 MiB. The generic import error currently omits commit state. The shared recovery operation looks up the original receipt before submitting, but handles only a base rejection or replay mismatch as recoverable refusal.

The CLI keeps the reviewed conclusion and its choices in encrypted local storage. A separate process rebuilds the request and result inputs at confirmation even when the same facts and choices are available. Compose has an in-memory review and separate platform IO adaptations.

## Decisions

- Keep one complete write set, authorization and transaction. Check its logical size against the existing budget before publishing a committable review. If the full JSON is too large, try omitting fields whose defaults are already defined by the version 1 contract. This preserves the decoded write set and permits the same proof to fit without moving the server budget. Preserve the received and expansion limits at the service. Use distinct authoritative refusal and uncertain response paths.
- An explicit write-set size refusal cannot retire the attempt until its original receipt is checked again. A successful original receipt wins. Receipt lookup failure keeps the attempt frozen. An old frozen command can use this path after the current server rejects it.
- Bind any reusable request and result inputs to the workspace, captured ledger sequence, review revision, rules version and immutable local batch files and choices. Store them in the existing encrypted database under the batch scope with metadata separate from the reviewed body. Older clients can still read the batch and recalculate the review. On a changed snapshot or rules version use the existing conclusion checks. Keep previous storage readable.
- Keep JSON stdout a single result. CLI stage events use stderr. Scope performance optimizations to data structures and repeated conversions whose output can be checked by source-row and relation-member identities.
- Readback pages keep the receipt scope and version gates. Bounded index lookups for active cash rows and their current entry membership avoid the pathological PostgreSQL join found immediately after a large atomic import.
- Do not replace the relationship or storage contract with source-specific sub-batches. If the representative input exceeds the existing logical budget after semantically equivalent work, record its breakdown and obtain a decision before modifying resource or transaction boundaries.

## Compose feedback

Reuse the existing file adjustment state, error slot and translated capacity message for definitively refused imports. Preserve the same screens, component hierarchy, details and typography. A pending receipt continues to use the existing recovery action. This applies to Web, Android and iOS through the common view model. Material Design 3’s States and Start building with Material 3 Expressive, and the JetBrains Compose Multiplatform relationship guide, support reuse of identifiable action states and the same Compose component.

## Verification and rollback

Compare the installed CLI on the same synthetic fixture and measure each phase. Confirm the actual receipt and authoritative readback. Test lost replies, explicit refusal, restart, offline, duplicate confirmation and changed facts. Verify SQLite and PostgreSQL using isolated test databases. Validate affected Compose host recovery. Backend changes must continue accepting the previous client formats. The rollout leaves application data and database schema unchanged.
