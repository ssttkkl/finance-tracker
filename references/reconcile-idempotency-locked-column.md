# reconcile 幂等 + locked 人工锁定列

## 诉求
`ft reconcile` 必须幂等：已标记过的行（自动或人工），再次执行不会被重标、不会覆盖人工修正。多次跑收敛、无 git diff。

## 方案 B：新增 `locked` 列（CSV 第 11 列）
`locked=1` 表示该行被人工锁定，reconcile **完全跳过**：不去重、不配对、不单腿标记，仅原样写回。

### 实现要点（3 处代码 + 1 处审计 + 存量迁移）
1. **`models.py` `CSV_FIELDS`** — 10 → 11 列，末尾加 `locked`。
2. **`reconcile.py` `do_reconcile`** — 把 `scoped` 拆成两拨：
   ```python
   scoped_locked = [r for r in scoped if _is_locked(r)]   # 仅原样写回
   scoped_active = [r for r in scoped if not _is_locked(r)] # 参与去重/配对/单腿
   kept, removed, pairs = dedup_with_pairs(scoped_active)
   ...
   # 写回时：非 scoped 行原样 + scoped_locked 原样 + kept
   ```
   辅助：`_is_locked(row) -> bool` 判 `str(row.get("locked","")).strip() == "1"`。
   关键：锁定行**不进任何识别管线**，但必须走写回分支（否则会被当作删除丢失）。
3. **`transfer.py` `new_row`** — `ft transfer` 手动写入的两条腿都加 `"locked": "1"`，保护人工转账不被 reconcile 翻动。
4. **`reconcile.py` `_write_audit` 的 `fields` 列表** — 这是**独立于 `CSV_FIELDS` 的第二个硬编码列表**，必须同步插入 `"locked"`（放在 `transfer_account` 之后、`record_file` 之前），否则 `_clean_row` 带出 locked 键时 `DictWriter` 抛 `ValueError: dict contains fields not in fieldnames: 'locked'`。这是本类改动最容易漏的地方。

### 一个反直觉的发现
**单腿型转账本来就幂等**：`transfer_rules.classify_single_leg` 已有 `category not in (income,expense) → None` 保护，已是 transfer 的行天然被跳过。所以 `locked` 列的真正增量不是"防止自动重标"（那已经成立），而是**保护用户手动修正**——例如用户手动把某笔 transfer 改回 income 想强制它算收入，reconcile 从此不再翻它。写幂等测试时要区分这两类，"已锁行不动"和"未锁但已是 transfer 的行不动"是两个独立断言。

## 存量数据批量迁移（10 → 11 列）
records CSV（cash/loan，本例 1590 个文件）是旧的 10 列，需批量加空 `locked` 列。用 `execute_code` 跑一次性脚本：
```python
import csv, glob, os
F10 = ["date","amount","currency","counterparty","description",
       "category","account_name","source","bill_source","transfer_account"]
F11 = F10 + ["locked"]
base = os.path.expanduser("~/.ft/records")
for fp in glob.glob(f"{base}/cash/*.csv") + glob.glob(f"{base}/loan/*.csv"):
    rows = list(csv.reader(open(fp, encoding="utf-8", newline="")))
    if not rows or rows[0] == F11: continue
    assert rows[0] == F10, f"意外表头 {fp}: {rows[0]}"  # 不静默容忍未知表头
    with open(fp, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f); w.writerow(F11)
        for r in rows[1:]: w.writerow(r + [""])
```
迁移后 `ft verify` 应仍全绿（reconcile 读取用 `row.get(field,"")` 补齐，旧文件读进来不崩；迁移只是让磁盘表头统一）。迁移单独提一个 commit，隔离后续 reconcile 的 diff 观测。

## 幂等真实数据验证（不靠单测，靠真账）
1. 迁移后先 `ft commit` 隔离基线。
2. 连跑两次 `ft reconcile`，两次都应 **0 git 改动**（`git status --short | wc -l` == 0）。存量若早已收敛，第一次就 0 diff，这本身是幂等的初步证据。
3. **transfer e2e**：`ft transfer --from A --to B --amount 0.01 --description 测试` → 检查两腿 `locked=='1'` → 跑 `ft reconcile` → 确认两行 locked 保持不变、未被卷入去重/配对 → `git reset --hard <基线commit>` 彻底回滚，不污染真实账。

## 通用纪律：区分「既有失败」与「本次回归」
改动后全套跑出几个 FAIL 时，**先证明它们是不是自己引入的**，别急着修：
```bash
git stash push -u                    # 暂存本次全部改动
python -m pytest <那几个失败用例> -q  # 干净 HEAD 上重跑
git stash pop                        # 恢复
```
若干净 HEAD 上同样失败 → 是仓库既有遗留（如实现已加 ValueError 但测试还期望旧的返回 None），与本次无关，用 `pytest --deselect <用例>` 隔离后继续，并在汇报中明确"这 N 个失败改动前就存在"。**绝不把既有失败当成自己的锅去改，也绝不把自己引入的回归甩锅给"既有"**——git stash 到干净 HEAD 实测是唯一可信裁决。本例 3 个 test_convert 失败（零金额跳过/无日期行/短行保护）经此法证明是既有的，真正回归只有 `_write_audit` 的 fields 缺列，已修。

---

## 后续加固（同一轮 review 追加的两处）

### 加固 1：管线归属判定用 `id()` 集合，不用 `row in scoped`
`do_reconcile` 写回时判断某行是否属于 scope，原代码 `if row in scoped:` 走 dict `__eq__`（**按值相等**），有两个问题：① O(n²)；② **两条内容完全相同的 dict（真重复未去重时）会互相误判归属**。全文件其它地方（`used_ids`、`matched_candidate_ids`）都用 `id()`，这里是唯一不一致处。
```python
scoped_ids = {id(row) for row in scoped}   # 紧跟 scoped 定义
...
for row in entries:
    if id(row) in scoped_ids:              # 原为 `if row in scoped:`
        continue
```
**通用教训**：在同一批 dict 对象上做「是否属于某子集」判断时，只要对象在内存里是同一个（没有 round-trip 序列化），一律用 `id()` 集合而非 `in list`——值相等在有重复内容的数据里会咬人。

### 加固 2：test-vs-impl 契约冲突——用 git 历史裁定谁是新契约
之前 deselect 的 3 个 convert 失败，本轮真正修掉了（不是继续隔离）。冲突本质是**实现和测试各自演进、语义对不上**。裁定步骤：
```bash
# 谁更新？被 -S 的字符串出现在哪个 commit，日期新的那个代表当前契约意图
git log -1 --format="%h %ai %s" -S "工行借记卡行列数不足" -- src/ft/convert.py   # 实现：抛 ValueError（6-28，较新）
git log -1 --format="%h %ai %s" -S "不足13列的行应返回 None" -- tests/test_convert.py  # 测试：期望 None（6-13，initial）
```
- **无日期行 / 短行(<13列)**：实现较新且抛 `ValueError` 符合本项目「禁止静默丢弃、非预期数据抛错中断」的铁律 → **保留实现，改测试**为 `pytest.raises(ValueError, match="无法提取日期")`。测试名也从 `test_无日期行_返回空` 改为 `test_无日期行_抛错` 以反映新契约。
- **支付宝「不计收支」+金额0**（预授权解冻/冻结解冻，无资金流动）：这是实现**漏掉**的 case——原逻辑只在「不计收支 AND 交易状态=交易关闭」时跳过，而测试数据无「交易状态」列。补实现 `if direction == "不计收支" and amount == 0: continue`（与既有「不计收支+交易关闭」跳过同源、保守无歧义）→ **改实现让测试通过**。

**契约冲突不要自己拍板选边**：涉及数据取舍（抛错中断 vs 静默跳过）的分歧先用 clarify 问用户定契约，再据此决定改测试还是改实现。本例用户选「保留 ValueError（零容忍铁律）」。改完全套回到 360 passed / 0 failed，不再需要任何 deselect。
