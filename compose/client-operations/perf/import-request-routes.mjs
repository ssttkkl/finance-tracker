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
    receiptRows: ['GET', `${writeSet}/receipt/{receipt_id}/rows`],
    receiptRelations: ['GET', `${writeSet}/receipt/{receipt_id}/relations`],
};

const matchers = Object.entries(importRequestRoutes).map(([id, [method, template]]) => ({
    id, method, path: new RegExp(`^${template.replace(/\{[a-z_]+\}/g, '[^/]+')}/?$`),
}));

export function importRequestRoute(method, path) {
    return matchers.find((route) => route.method === method && route.path.test(path))?.id ?? 'unclassified';
}
