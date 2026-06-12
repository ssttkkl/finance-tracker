1|# 建行新版交易地点优化 — 实施计划
2|
3|> **For agentic workers:** Use subagent-driven-development.
4|
5|**Goal:** 重写 `src/ft/importers/ccb_debit.py`，用交易地点列提取真实 counterparty，删除 `_pair_ccb_refunds`，改用 `_pair_refunds`。
6|
7|**Architecture:** `read_ccb_debit` 调用 `_extract_ccb_counterparty` 从交易地点提取 (counterparty, payment_source)。退款配对交给 `convert.py` 的 `_pair_refunds`。
8|
9|**Tech Stack:** Python 3.11+, uv, xlrd, pytest
10|
11|---
12|
13|### Task 1: 写测试（RED）
14|
15|**Files:**
16|- Modify: `tests/test_ccb_debit.py`
17|
18|重写测试，用新版数据格式。
19|
20|- [ ] **Step 1: 更新 _make_xls helper，交易地点列用真实值**
21|
22|```python
23|def _make_xls(card: str, rows: list[tuple]) -> str:
24|    """rows: [(摘要, 币别, 钞汇, 交易日期, 交易金额, 账户余额, 交易地点, 对方账号与户名), ...]
25|    交易地点列：新版有真实值（如 '财付通-微信支付-瑞幸咖啡'），旧版为 '***'
26|    """
27|    ...
28|```
29|
30|- [ ] **Step 2: 测试新版 counterparty 提取**
31|
32|```python
33|def test_location_counterparty_wechat_pay():
34|    path = _make_xls("6217000000000002820", [
35|        ("消费", "人民币元", "钞", "20260128", "-4.23", "1,845.76",
36|         "财付通-微信支付-瑞幸咖啡", "Z******0010/***咖啡"),
37|    ])
38|    recs = read_ccb_debit(path)
39|    assert recs[0]["counterparty"] == "瑞幸咖啡"
40|
41|def test_location_counterparty_alipay():
42|    path = _make_xls("6217000000000002820", [
43|        ("消费", "人民币元", "钞", "20260321", "-2598.95", "5,974.20",
44|         "支付宝-淘宝-于震", "Z******0010/*震"),
45|    ])
46|    recs = read_ccb_debit(path)
47|    assert recs[0]["counterparty"] == "于震"
48|
49|def test_location_counterparty_alipay_external():
50|    path = _make_xls("6217000000000002820", [
51|        ("消费", "人民币元", "钞", "20260430", "-89.50", "5,430.29",
52|         "支付宝-支付宝外部商户-上海部恩科技有限公司", "Z******0010/***公司"),
53|    ])
54|    recs = read_ccb_debit(path)
55|    assert recs[0]["counterparty"] == "上海部恩科技有限公司"
56|
57|def test_location_counterparty_meituan():
58|    path = _make_xls("6217000000000002820", [
59|        ("消费退货", "人民币元", "钞", "20260528", "22.23", "306.54",
60|         "美团支付-美团特约商户", "11000175712473/北京三快在线科技有限公司"),
61|    ])
62|    recs = read_ccb_debit(path)
63|    assert recs[0]["counterparty"] == "美团特约商户"
64|
65|def test_location_counterparty_paypal():
66|    path = _make_xls("6217000000000002820", [
67|        ("无卡自助交易", "人民币元", "钞", "20260101", "-13.99", "1,879.94",
68|         "PAYPAL_PIXIVFANBOX", "685070248160001/PAYPAL_PIXIVFANBOX"),
69|    ])
70|    recs = read_ccb_debit(path)
71|    assert recs[0]["counterparty"] == "PAYPAL_PIXIVFANBOX"
72|
73|def test_location_counterparty_direct():
74|    path = _make_xls("6217000000000002820", [
75|        ("有卡自助消费", "人民币元", "钞", "20260102", "-29.95", "1,849.99",
76|         "北京市政交通一卡通有限公司", "898111941110139/北京市政交通一卡通有限公司"),
77|    ])
78|    recs = read_ccb_debit(path)
79|    assert recs[0]["counterparty"] == "北京市政交通一卡通有限公司"
80|
81|def test_location_fallback_legacy():
82|    """旧版 *** → 回退对方户名"""
83|    path = _make_xls("6217000000000002820", [
84|        ("消费", "人民币元", "钞", "20260128", "-9.30", "1,836.46",
85|         "***", "Z******0010/***ee"),
86|    ])
87|    recs = read_ccb_debit(path)
88|    assert recs[0]["counterparty"] == "***ee"
89|```
90|
91|- [ ] **Step 3: 新版退款配对测试（用 _pair_refunds）**
92|
93|```python
94|def test_refund_pairing_with_location():
95|    """消费和退款用同样的 counterparty 提取，_pair_refunds 精确匹配"""
96|    path = _make_xls("6217000000000002820", [
97|        ("消费", "人民币元", "钞", "20260314", "-200.00", "8,539.89",
98|         "财付通-微信支付-鸟楽町居酒屋Bistro", "Z******0010/***ro"),
99|        ("消费退货", "人民币元", "钞", "20260314", "200.00", "8,739.89",
100|         "财付通-鸟楽町居酒屋Bistro", "833678836/鸟楽町居酒屋Bistro"),
101|    ])
102|    recs, tracking = read_ccb_debit(path)
103|    assert len(recs) == 0  # 全额配对删除
104|    assert len(tracking) == 1
105|    assert tracking[0]["match_type"] == "full"
106|
107|def test_orphan_refund_with_location():
108|    """22.23 美团特约商户 — counterparty 无匹配，孤退款"""
109|    path = _make_xls("6217000000000002820", [
110|        ("消费退货", "人民币元", "钞", "20260528", "22.23", "306.54",
111|         "美团支付-美团特约商户", "11000175712473/北京三快在线科技有限公司"),
112|    ])
113|    recs, tracking = read_ccb_debit(path)
114|    assert len(recs) == 1
115|    assert recs[0]["category"] == "income"
116|    assert recs[0]["amount"] == 22.23
117|    assert len(tracking) == 0  # 孤退款不进 tracking
118|```
119|
120|- [ ] **Step 4: 运行确认 RED**
121|
122|```bash
123|uv run pytest tests/test_ccb_debit.py -v
124|```
125|
126|---
127|
128|### Task 2: 重写 read_ccb_debit（GREEN）
129|
130|**Files:**
131|- Modify: `src/ft/importers/ccb_debit.py`
132|
133|- [ ] **Step 1: 新增 _extract_ccb_counterparty**
134|
135|```python
136|def _extract_ccb_counterparty(location: str) -> str:
137|    """从建行交易地点列提取纯 counterparty
138|    
139|    模式：
140|      财付通-微信支付-商户名  →  商户名
141|      支付宝-淘宝-商户名      →  商户名
142|      美团支付-商户名         →  商户名
143|      PAYPAL_XXX              →  PAYPAL_XXX
144|      直接商户名               →  直接商户名
145|      ***                     →  None（回退对方户名）
146|    """
147|    if not location or location == "***":
148|        return None
149|    
150|    # 支付源前缀映射（按长度降序匹配，避免财付通-匹配到支付宝-之前）
151|    PAYMENT_PREFIXES = [
152|        ("财付通-", ["微信支付-", "微信转账"]),
153|        ("支付宝-", ["淘宝-", "支付宝外部商户-", "支付宝-转账-"]),
154|        ("美团支付-", []),
155|    ]
156|    
157|    for prefix, subs in PAYMENT_PREFIXES:
158|        if location.startswith(prefix):
159|            rest = location[len(prefix):]
160|            for sub in subs:
161|                if rest.startswith(sub):
162|                    rest = rest[len(sub):]
163|                    break
164|            return rest
165|    
166|    return location
167|```
168|
169|- [ ] **Step 2: 重写 read_ccb_debit**
170|
171|- 调用 `_extract_ccb_counterparty` 提取 counterparty
172|- 新版的记录不包含 `card_number`/`payment_method` 中的脱敏信息，改为从交易地点推断 payment_source
173|- 退款配对交给上层 `do_convert` 的 `_pair_refunds`
174|- 返回值：`(records, [])` — tracking_pairs 留给 `_pair_refunds` 处理
175|
176|```python
177|def read_ccb_debit(path: str) -> tuple[list[dict], list[dict]]:
178|    """解析建行 XLS → (records, tracking_pairs)
179|    
180|    tracking_pairs 始终为空 — 退款配对由 convert.py 的 _pair_refunds 统一处理。
181|    """
182|    ...
183|    # counterparty
184|    cpy = _extract_ccb_counterparty(location)
185|    if cpy is None:
186|        # 旧版：回退对方户名
187|        cpy = counterparty_from_account_name(acct_name_raw)
188|    
189|    # payment_method: 从交易地点推断
190|    pm = _infer_ccb_payment_source(location)
191|    
192|    records.append({...})
193|    
194|    return records, []
195|```
196|
197|- [ ] **Step 3: _infer_ccb_payment_source**
198|
199|```python
200|def _infer_ccb_payment_source(location: str) -> str:
201|    if location.startswith("财付通-"):
202|        return "微信支付"
203|    if location.startswith("支付宝-"):
204|        return "支付宝"
205|    if location.startswith("美团支付-"):
206|        return "美团支付"
207|    if "PAYPAL" in location.upper():
208|        return "PayPal"
209|    return "建行储蓄卡({card_last4})"
210|```
211|
212|- [ ] **Step 4: 删除 _pair_ccb_refunds**
213|
214|- [ ] **Step 5: 更新 convert.py 中 ccb-debit 分支**
215|
216|在 `do_convert` 中，`ccb-debit` 分支调用 `read_ccb_debit` 得到 `(records, [])` 后，需要把退款配对交给 `_pair_refunds`：
217|
218|```python
219|elif source == "ccb-debit":
220|    rows, _ = read_ccb_debit(path)
221|    bill_type = "ccb_debit"
222|    # 退款配对：使用 _pair_refunds（与支付宝/微信一致）
223|    expenses = [r for r in rows if r["category"] == "expense"]
224|    refunds = [r for r in rows if r["category"] == "income" and "退货" in r.get("description", "")]
225|    others = [r for r in rows if r["category"] != "expense" and ("退货" not in r.get("description", ""))]
226|    rows, tracking_pairs = _pair_refunds(expenses, refunds, others)
227|```
228|
229|- [ ] **Step 6: 运行测试**
230|
231|```bash
232|uv run pytest tests/test_ccb_debit.py -v  # 新版测试 GREEN
233|uv run pytest -v  # 全量，确认不破坏现有
234|```
235|
236|---
237|
238|### Task 3: E2E 验证 + 提交
239|
240|- [ ] **Step 1: 新版 XLS 转换**
241|
242|```bash
243|uv run ft convert ~/Downloads/ccb_new/hqmx_20260610130934.xls -s ccb-debit -o /tmp/ccb_new_test.csv
244|```
245|
246|检查：
247|- counterparty 为真实商户名（非脱敏）
248|- 退款配对正确（4 对全额 + 1 孤退款）
249|- payment_source 正确
250|
251|- [ ] **Step 2: 旧版 XLS 仍兼容**
252|
253|```bash
254|uv run ft convert ~/Downloads/ccb_bills/hqmx_20260609191736.xls -s ccb-debit -o /tmp/ccb_old_test.csv
255|```
256|
257|旧版 `***` → 回退对方户名，行为不变。
258|
259|- [ ] **Step 3: 提交**
260|
261|```bash
262|git add -A && git commit -m "feat: optimize CCB debit with real location data, use _pair_refunds"
263|```
264|