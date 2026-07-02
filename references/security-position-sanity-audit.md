# Security 持仓异常/脏数据排查

用于用户质疑 `ft stock list` 中股数、市值、成本、盈亏“看着不对”时。目标是先定位数据来源，不要直接改账。

## 固定顺序

1. 先跑一致性校验：

```bash
cd ~/.ft
ft verify
```

- 通过：说明 records CSV 与 snapshot 一致，问题多半是源记录/成本法/行情价/外部持仓对比，而不是 snapshot 损坏。
- 不通过：先按 verify 报告定位，不要继续报持仓数字。

2. 扫描 snapshot 中的异常形态：

```bash
cd ~/.ft
python3 - <<'PY'
import yaml
snap=yaml.safe_load(open('snapshot.yaml', encoding='utf-8'))
for acct, data in snap['accounts'].get('security', {}).items():
    print('\n##', acct, data.get('currency'), 'cash=', data.get('cash'))
    for t,p in sorted((data.get('positions') or {}).items()):
        sh=float(p.get('shares',0)); avg=float(p.get('avg_cost',0)); cost=sh*avg
        flags=[]
        if abs(sh)<0.01: flags.append('tiny-shares')
        if cost<0: flags.append('negative-cost')
        if avg==0 and abs(sh)>0: flags.append('zero-avg-cost')
        if abs(avg)>1000 and t.startswith('pm:'): flags.append('pm-huge-avg')
        print(f'{t:85} shares={sh:g} avg={avg:g} cost={cost:.2f} {"|".join(flags)}')
PY
```

重点解释：
- `zero-avg-cost`：通常是 `CHECKIN` 时成本填 0，盈亏不可用，需要用户确认真实成本。
- `negative-cost`：不一定是错。平均成本法下，部分卖出赚的钱超过剩余摊余成本时，剩余仓位可以是负成本。
- Polymarket 的 `tiny-shares + negative-cost + huge avg`：常见于官方 Activity 回放的浮点/撮合尾差，需与官方 positions 对比后再清理。

3. 找异常 ticker 的来源记录：

```bash
cd ~/.ft
python3 - <<'PY'
from pathlib import Path
sus = ['TICKER1', 'TICKER2']
for s in sus:
    print('\n###', s)
    for p in sorted(Path('records/security').glob('*.csv')):
        txt=p.read_text(encoding='utf-8')
        if s not in txt: continue
        for line in txt.splitlines():
            if s in line:
                print(p.name, line)
PY
```

4. Polymarket 必须用官方当前持仓 API 交叉验证。已知 proxy wallet 可从 profile 页面解析或复用导入流程得到。

```bash
cd ~/.ft
python3 - <<'PY'
import urllib.request, json, yaml
addr='0xPROXY_WALLET'
u=f'https://data-api.polymarket.com/positions?user={addr}&sizeThreshold=0&limit=200'
req=urllib.request.Request(u, headers={'User-Agent':'Mozilla/5.0'})
positions=json.loads(urllib.request.urlopen(req, timeout=30).read())
api={f"pm:{p['slug']}:{p['outcome'].lower()}":p for p in positions}
snap=yaml.safe_load(open('snapshot.yaml'))
ft=snap['accounts']['security']['Polymarket']['positions']
for t in sorted(set(ft)|set(api)):
    fs=float(ft[t]['shares']) if t in ft else 0
    aa=float(api[t]['size']) if t in api else 0
    print(f"{t[:80]:80} ft={fs:12g} api={aa:12g} diff={fs-aa: .8f}")
PY
```

判断：
- ft 有、官方没有，且 `abs(shares) < 0.01`：可视为 Polymarket dust 残余，建议用明确的清理记录归零，而不是默默删历史成交。
- ft 与官方仅差 `~1e-4`：通常是浮点/显示精度，不作为脏数据处理。
- 官方有、ft 没有：导入漏单或 ticker slug/outcome 映射错，回到 Activity/trades 导入流程查。

5. 扫描 security CSV 币种字段与账户币种不一致。这个会污染审计字段，通常不影响 snapshot 计算，但应作为清洁任务处理。

```bash
cd ~/.ft
python3 - <<'PY'
import csv, yaml
from pathlib import Path
acc=yaml.safe_load(open('accounts.yaml',encoding='utf-8'))
cur={}
raw=acc.get('accounts',{})
if isinstance(raw,list):
    for a in raw: cur[a['name']]=a.get('currency')
elif isinstance(raw,dict):
    for k,v in raw.items():
        if isinstance(v,dict) and 'currency' in v: cur[k]=v.get('currency')
        if k=='security' and isinstance(v,dict):
            for n,d in v.items(): cur[n]=d.get('currency')
for p in sorted(Path('records/security').glob('*.csv')):
    with open(p,encoding='utf-8') as f:
        try: rows=list(csv.DictReader(f))
        except Exception: continue
    for i,r in enumerate(rows,2):
        acct=r.get('account_name'); c=r.get('currency')
        if acct in cur and c and c != cur[acct]:
            print(p.name, i, acct, 'record', c, 'account', cur[acct], r.get('action'), r.get('ticker'), r.get('amount'))
PY
```

## 报告口径

- 不要把“ft verify 通过”说成“数据一定正确”；它只证明 CSV 与 snapshot 一致。
- 分清：真实脏数据、成本法导致的怪显示、行情/市值拉取问题、审计字段脏但不影响当前持仓。
- 涉及清理持仓/改历史记录前，先列出证据并让用户确认。