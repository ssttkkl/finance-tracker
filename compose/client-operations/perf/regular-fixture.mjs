import { createHash } from 'node:crypto';

// Match the SQLite UtcInstantBinding storage format.
const stamp = '2025-01-01 00:00:00.000000';
const future = '2075-01-01 00:00:00.000000';

function atomic(db, action) {
    db.exec('BEGIN IMMEDIATE');
    try {
        const result = action();
        db.exec('COMMIT');
        return result;
    } catch (failure) {
        db.exec('ROLLBACK');
        throw failure;
    }
}

function permanentId(group, number) {
    return `00000000-0000-4${group}-8000-${String(number).padStart(12, '0')}`;
}

function newWorkspace(db, id, name, owner) {
    db.prepare('INSERT INTO workspaces (id, name, created_at, ledger_seq) VALUES (?, ?, ?, 1)').run(id, name, stamp);
    db.prepare('INSERT INTO workspace_memberships (workspace_id, user_id, role, created_at) VALUES (?, ?, ?, ?)')
        .run(id, owner, 'admin', stamp);
}

function addAccounts(db, workspace, count) {
    const add = db.prepare(`INSERT INTO accounts (workspace_id, name, type, metadata_json, currencies, created_at, updated_at)
        VALUES (?, ?, 'cash', '{}', '["CNY"]', ?, ?)`);
    return Array.from({ length: count }, (_, i) => String(add.run(workspace, `Perf account ${i}`, stamp, stamp).lastInsertRowid));
}

function addCategories(db, workspace, count) {
    db.prepare('INSERT INTO cash_category_states (workspace_id,revision,updated_at) VALUES (?, 1, ?)').run(workspace, stamp);
    const add = db.prepare(`INSERT INTO cash_categories
        (id, workspace_id, parent_scope_key, name, normalized_name, category_path, depth, sort_order, created_at, updated_at)
        VALUES (?, ?, '', ?, ?, ?, 1, ?, ?, ?)`);
    return Array.from({ length: count }, (_, i) => {
        const id = permanentId('004', i + 1);
        const name = `Perf Category ${String(i).padStart(4, '0')}`;
        add.run(id, workspace, name, name.toLowerCase(), `/${id}/`, i, stamp, stamp);
        return id;
    });
}

function addRows(db, workspace, accounts, count, categories = []) {
    const add = db.prepare(`INSERT INTO cash_transactions
        (workspace_id, account_id, occurred_at, amount, currency, counterparty, note, created_at,
          record_type, category_id, record_origin, source_type, record_id, source_payload)
        VALUES (?, ?, ?, ?, 'CNY', ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    const component = db.prepare(`INSERT INTO cash_transaction_components
        (workspace_id,cash_transaction_id,account_id,amount,currency,ordinal,created_at) VALUES (?, ?, ?, ?, 'CNY', 0, ?)`);
    const entry = db.prepare(`INSERT INTO cash_entries
        (id,workspace_id,root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields,identity_transaction_id)
        VALUES (?, ?, ?, 1, ?, ?, ?, '', '[]', '[]', ?)`);
    const member = db.prepare(`INSERT INTO cash_entry_members
        (workspace_id,transaction_id,entry_id,standalone_values,standalone_manual_fields) VALUES (?, ?, ?, ?, '[]')`);
    return Array.from({ length: count }, (_, i) => {
        const date = `2025-${String(i % 12 + 1).padStart(2, '0')}-${String(i % 28 + 1).padStart(2, '0')} 12:00:00.000000`;
        const income = i % 3 === 0;
        const amount = income ? '12.34' : '-10.25';
        const counterparty = `Perf Merchant ${i % 12}`;
        const note = `Perf statement ${i}`;
        // Leave at least 100 selectable uncategorized items and a separate unused category.
        const category = categories.length && i >= 100 ? categories[i % (categories.length - 1)] : null;
        const account = accounts[i % accounts.length];
        const source = count === 3000 ? ({ 1: 'alipay', 3: 'ccb_debit' }[i] ?? null) : null;
        const sourceRef = source ? `perf-original-${i}` : '';
        const origin = source ? 'import' : 'manual';
        const id = String(add.run(workspace, account, date, amount, counterparty, note, stamp,
            income ? 'income' : 'consumption', category, origin, source, sourceRef,
            source ? JSON.stringify({ fixture: 'ordinary', number: i }) : null).lastInsertRowid);
        const entryId = `perf-entry-${id}`;
        component.run(workspace, id, account, amount, stamp);
        entry.run(entryId, workspace, id, category, note, counterparty, id);
        member.run(workspace, id, entryId, JSON.stringify({
            category_id: category, note, counterparty, counterparty_account: '', counterparty_account_attrs: [],
        }));
        return { id, entryId: 'cash-entry:' + entryId, amount, account, note, date, source, sourceRef };
    });
}

export function restoreBalances(db, workspace) {
    const payload = { updated_at: '2025-01-01T00:00:00Z', accounts: { cash: {}, loan: {}, lend: {}, security: {} } };
    const accounts = db.prepare('SELECT id, name, type FROM accounts WHERE workspace_id=?').all(workspace);
    const amounts = new Map();
    for (const row of db.prepare(`SELECT account_id, amount, currency FROM cash_transactions
        WHERE workspace_id=? AND state='posted' AND deleted_at IS NULL`).all(workspace)) {
        if (row.currency !== 'CNY') throw new Error('Ordinary fixture cash must use CNY');
        const match = /^(-?)(\d+)\.(\d{2})$/.exec(row.amount);
        if (!match) throw new Error('Unexpected ordinary fixture decimal');
        const cents = (Number(match[2]) * 100 + Number(match[3])) * (match[1] ? -1 : 1);
        amounts.set(row.account_id, (amounts.get(row.account_id) ?? 0) + cents);
    }
    for (const account of accounts) {
        if (account.type !== 'cash') throw new Error('Unexpected fixture account type');
        const cents = amounts.get(account.id) ?? 0;
        const absolute = Math.abs(cents);
        payload.accounts.cash[account.id] = {
            CNY: `${cents < 0 ? '-' : ''}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`,
        };
    }
    db.prepare(`INSERT INTO ledger_snapshots (workspace_id,payload,version,updated_at) VALUES (?, ?, 1, ?)
        ON CONFLICT (workspace_id) DO UPDATE SET payload=excluded.payload, version=ledger_snapshots.version+1,
            updated_at=excluded.updated_at`).run(workspace, JSON.stringify(payload), stamp);
}

export function seed(db, ownerEmail, initialWorkspace) {
    return atomic(db, () => {
        const owner = db.prepare('SELECT id FROM users WHERE email=?').get(ownerEmail)?.id;
        if (!owner) throw new Error('Authenticated fixture owner not found');
        const workspaces = [initialWorkspace, ...Array.from({ length: 19 }, (_, i) => permanentId('001', i + 1))];
        for (let i = 1; i < workspaces.length; i++) newWorkspace(db, workspaces[i], `Perf workspace ${i}`, owner);
        db.prepare('UPDATE workspaces SET ledger_seq=1 WHERE id=?').run(initialWorkspace);
        const addUser = db.prepare('INSERT INTO users (id,email,password_hash,created_at) VALUES (?, ?, ?, ?)');
        const addMember = db.prepare(`INSERT INTO workspace_memberships (workspace_id,user_id,role,created_at)
            VALUES (?, ?, 'viewer', ?)`);
        const members = Array.from({ length: 49 }, (_, i) => {
            const id = permanentId('002', i + 1);
            addUser.run(id, `perf-member-${i}@example.test`, 'fixture-without-login', stamp);
            addMember.run(initialWorkspace, id, stamp);
            return id;
        });
        const accounts = addAccounts(db, initialWorkspace, 50);
        const emptyAccounts = addAccounts(db, workspaces[1], 50);
        const categoryAccounts = addAccounts(db, workspaces[2], 50);
        const categories = addCategories(db, workspaces[2], 1000);
        const rows = addRows(db, initialWorkspace, accounts, 3000);
        const categoryRows = addRows(db, workspaces[2], categoryAccounts, 10000, categories);
        for (const workspace of workspaces.slice(0, 3)) restoreBalances(db, workspace);
        const cashDigest = createHash('sha256').update(JSON.stringify({ accounts, rows })).digest('hex');
        return { owner, workspaces, members, accounts, emptyAccounts, categoryAccounts, categories, rows, categoryRows, cashDigest };
    });
}

export function restoreMember(db, workspace, user) {
    db.prepare("INSERT INTO workspace_memberships (workspace_id,user_id,role,created_at) VALUES (?, ?, 'viewer', ?)")
        .run(workspace, user, stamp);
}

export function seedDeletion(db, id, owner) {
    return atomic(db, () => {
        newWorkspace(db, id, 'Perf deletion workspace', owner);
        const addSession = db.prepare(`INSERT INTO user_sessions
            (id,user_id,token_digest,active_workspace_id,expires_at,created_at) VALUES (?, ?, ?, ?, ?, ?)`);
        for (let i = 0; i < 999; i++) {
            const identity = permanentId('003', i + 1);
            addSession.run(identity, owner, `perf-synthetic-deletion-session-${i}`, id, future, stamp);
        }
        const accounts = addAccounts(db, id, 50);
        addRows(db, id, accounts, 1000);
    });
}

export function deleteDeletionRemainder(db, id) {
    db.prepare('DELETE FROM user_sessions WHERE id LIKE ?').run('00000000-0000-4003-8000-%');
    const exists = Number(db.prepare('SELECT count(*) AS total FROM workspaces WHERE id=?').get(id).total);
    if (exists) throw new Error('Deleted workspace still exists');
}

const counts = {
    workspaces: 'SELECT count(*) AS total FROM workspaces WHERE id=?',
    memberships: 'SELECT count(*) AS total FROM workspace_memberships WHERE workspace_id=?',
    activities: 'SELECT count(*) AS total FROM user_sessions WHERE active_workspace_id=?',
    accounts: 'SELECT count(*) AS total FROM accounts WHERE workspace_id=?',
    rows: 'SELECT count(*) AS total FROM cash_transactions WHERE workspace_id=? AND deleted_at IS NULL',
    receipts: 'SELECT count(*) AS total FROM cash_mutation_receipts WHERE workspace_id=?',
    categories: 'SELECT count(*) AS total FROM cash_categories WHERE workspace_id=?',
};

export function rowCount(db, kind, workspace) {
    if (!(kind in counts)) throw new Error('Unknown fixture metric ' + kind);
    return Number(db.prepare(counts[kind]).get(workspace).total);
}
