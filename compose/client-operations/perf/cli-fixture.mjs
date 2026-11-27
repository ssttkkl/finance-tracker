function csv(indices, seed) {
    const account = seed ? 'perf-ledger' : 'perf-preview';
    const pad = value => String(value).padStart(2, '0');
    const records = indices.map(index => {
        const month = seed ? Math.floor(index / 250) + 1 : 6;
        const date = `2026-${pad(month)}-${pad(index % 27 + 1)} ${pad(10 + Math.floor(index / 27) % 12)}:00:00`;
        const closed = !seed && index >= 985;
        const id = String((seed ? 1 : 2) * 10 ** 12 + index);
        return [date, '日用百货', `合成商户${index}`, '', '合成日用品', closed ? '不计收支' : '支出', '12.40',
            closed ? '' : '支付宝余额', closed ? '交易关闭' : '交易成功', id, id, '', ''].join(',');
    });
    return [
        '支付宝交易记录明细查询', `支付宝账户：[${account}@example.test]`,
        '起始时间：[2026-01-01 00:00:00]', '终止时间：[2026-12-31 23:59:59]',
        '交易类型：[全部]', `共${records.length}笔记录`,
        '交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,',
        ...records,
    ].join('\n') + '\n';
}

export function range(start, end) {
    return Array.from({ length: end - start }, (_, index) => index + start);
}

export function ordinaryLedgerCsv() {
    return ['ledger.csv', csv(range(0, 3000), true)];
}

export function ordinaryImportSources() {
    return [
        ['preview-1.csv', csv(range(0, 300), false)],
        ['preview-2.csv', csv(range(300, 650), false)],
        ['preview-3.csv', csv(range(640, 990), false)],
    ];
}
