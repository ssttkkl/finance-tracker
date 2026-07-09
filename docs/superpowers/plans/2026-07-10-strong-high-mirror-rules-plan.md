# Strong/High Mirror Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把一批当前只会进入 `possible_mirror_weak_30s_cross_source` / review 的稳定镜像消费，升级成基于“强侧更具体、弱侧更通道化”的 strong/high auto-drop 规则。

**Architecture:** 只增量修改 `src/ft/mirror_rules.py`，保留 `detect_mirror_pairs()` 的公开接口、`dedup.py` 只消费 `auto_drop_pairs` 的边界，以及 `reconcile.py` 只消费 `review_pairs` 的边界。新规则通过补充文本强弱判断、通道关键词识别和极小 alias 兜底，把部分宽松弱候选提升为新的高置信 `auto_drop_pairs`，同时保留 refund / social / multi-candidate 的安全降级语义。

**Tech Stack:** Python 3、pytest、finance-tracker 现有 `ft.mirror_rules` / `ft.reconcile` / `ft.dedup` 测试体系

---

## File map

- Modify: `src/ft/mirror_rules.py`
  - 增加“文本更具体 / 更像通道残影”的判断函数
  - 在现有 candidate 分类流程里新增 strong/high 分支
  - 保持 `detect_mirror_pairs(rows)`、`MirrorPair`、`MirrorDetectionResult` 不变
- Modify: `tests/test_mirror_rules.py`
  - 先写失败测试，覆盖新增 strong/high 命中与安全降级
- Modify: `tests/test_reconcile.py`
  - 验证升级后的样本不再进入 pending，而是直接自动删弱侧
- Modify: `tests/test_dedup.py`（仅当需要回归保护时）
  - 验证 dedup 仍然只消费 `auto_drop_pairs`

## Implementation notes

- 不新增 schema，不改 `ai_working.csv` 字段。
- 不改 `dedup.py` / `reconcile.py` 的职责，除非新增测试证明确实需要极小联动修改。
- 先写测试，确认 RED；再写最小实现，确认 GREEN。
- alias 只允许做小规模兜底，不能发展成商户大字典。

### Task 1: 升级“具体商户 vs 信用卡泛化消费”为 high auto-drop

**Files:**
- Modify: `tests/test_mirror_rules.py`
- Modify: `src/ft/mirror_rules.py`
- Test: `tests/test_mirror_rules.py`

- [ ] **Step 1: Write the failing test**

在 `tests/test_mirror_rules.py` 追加这个测试，放在宽松弱候选测试附近：

```python
def test_upgrades_specific_merchant_vs_icbc_credit_generic_consume_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 10:00:06",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_credit"
    assert pair.rule_hint == "card_channel_purchase_mirror"
    assert pair.confidence == "high"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_upgrades_specific_merchant_vs_icbc_credit_generic_consume_to_high_auto_drop -v`

Expected: FAIL，因为当前这组样本还是 `possible_mirror_weak_30s_cross_source` review，`auto_drop_pairs` 长度为 0。

- [ ] **Step 3: Write minimal implementation**

在 `src/ft/mirror_rules.py` 中新增最小能力：

1. 在关键词常量附近补一组“通道弱文本”关键词：

```python
WEAK_GENERIC_TEXT_KEYWORDS = (
    "消费",
    "财付通",
    "微信支付",
    "支付宝消费",
    "支付宝",
    "网络技术",
    "银联",
    "快捷支付",
)
```

2. 在 `_full_text()` 下方新增两个判断函数：

```python
def _looks_like_generic_channel_text(row: dict) -> bool:
    text = _full_text(row)
    return any(keyword in text for keyword in WEAK_GENERIC_TEXT_KEYWORDS)


def _looks_like_specific_merchant_text(row: dict) -> bool:
    text = _full_text(row)
    if not text.strip():
        return False
    if _looks_like_generic_channel_text(row):
        return False
    if any(keyword in text for keyword in WECHAT_SOCIAL_KEYWORDS):
        return False
    if any(keyword in text for keyword in QR_COLLECT_KEYWORDS):
        return False
    return True
```

3. 在 `_classify_candidate()` 的 `icbc_credit_card_channel` 分支里，把现有“必须 `_cross_verify()` 才能 high”扩成“交叉文本互证 或 结构强弱互证”：

```python
    if candidate.weak_channel_kind == "icbc_credit_card_channel":
        if candidate.candidate_count != 1:
            return None
        if _cross_verify(strong_row, weak_row):
            confidence = "low" if candidate.merchant_signal_kind == "refund" else "high"
            bucket = "review" if confidence == "low" else "auto"
            return bucket, MirrorPair(strong_row, weak_row, "card_channel_purchase_mirror", confidence)
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and _looks_like_generic_channel_text(weak_row)
        ):
            return "auto", MirrorPair(strong_row, weak_row, "card_channel_purchase_mirror", "high")
        return None
```

- [ ] **Step 4: Run test to verify it passes**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_upgrades_specific_merchant_vs_icbc_credit_generic_consume_to_high_auto_drop -v`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_mirror_rules.py src/ft/mirror_rules.py
git commit -m "feat: promote generic credit mirror matches"
```

### Task 2: 升级“具体商户 vs 借记卡通道文本”为 high auto-drop

**Files:**
- Modify: `tests/test_mirror_rules.py`
- Modify: `src/ft/mirror_rules.py`
- Test: `tests/test_mirror_rules.py`

- [ ] **Step 1: Write the failing test**

在 `tests/test_mirror_rules.py` 追加：

```python
def test_upgrades_specific_merchant_vs_icbc_debit_generic_channel_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-08 08:01:01",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "美团买菜",
            "description": "美团买菜",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        {
            "record_id": "b1",
            "date": "2026-06-08 08:01:20",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "支付宝(中国)网络技术有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "alipay"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.rule_hint == "debit_purchase_mirror_icbc"
    assert pair.confidence == "high"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_upgrades_specific_merchant_vs_icbc_debit_generic_channel_to_high_auto_drop -v`

Expected: FAIL，因为当前这组样本不会进入 `auto_drop_pairs`。

- [ ] **Step 3: Write minimal implementation**

在 `_classify_candidate()` 的 `icbc_debit_wechat_gateway` / `icbc_debit_alipay_gateway` / `icbc_debit_unionpay_gateway` 分支中，先保留已有 refund/social/multi-candidate 安全阀，再补一个“结构强弱互证也可 high”的明确分支：

```python
    if candidate.weak_channel_kind in {
        "icbc_debit_wechat_gateway",
        "icbc_debit_alipay_gateway",
        "icbc_debit_unionpay_gateway",
    }:
        if candidate.merchant_signal_kind in {"refund", "social_flow"}:
            return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")
        if candidate.candidate_count > 1:
            return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")
        if _cross_verify(strong_row, weak_row):
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and _looks_like_generic_channel_text(weak_row)
        ):
            return "auto", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "high")
        return "review", MirrorPair(strong_row, weak_row, "debit_purchase_mirror_icbc", "low")
```

这样不会改变原有安全阀，只是让“结构上已经很稳”的样本直接升级 high。

- [ ] **Step 4: Run test to verify it passes**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_upgrades_specific_merchant_vs_icbc_debit_generic_channel_to_high_auto_drop -v`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_mirror_rules.py src/ft/mirror_rules.py
git commit -m "feat: promote generic debit mirror matches"
```

### Task 3: 用小规模 alias 兜底“双方都具体但叫法不同”的稳定样本

**Files:**
- Modify: `tests/test_mirror_rules.py`
- Modify: `src/ft/mirror_rules.py`
- Test: `tests/test_mirror_rules.py`

- [ ] **Step 1: Write the failing test**

在 `tests/test_mirror_rules.py` 追加一个小规模 alias 样本。用真实风格但不扩大范围，例如品牌名 vs 公司主体名：

```python
def test_uses_small_alias_set_for_specific_brand_vs_settlement_entity_match():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-18 18:30:01",
            "amount": "-19.90",
            "currency": "CNY",
            "counterparty": "库迪咖啡",
            "description": "库迪咖啡",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-18 18:30:05",
            "amount": "-19.90",
            "currency": "CNY",
            "counterparty": "Cotti Coffee",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    assert result.auto_drop_pairs[0].rule_hint == "card_channel_purchase_mirror"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_uses_small_alias_set_for_specific_brand_vs_settlement_entity_match -v`

Expected: FAIL，因为当前双方文本既不互为子串，也不属于已知通道弱文本互证，无法进 high。

- [ ] **Step 3: Write minimal implementation**

在 `src/ft/mirror_rules.py` 中新增极小 alias 集与匹配函数：

```python
MERCHANT_ALIAS_SETS = (
    {"库迪咖啡", "Cotti Coffee"},
)


def _matches_alias_group(a: dict, b: dict) -> bool:
    text = _full_text(a) + " " + _full_text(b)
    for alias_group in MERCHANT_ALIAS_SETS:
        if all(alias in text for alias in alias_group):
            return True
    return False
```

然后把 Task 1 的 `icbc_credit_card_channel` 分支扩成：

```python
        if (
            candidate.merchant_signal_kind == "merchant_consume"
            and _looks_like_specific_merchant_text(strong_row)
            and (
                _looks_like_generic_channel_text(weak_row)
                or _matches_alias_group(strong_row, weak_row)
            )
        ):
            return "auto", MirrorPair(strong_row, weak_row, "card_channel_purchase_mirror", "high")
```

注意：alias 只是和结构证据一起使用，不能单独决定 high。

- [ ] **Step 4: Run test to verify it passes**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py::test_uses_small_alias_set_for_specific_brand_vs_settlement_entity_match -v`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_mirror_rules.py src/ft/mirror_rules.py
git commit -m "feat: add small merchant alias fallback"
```

### Task 4: 保护 refund / social / multi-candidate 安全阀不被新规则破坏

**Files:**
- Modify: `tests/test_mirror_rules.py`
- Modify: `src/ft/mirror_rules.py`（仅当测试暴露出回归）
- Test: `tests/test_mirror_rules.py`

- [ ] **Step 1: Write the failing regression tests**

如果文件中还没有完全覆盖，就追加下面两个保护性测试：

```python
def test_does_not_upgrade_refund_chain_generic_credit_match_to_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
            "offset_group": "refund_001",
            "offset_role": "expense",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 10:00:06",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
            "offset_group": "refund_002",
            "offset_role": "expense",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1


def test_does_not_upgrade_multi_candidate_generic_gateway_match_to_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "外卖平台",
            "description": "付款",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "a2",
            "date": "2026-06-01 09:42:04",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "咖啡品牌",
            "description": "付款",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "支付宝(中国)网络技术有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].confidence == "low"
```

- [ ] **Step 2: Run tests to verify they fail only if regressions exist**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py -k "refund_chain_generic_credit or multi_candidate_generic_gateway" -v`

Expected: 如果现有逻辑已安全通过，则直接 PASS；如果新增 strong/high 分支破坏了安全阀，则 FAIL，且失败信息应显示错误进入了 `auto_drop_pairs`。

- [ ] **Step 3: Write minimal implementation**

仅在测试失败时修补 `src/ft/mirror_rules.py`，原则是：

- refund 仍然优先降级 review
- multi-candidate 仍然优先降级 review
- social/红包/群收款 不因“具体文本”判断被误升 high

如果需要，优先在 `_classify_candidate()` 中调整分支顺序，而不是增加新抽象。

- [ ] **Step 4: Run tests to verify they pass**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py -k "refund_chain_generic_credit or multi_candidate_generic_gateway" -v`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_mirror_rules.py src/ft/mirror_rules.py
git commit -m "test: protect mirror safety gates"
```

### Task 5: 验证 reconcile 集成路径会把升级样本从 pending 变成直接去重

**Files:**
- Modify: `tests/test_reconcile.py`
- Modify: `src/ft/mirror_rules.py`（仅当集成暴露问题）
- Test: `tests/test_reconcile.py`

- [ ] **Step 1: Write the failing integration test**

在 `tests/test_reconcile.py` 的 `test_reconcile_puts_loose_30s_candidates_into_ai_working_csv` 旁边追加一个“升级后不再进 pending”的测试：

```python
def test_reconcile_auto_drops_upgraded_generic_credit_mirror_without_pending(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 10:00:00", "amount": "-18.8", "currency": "CNY",
         "counterparty": "滴滴出行", "description": "先乘后付", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 10:00:06", "amount": "-18.8", "currency": "CNY",
         "counterparty": "杭州青奇科技有限公司", "description": "消费", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `PYTHONPATH=src pytest tests/test_reconcile.py::test_reconcile_auto_drops_upgraded_generic_credit_mirror_without_pending -v`

Expected: FAIL，因为当前会创建 pending 会话或保留两条 records。

- [ ] **Step 3: Write minimal implementation**

如果 Task 1 的实现已经让该测试自动通过，则此步不需要额外代码改动。

如果仍失败：
- 先检查失败是否来自 `mirror_rules.py` 判定未命中
- 仅修正 `mirror_rules.py` 的 high 分类条件
- 不修改 `reconcile.py` 的流程，除非测试明确表明其消费逻辑与 `auto_drop_pairs` 不一致

- [ ] **Step 4: Run test to verify it passes**

Run: `PYTHONPATH=src pytest tests/test_reconcile.py::test_reconcile_auto_drops_upgraded_generic_credit_mirror_without_pending -v`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_reconcile.py src/ft/mirror_rules.py
git commit -m "test: cover upgraded mirror auto drop in reconcile"
```

### Task 6: 跑聚焦回归，确认职责边界与旧行为不回退

**Files:**
- Modify: `tests/test_dedup.py`（仅当缺回归保护）
- Modify: `src/ft/mirror_rules.py`（仅当回归失败）
- Test: `tests/test_mirror_rules.py`
- Test: `tests/test_reconcile.py`
- Test: `tests/test_dedup.py`
- Test: `tests/test_reconcile_locked.py`

- [ ] **Step 1: Write any missing regression test**

如果 `tests/test_dedup.py` 里还没有“只消费 auto_drop_pairs”的直接保护测试，则追加：

```python
def test_dedup_does_not_drop_review_only_loose_cross_source_pair():
    records = [
        {
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "date": "2026-06-01 10:00:20",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    deduped = dedup_with_pairs(records)

    assert len(deduped.records) == 2
```

- [ ] **Step 2: Run focused regression suite**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py tests/test_reconcile.py tests/test_dedup.py tests/test_reconcile_locked.py -q`

Expected: 全部 PASS；如果失败，应明确是某个旧 high 规则、review 规则、locked 语义或 dedup 边界被破坏。

- [ ] **Step 3: Write minimal implementation**

只修复回归暴露的最小问题：
- 优先修分支顺序
- 优先收紧关键词条件
- 不重构文件结构
- 不扩大 alias 集

- [ ] **Step 4: Run focused regression suite again**

Run: `PYTHONPATH=src pytest tests/test_mirror_rules.py tests/test_reconcile.py tests/test_dedup.py tests/test_reconcile_locked.py -q`

Expected: 全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add tests/test_mirror_rules.py tests/test_reconcile.py tests/test_dedup.py src/ft/mirror_rules.py
git commit -m "test: lock mirror promotion regressions"
```

### Task 7: 跑广回归确认没有波及 refund / pending / convert 等周边流程

**Files:**
- Modify: `src/ft/mirror_rules.py`（仅当广回归失败）
- Test: `tests/test_ai_apply.py`
- Test: `tests/test_pending.py`
- Test: `tests/test_transfer_rules.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: No new test code in this task**

本任务不先写新测试文件，而是执行已有广回归集合，确认新增规则没有波及相邻流程。

- [ ] **Step 2: Run broader regression suite**

Run: `PYTHONPATH=src pytest tests/test_ai_apply.py tests/test_pending.py tests/test_transfer_rules.py tests/test_convert.py -q`

Expected: PASS；如果失败，失败点应清晰显示是 mirror 规则副作用还是测试预期需要跟随更新。

- [ ] **Step 3: Write minimal implementation**

仅在广回归失败时做最小修复：
- 如果是 mirror 分类太宽，收紧 `_looks_like_generic_channel_text()` 或 `_looks_like_specific_merchant_text()`
- 如果是 alias 太激进，缩小 `MERCHANT_ALIAS_SETS`
- 不新增 unrelated feature

- [ ] **Step 4: Run broader regression suite again**

Run: `PYTHONPATH=src pytest tests/test_ai_apply.py tests/test_pending.py tests/test_transfer_rules.py tests/test_convert.py -q`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/ft/mirror_rules.py
git commit -m "fix: tighten strong mirror promotion boundaries"
```

## Self-review

### Spec coverage

- “结构优先，别名兜底” → Task 1 / Task 2 / Task 3
- “自动删除只做删弱侧” → Task 1 / Task 2 / Task 5
- “低置信候选继续走 AI 审查” → Task 4 / Task 6
- “不改 dedup/reconcile 边界” → Task 5 / Task 6
- “保护 refund/social/multi-candidate/ccb 歧义” → Task 4 / Task 6 / Task 7

无缺口。

### Placeholder scan

已检查：无 `TODO` / `TBD` / “similar to” / 缺命令 / 缺代码块占位。

### Type consistency

- 统一使用现有 `detect_mirror_pairs(rows)`
- 统一使用现有 `MirrorPair`、`auto_drop_pairs`、`review_pairs`
- 新增辅助函数名称在计划中保持一致：
  - `_looks_like_generic_channel_text()`
  - `_looks_like_specific_merchant_text()`
  - `_matches_alias_group()`

无命名冲突。
