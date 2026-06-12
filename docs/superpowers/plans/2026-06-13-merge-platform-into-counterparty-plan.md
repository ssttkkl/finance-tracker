# 合并 platform→counterparty 实施方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 platform 列，统一为 9 列 CSV；convert 阶段完成 counterty/description 规范化

**Architecture:** `_normalize_counterparty()` 替代 `_infer_platform()` 的直接调用，品牌匹配→O2O剥离→原样的三级 fallthrough；存量数据一次迁移脚本

**Tech Stack:** Python, pytest, CSV

**Spec:** `docs/superpowers/specs/2026-06-13-merge-platform-into-counterparty-design.md`

---

### Task 1: models.py — 去掉 platform 列

**Files:**
- Modify: `src/ft/models.py`

- [ ] **Step 1: 修改 CSV_FIELDS**

```python
# 改前
CSV_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]

# 改后
CSV_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]
```

- [ ] **Step 2: 运行现有测试确认没有因字段数减少崩溃**

Run: `cd ~/.hermes/skills/finance/finance-tracker && uv run pytest tests/ -x -q --tb=short`

Expected: 部分测试可能因 platform 不存在而失败（后续 task 修复）

- [ ] **Step 3: Commit**

```bash
git add src/ft/models.py
git commit -m "refactor: remove platform from CSV_FIELDS (10→9)"
```

---

### Task 2: convert.py — 核心函数 `_normalize_counterparty()`

**Files:**
- Modify: `src/ft/convert.py`
- Test: `tests/test_convert.py`（如不存在则创建）

- [ ] **Step 1: 写 `_strip_platform_prefix()` 函数**

在 `_strip_payment_prefix` 之后加入：

```python
# O2O 中间商前缀 → 剥离
PLATFORM_PREFIXES = [
    "美团App",
    "饿了么",
    "大众点评",
    "高德团购",
]

def _strip_platform_prefix(counterparty: str) -> str:
    """剥离 O2O 中间商前缀，返回商铺名"""
    if not counterparty:
        return counterparty
    for prefix in PLATFORM_PREFIXES:
        if counterparty.startswith(prefix):
            stripped = counterparty[len(prefix):].strip()
            return stripped if stripped else counterparty
    return counterparty
```

- [ ] **Step 2: 写 `_normalize_counterparty()` 函数**

在 `_infer_platform` 之后加入：

```python
def _normalize_counterparty(raw_cp: str, raw_desc: str, source: str) -> tuple[str, str]:
    """品牌匹配/O2O剥离/原样 三级 fallthrough
    Returns (counterparty, enriched_description)
    """
    import re

    # Step 0: 剥离支付前缀
    cp = _strip_payment_prefix(raw_cp)

    # Step 1: 品牌匹配
    brand = _infer_platform(cp, raw_desc, source)
    if brand:
        # 从 cp 中去掉匹配到的品牌关键词，残留迁移到 desc
        leftover = _extract_leftover(cp, brand)
        enriched = raw_desc
        if leftover and leftover != brand:
            enriched = f"{leftover} | {raw_desc}" if raw_desc else leftover
        return brand, enriched

    # Step 2: O2O 前缀剥离
    stripped = _strip_platform_prefix(cp)
    if stripped != cp:
        return stripped, raw_desc

    # Step 3: 原样保留
    return cp, raw_desc


def _extract_leftover(cp: str, brand: str) -> str:
    """从 cp 中移除品牌名，返回残留部分"""
    import re

    # 精确匹配移除
    pattern = re.compile(re.escape(brand), re.IGNORECASE)
    cleaned = pattern.sub("", cp, count=1)

    # 清理残留的标点/空格/括号
    cleaned = re.sub(r'\s*[（(][^)）]*[)）]\s*', '', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned)
    cleaned = cleaned.strip(' ·-—。，,')

    # 再剥离一次支付前缀（残留中可能还有）
    cleaned = _strip_payment_prefix(cleaned)

    return cleaned
```

- [ ] **Step 3: 加单元测试**

创建 `tests/test_convert_normalize.py`：

```python
"""测试 _normalize_counterparty"""
import pytest
from ft.convert import _normalize_counterparty, _strip_platform_prefix


class TestStripPlatformPrefix:
    def test_meituan_prefix(self):
        assert _strip_platform_prefix("美团App麦当劳") == "麦当劳"

    def test_eleme_prefix(self):
        assert _strip_platform_prefix("饿了么永和大王") == "永和大王"

    def test_dianping_prefix(self):
        assert _strip_platform_prefix("大众点评XXKTV") == "XXKTV"

    def test_no_prefix(self):
        assert _strip_platform_prefix("麦当劳") == "麦当劳"

    def test_empty(self):
        assert _strip_platform_prefix("") == ""

    def test_only_prefix(self):
        # 只有前缀没内容 → 保留原值
        assert _strip_platform_prefix("美团App") == "美团App"


class TestNormalizeCounterparty:
    def test_brand_match_jd(self):
        cp, desc = _normalize_counterparty("安尔雅家具京东自营旗舰店", "", "icbc_credit")
        assert cp == "京东"
        assert "安尔雅家具" in desc

    def test_brand_match_mcdonalds(self):
        cp, desc = _normalize_counterparty("美团App麦当劳麦咖啡(北京武圣", "", "icbc_credit")
        assert cp == "麦当劳"
        assert "麦咖啡" in desc

    def test_brand_match_luckin(self):
        cp, desc = _normalize_counterparty("luckin coffee", "订单付款", "icbc_credit")
        assert cp == "瑞幸咖啡"
        assert "订单付款" in desc

    def test_o2o_small_shop(self):
        cp, desc = _normalize_counterparty("美团App渝八两重庆鸡公煲", "", "icbc_credit")
        # 未命中品牌 → 走 O2O 前缀剥离
        assert cp == "渝八两重庆鸡公煲"
        assert desc == ""

    def test_o2o_eleme(self):
        cp, desc = _normalize_counterparty("饿了么永和大王(建外SOHO店)", "", "icbc_credit")
        assert cp == "永和大王(建外SOHO店)"

    def test_no_match_keep_original(self):
        cp, desc = _normalize_counterparty("先享后付订单到期扣款", "", "icbc_credit")
        assert cp == "先享后付订单到期扣款"
        assert desc == ""

    def test_xian_qi_hou_fu(self):
        # 先骑后付 → 美团（整个 cp 是触发词，无残留）
        cp, desc = _normalize_counterparty("先骑后付", "", "wechat")
        assert cp == "美团"
        assert "先骑后付" in desc

    def test_payment_prefix_stripped_first(self):
        cp, desc = _normalize_counterparty("美团支付-luckin coffee", "订单付款", "icbc_credit")
        assert cp == "瑞幸咖啡"
        assert "订单付款" in desc

    def test_company_name_unchanged(self):
        cp, desc = _normalize_counterparty("北京屏芯科技有限公司", "工资", "icbc_debit")
        assert cp == "北京屏芯科技有限公司"
        assert desc == "工资"
```

- [ ] **Step 4: 运行新测试确认通过**

Run: `cd ~/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_convert_normalize.py -v`

Expected: 所有 11 个测试 PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/convert.py tests/test_convert_normalize.py
git commit -m "feat: _normalize_counterparty — 品牌匹配+O2O剥离三级fallthrough"
```

---

### Task 3: convert.py — 改造 _read_alipay_raw / _read_wechat_raw

**Files:**
- Modify: `src/ft/convert.py`（`_read_alipay_raw`、`_read_wechat_raw`、`_pair_refunds`）

- [ ] **Step 1: 改造 `_read_alipay_raw`**

在函数内（line ~298-306），把：

```python
        raw.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": counterparty,
            "description": desc[:80],
            "category": category,
            "txn_type": txn_type,
            "platform": _infer_platform(counterparty, desc[:80], "alipay"),
        })
```

改为：

```python
        normalized_cp, enriched_desc = _normalize_counterparty(counterparty, desc[:80], "alipay")
        raw.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": normalized_cp,
            "description": enriched_desc[:80],
            "category": category,
            "txn_type": txn_type,
        })
```

- [ ] **Step 2: 改造 `_read_wechat_raw`**

同理（line ~383-392），改为调用 `_normalize_counterparty()`，去掉 `platform` 字段。

- [ ] **Step 3: 改造 `_pair_refunds` 中的孤退款**

函数内（line ~196-206）的孤退款追加，把 `"platform": ref.get("platform", "")` 去掉：

```python
        others.append({
            "date": ref["date"],
            "amount": ref["amount"],
            "currency": ref.get("currency", "CNY"),
            "payment_method": ref["payment_method"],
            "card_number": ref.get("card_number", ""),
            "counterparty": _strip_payment_prefix(ref["counterparty"]),
            "description": ref["description"],
            "category": "income",
            # 不再有 "platform" 字段
        })
```

- [ ] **Step 4: 现有 convert 测试调整**

搜索所有测试中对 `platform` 的引用：

Run: `cd ~/.hermes/skills/finance/finance-tracker && rg "platform" tests/test_convert*.py`

逐步更新每个断言：去掉 platform 字段断言，新增 counterparty 标准化断言。

- [ ] **Step 5: 运行全量测试**

Run: `cd ~/.hermes/skills/finance/finance-tracker && uv run pytest tests/ -x -q --tb=short`

Expected: 所有现有测试 PASS（或可控失败数）

- [ ] **Step 6: Commit**

```bash
git add src/ft/convert.py tests/
git commit -m "refactor: convert pipeline 使用 _normalize_counterparty，去掉 platform 输出"
```

---

### Task 4: append.py — 去掉 platform 列写入

**Files:**
- Modify: `src/ft/append.py`

- [ ] **Step 1: 找到 CSV 写入代码**

用 `search_files` 在 append.py 中搜索 `writer` 或 `csv`：

```python
# 找到类似这样的代码：
writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
```

确认 `CSV_FIELDS` 已从 models 导入且已是 9 列，无需额外改动。如果有硬编码的 10 列 header，改为 9 列。

- [ ] **Step 2: 搜索 append.py 中 platform 的其他引用**

Run: `cd ~/.hermes/skills/finance/finance-tracker && rg "platform" src/ft/append.py`

如果有，逐一处理。

- [ ] **Step 3: Commit**

```bash
git add src/ft/append.py
git commit -m "refactor: append.py 写 9 列 CSV（无 platform）"
```

---

### Task 5: merge.py / cli.py — 去掉 platform 依赖

**Files:**
- Modify: `src/ft/merge.py`
- Modify: `src/ft/cli.py`

- [ ] **Step 1: merge.py**

搜索 platform 引用：

Run: `rg "platform" src/ft/merge.py`

处理方式：
- 如果 platform 用于去重比较 → 去掉该比较维度
- 如果 platform 用于输出 → 删掉写入逻辑

- [ ] **Step 2: cli.py**

搜索 `--platform` 参数：

Run: `rg "platform" src/ft/cli.py`

如果 `ft add` 有 `--platform` 选项 → 删除该参数和相关代码。

- [ ] **Step 3: 搜索全项目残留的 platform 字段引用**

Run: `cd ~/.hermes/skills/finance/finance-tracker && rg "platform" src/ tests/ --type py`

确保所有引用都已处理或确认合理（如 `_infer_platform` 函数名改为 `_match_brand` 或保持原名，函数内部逻辑不变）。

- [ ] **Step 4: 运行全量测试**

Run: `cd ~/.hermes/skills/finance/finance-tracker && uv run pytest tests/ -x -q --tb=short`

Expected: PASS（如个别测试失败，定位并修复）

- [ ] **Step 5: Commit**

```bash
git add src/ft/merge.py src/ft/cli.py
git commit -m "refactor: merge/cli 去掉 platform 引用"
```

---

### Task 6: 存量迁移脚本

**Files:**
- Create: `scripts/migrate_drop_platform.py`

- [ ] **Step 1: 写迁移脚本**

```python
#!/usr/bin/env python3
"""一次性迁移：删 platform 列，platform 非空覆盖 counterty，platform 为空走 normalize"""
import csv
import sys
import os
from pathlib import Path

# 需要调整：在 finance-tracker 环境内运行
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from ft.convert import _normalize_counterparty

FT_DIR = Path.home() / ".ft"
RECORDS_DIR = FT_DIR / "records"

NEW_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]

def migrate_file(csv_path: Path):
    """迁移单个 CSV 文件"""
    rows = []
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            cp = row.get("counterparty", "")
            desc = row.get("description", "")
            plat = row.get("platform", "").strip()
            source = row.get("source", "")

            if plat:
                # platform 有值 → 直接覆盖 counterty
                row["counterparty"] = plat
                # description 不动（存量无法安全拆分混合 cp）
            else:
                # platform 为空 → 跑完整的 normalize（让新规则作用于存量）
                new_cp, new_desc = _normalize_counterparty(cp, desc, source)
                row["counterparty"] = new_cp
                row["description"] = new_desc

            # 删除 platform 列
            del row["platform"]
            rows.append(row)

    # 写回
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=NEW_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def main():
    for subdir in ["cash", "loan"]:
        sub = RECORDS_DIR / subdir
        if not sub.exists():
            continue
        for f in sorted(sub.glob("*.csv")):
            print(f"  migrating {f}")
            migrate_file(f)
    print("✅ 迁移完成")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 在数据备份后执行迁移**

```bash
cd ~/.ft && git add -A && git commit -m "backup: pre-migration snapshot"

cd ~/.hermes/skills/finance/finance-tracker
uv run python scripts/migrate_drop_platform.py
```

- [ ] **Step 3: 验证迁移结果**

```bash
# 检查 csv 列数
head -1 ~/.ft/records/cash/2026-06-08.csv | tr ',' '\n' | wc -l
# Expected: 9

# 验证 snapshot 重建
cd ~/.hermes/skills/finance/finance-tracker && uv run ft verify --fix
```

- [ ] **Step 4: 验证数据完整性**

```bash
# 快照余额不应变化（或差异极小）
cd ~/.ft && git diff snapshot.yaml
```

- [ ] **Step 5: 如果验证通过，提交迁移**

```bash
cd ~/.ft && git add -A && git commit -m "migrate: 合并 platform→counterparty, 9列CSV"
```

- [ ] **Step 6: 提交迁移脚本到仓库**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add scripts/
git commit -m "tool: migrate_drop_platform.py 存量迁移脚本"
```

---

### Task 7: SKILL.md 更新

**Files:**
- Modify: `SKILL.md`

- [ ] **Step 1: 更新 CSV 字段表**

在「账单字段」节，替换 10 列表为 9 列表（去掉 platform 行）。

- [ ] **Step 2: 更新命令表**

如果命令表中有涉及 platform 参数的描述，去掉。

- [ ] **Step 3: 更新 convert 流水线描述**

在流水线说明中，platform 的提及改为 counterparty 规范化。

- [ ] **Step 4: Commit**

```bash
git add SKILL.md
git commit -m "docs: SKILL.md 更新 9 列 CSV，去掉 platform 引用"
```

---

### Task 8: 最终验证

- [ ] **Step 1: 全量测试**

```bash
cd ~/.hermes/skills/finance/finance-tracker
uv run pytest tests/ -v --tb=short
```

Expected: 所有测试 PASS

- [ ] **Step 2: 功能验证**

```bash
# 查账（应正常）
uv run ft acct

# 加一笔记录（应正常写入 9 列）
uv run ft add -a -18.5 -c 麦当劳 --account 工行信用卡\(1200\) -d "巨无霸套餐"

# 构造一条 convert 测试（如果有本地测试账单）
```

- [ ] **Step 3: 推送**

```bash
git push origin main
```
