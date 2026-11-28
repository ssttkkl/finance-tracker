const importPath = '/api/v1/cash-import';
const writeSet = `${importPath}/write-set`;
const accounts = '/api/v1/accounts';
const ledger = '/api/v1/ledger';

export const importRequestRoutes = {
    session: ['GET', '/api/v1/auth/session'],
    accountReceipt: ['GET', '/api/v1/cash-mutations/receipts/by-key'],
    pdf: ['POST', `${importPath}/pdf/parse`],
    xls: ['POST', `${importPath}/xls/parse`],
    snapshot: ['GET', `${ledger}/snapshot`],
    snapshotPage: ['GET', `${ledger}/snapshot/pages`],
    version: ['GET', `${ledger}/version`],
    accounts: ['GET', accounts],
    account: ['GET', `${accounts}/by-id/{account_id}`],
    createAccount: ['POST', accounts],
    updateAccount: ['PATCH', `${accounts}/by-id/{account_id}`],
    deleteAccount: ['DELETE', `${accounts}/by-id/{account_id}`],
    addCurrencies: ['POST', `${importPath}/accounts/currency-requirements`],
    submit: ['POST', writeSet],
    status: ['GET', `${writeSet}/status`],
    cancel: ['POST', `${writeSet}/cancel`],
    receipt: ['GET', `${writeSet}/receipt`],
    receiptPage: ['GET', `${writeSet}/receipt/pages`],
    receiptRows: ['GET', `${writeSet}/receipt/{receipt_id}/rows`],
    receiptRelations: ['GET', `${writeSet}/receipt/{receipt_id}/relations`],
};

const matchers = Object.entries(importRequestRoutes).map(([id, [method, template]]) => ({
    id, method, path: new RegExp(`^${template.replace(/\{[a-z_]+\}/g, '[^/]+')}/?$`),
}));

export function importRequestRoute(method, path) {
    return matchers.find((route) => route.method === method && route.path.test(path))?.id ?? 'unclassified';
}

export const compatibleReadSections = {
    snapshotPage: [
        'accounts', 'account_aliases', 'statement_account_mappings', 'cash_categories', 'cash_transactions',
        'cash_transaction_components', 'cash_source_bindings', 'cash_source_empty_pages', 'transaction_relations',
        'cash_relation_candidates', 'cash_transaction_issues', 'cash_issue_candidates', 'cash_entries', 'cash_entry_members',
        'cash_investment_funding_relations',
    ],
    receiptPage: [
        'header', 'transactions', 'source_bindings', 'issues', 'statement_account_mappings', 'relations', 'entries',
        'resolved_issue_ids', 'retired_entry_ids', 'pending_transaction_ids',
    ],
};

export function readPageCoordinates(route, url) {
    if (!Object.hasOwn(compatibleReadSections, route)) return undefined;
    const section = url.searchParams.get('section');
    function index(name) {
        const raw = url.searchParams.get(name);
        if (raw === null) return 0;
        const number = Number(raw);
        return Number.isSafeInteger(number) && number >= 0 && String(number) === raw ? number : -1;
    }
    return {
        section: section === null || compatibleReadSections[route].includes(section) ? section : 'invalid',
        offset: index('offset'),
        fragmentOffset: index('fragment_offset'),
    };
}


export const readPageBundleHeader = 'x-ft-read-page-bundle';
export const readPageChunksHeader = 'x-ft-read-page-chunks';

export function readPageChunks(route, value) {
    const sections = compatibleReadSections[route];
    if (!sections || typeof value !== 'string' || value.length > 4096) throw new Error('missing read page chunks');
    const fields = value.split(',');
    if (fields.length > sections.length) throw new Error('invalid read page chunks');
    return fields.map((field) => {
        const [section, ...coordinates] = field.split(':');
        if (!sections.includes(section) || coordinates.length !== 6 ||
            coordinates.some((part) => !/^(0|[1-9][0-9]{0,9})$/.test(part) || Number(part) > 2147483647)) {
            throw new Error('invalid read page chunks');
        }
        const [offset, fragmentOffset, total, rows, fragmentTotal, fragmentBytes] = coordinates.map(Number);
        return { section, offset, fragmentOffset, total, rows, fragmentTotal, fragmentBytes };
    });
}
