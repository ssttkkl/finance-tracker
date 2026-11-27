import { createHash } from 'node:crypto';
import { gunzipSync } from 'node:zlib';

const space = new Set([9, 10, 13, 32]);
const roots = { submit: 'changes', snapshot: 'facts', receipt: 'write_set_result' };
const schemaFields = new Set([
    'changes', 'facts', 'write_set_result', 'base_ledger_seq', 'rules_version', 'ledger_seq', 'workspace_id',
    'command_version', 'coverage_table', 'replay_groups', 'request_digest', 'receipt_id', 'status', 'summary',
    'plan_id', 'created_at', 'committed', 'operation_kind', 'pending_item_count', 'pending_transaction_ids',
    'accounts', 'account_aliases', 'statement_account_mappings', 'cash_categories', 'category_directory_revision',
    'cash_transactions', 'cash_transaction_components', 'cash_source_bindings', 'cash_source_empty_pages',
    'transaction_relations', 'cash_relation_candidates', 'cash_transaction_issues', 'cash_issue_candidates',
    'cash_entries', 'cash_entry_members', 'cash_investment_funding_relations', 'transactions', 'components',
    'source_bindings', 'source_empty_pages', 'issues', 'relations', 'entries', 'resolved_issue_ids',
    'retired_entry_ids', 'resolve_issues', 'entry_dependencies', 'group_members', 'relation_groups',
    'source_documents', 'file_results', 'batch_choices', 'binding_rebases', 'operation_refs', 'target', 'values',
    'preconditions', 'pre_edits', 'entry_edits', 'standalone_values', 'manual_overrides', 'metadata_json',
    'counterparty_account_attrs', 'source_evidence', 'source_fingerprint', 'stable_source_key', 'root_transaction_id',
    'identity_transaction_id', 'row_refs', 'standalone_manual_fields', 'coverage_ref', 'ordinal',
]);
const repeatFields = new Set([
    'coverage', 'source_payload', 'relation_metadata', 'source_scope_key', 'source_scope_kind',
    'identity_kind', 'identity_key', 'identity_strength', 'stable_evidence_key', 'strategy_version',
    'expected_revision', 'record_id', 'members', 'transaction', 'transaction_id', 'component_ids',
    'row_revision', 'revision', 'account_id', 'amount', 'currency',
]);

export function summarizePayloadShape(route, bytes) {
    const root = roots[route];
    if (!root) throw new Error('Unsupported payload shape');
    const fields = new Map();
    const measured = new Set(['count', 'bytes', 'repeatedValues', 'repeatedValueBytes']);
    let index = 0;

    function skipSpace() {
        while (space.has(bytes[index])) index++;
    }

    function stringEnd() {
        const first = index++;
        while (index < bytes.length) {
            const current = bytes[index++];
            if (current === 34) return first;
            if (current === 92) index++;
        }
        throw new Error('Incomplete JSON string');
    }

    function record(path, first, end) {
        const dimension = path.join('.');
        const collection = path[0] === root;
        const row = collection && path.length === 3 && path[2] === '[]';
        const repeated = row || (collection && path.length > 3 && repeatFields.has(path.at(-1)));
        const wins = path.length === 1 || (collection && path.length <= 4) || repeated;
        if (!wins || path.length === 0) return;
        const entry = fields.get(dimension) ?? { count: 0, bytes: 0, repeatedValues: 0, repeatedValueBytes: 0,
            seen: repeated ? new Set() : null };
        const length = end - first;
        entry.count++;
        entry.bytes += length;
        if (entry.seen) {
            const digest = createHash('sha256').update(bytes.subarray(first, end)).digest('base64url');
            if (entry.seen.has(digest)) {
                entry.repeatedValues++;
                entry.repeatedValueBytes += length;
            } else entry.seen.add(digest);
        }
        fields.set(dimension, entry);
    }

    function value(path, depth) {
        if (depth > 160) throw new Error('Payload shape nesting exceeds diagnostics');
        skipSpace();
        const first = index;
        const char = bytes[index];
        if (char === 34) {
            stringEnd();
        } else if (char === 123 || char === 91) {
            const array = char === 91;
            const closing = array ? 93 : 125;
            index++;
            skipSpace();
            if (bytes[index] !== closing) {
                while (true) {
                    let child = '[]';
                    if (!array) {
                        if (bytes[index] !== 34) throw new Error('Invalid JSON property');
                        const start = stringEnd();
                        const name = JSON.parse(bytes.toString('utf8', start, index));
                        // Only schema fields enter evidence. Arbitrary JSON keys can carry business values.
                        child = repeatFields.has(name) || schemaFields.has(name) ?
                            name : '*';
                        skipSpace();
                        if (bytes[index++] !== 58) throw new Error('Invalid JSON object');
                    }
                    value([...path, child], depth + 1);
                    skipSpace();
                    if (bytes[index] === closing) break;
                    if (bytes[index++] !== 44) throw new Error('Invalid JSON delimiter');
                    skipSpace();
                }
            }
            index++;
        } else {
            while (index < bytes.length && !space.has(bytes[index]) && ![44, 93, 125].includes(bytes[index])) index++;
            if (index === first) throw new Error('Invalid JSON scalar');
        }
        record(path, first, index);
    }
    value([], 0);
    skipSpace();
    if (index !== bytes.length) throw new Error('Trailing JSON content');
    const top = [...fields.entries()].map(([field, value]) => ({
        field, share: value.bytes / bytes.length,
        ...Object.fromEntries(Object.entries(value).filter(([key]) => measured.has(key))),
    }));
    const largest = (predicate, limit) => top.filter(({ field }) => predicate(field))
        .sort((a, b) => b.bytes - a.bytes || a.field.localeCompare(b.field)).slice(0, limit);
    return {
        route, bytes: bytes.length,
        topLevel: largest(field => !field.includes('.'), 12),
        collections: largest(field => field.startsWith(root + '.') && field.split('.').length === 2, 12),
        nested: largest(field => field.startsWith(root + '.') && field.split('.').length >= 4, 24),
        repetition: [...top].filter(entry => entry.repeatedValues > 0)
            .sort((a, b) => b.repeatedValueBytes - a.repeatedValueBytes || a.field.localeCompare(b.field)).slice(0, 24),
    };
}

export function observePayloadShape(message, route, record) {
    if (!Object.hasOwn(roots, route)) return;
    const limit = 64 * 1024 * 1024;
    const parts = [];
    let count = 0;
    message.on('data', (chunk) => {
        count += chunk.length;
        if (count <= limit) parts.push(chunk);
    });
    message.once('end', () => {
        if (count > limit) {
            record({ route, error: 'diagnostic_wire_limit_exceeded' });
            return;
        }
        try {
            const raw = Buffer.concat(parts, count);
            const encoding = message.headers['content-encoding']?.toLowerCase() ?? 'identity';
            if (encoding !== 'gzip' && encoding !== 'identity') throw new Error('Unsupported body encoding');
            const body = encoding === 'gzip' ? gunzipSync(raw, { maxOutputLength: limit }) : raw;
            record({ ...summarizePayloadShape(route, body), wireBytes: raw.length });
        } catch {
            record({ route, error: 'diagnostic_body_invalid' });
        } finally {
            parts.length = 0;
        }
    });
    message.once('aborted', () => { parts.length = 0; });
}
