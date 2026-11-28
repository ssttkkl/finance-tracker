import { readFileSync } from 'node:fs';

const source = new URL('../../src/jvmTest/resources/cash-operation-snapshot.json', import.meta.url);

export function cashListFixture() {
    const snapshot = JSON.parse(readFileSync(source, 'utf8'));
    const facts = snapshot.facts;
    const count = 512;
    const [transaction] = facts.cash_transactions;
    const [component] = facts.cash_transaction_components;
    const [entry] = facts.cash_entries;
    const [member] = facts.cash_entry_members;
    const collections = ['cash_transactions', 'cash_transaction_components', 'cash_entries', 'cash_entry_members'];
    for (const name of collections) facts[name] = [];
    for (let i = 0; i < count; i++) {
        const id = String(BigInt(transaction.id) + BigInt(i));
        const entryId = 'example-entry-' + i;
        const counterparty = i % 3 === 0 ? 'Synthetic orchard' : 'Synthetic store';
        facts.cash_transactions.push({ ...transaction, id, record_id: 'example-record-' + i, counterparty });
        facts.cash_transaction_components.push({ ...component, id: String(7001 + i), cash_transaction_id: id });
        facts.cash_entries.push({ ...entry, id: entryId, root_transaction_id: id, identity_transaction_id: id, counterparty });
        facts.cash_entry_members.push({ ...member, transaction_id: id, entry_id: entryId });
        facts.cash_source_bindings.push({
            id: String(i + 1), transaction_id: id, source_type: 'alipay', source_scope_kind: 'account',
            source_scope_key: 'fixture', identity_kind: 'provider_id', identity_key: 'record-' + i,
            identity_strength: 'provider', stable_evidence_key: null, strategy_version: 'fixture', revision: '1', updated_at: transaction.created_at,
            created_at: transaction.created_at, coverage: { fixture: '合成条目'.repeat(180) },
        });
    }
    return snapshot;
}

export function withTransfer(snapshot) {
    const ledger = structuredClone(snapshot);
    const facts = ledger.facts;
    const outgoing = facts.cash_transactions[0];
    const incoming = facts.cash_transactions[1];
    facts.accounts.push({ ...facts.accounts[0], id: '20', name: 'Synthetic savings' });
    Object.assign(outgoing, { amount: '-100', record_type: 'transfer_out', record_subtype: 'ordinary_transfer' });
    Object.assign(incoming, { account_id: '20', amount: '100', record_type: 'transfer_in', record_subtype: 'ordinary_transfer' });
    const debit = facts.cash_transaction_components[0];
    const credit = facts.cash_transaction_components[1];
    Object.assign(debit, { amount: '-100' });
    Object.assign(credit, { account_id: '20', amount: '100' });
    facts.cash_entry_members[1].entry_id = facts.cash_entry_members[0].entry_id;
    facts.cash_entries.splice(1, 1);
    facts.transaction_relations = [{
        id: '30', kind: 'transfer_pair', primary_transaction_id: outgoing.id, secondary_transaction_id: incoming.id,
        primary_component_id: debit.id, secondary_component_id: credit.id, anchor_transaction_id: outgoing.id,
        anchor_component_id: debit.id, primary_role: 'outgoing', secondary_role: 'incoming',
        state: 'confirmed', active: true, row_revision: '1', decision_group_id: null, decision_source: 'system',
        evidence: {}, applied_amount: '0', rule_id: 'fixture', created_by: 'fixture', created_at: outgoing.created_at,
        decided_by: 'fixture', decided_at: outgoing.created_at, decision_reason: '',
    }];
    return ledger;
}
