# Contract: CLI Import / Convert Mapping

## `ft import FILE --source SOURCE [--currency CODE] [--password-file PATH]`

**禁止** `--account`。传入则 argparse 错误或显式失败，零写入。

### Behavior

1. Load `~/.ft/mapping.yaml`.
2. Parse rows; route each via payment_method / card_number / bill_type rules.
3. Single transaction, multi-account formal facts.
4. Unmatched + `default: error|fail` → non-zero, rollback.
5. Unmatched + `default: skip` → skip; zero kept rows → non-zero.
6. Same workspace+source_kind+digest → idempotent, no new facts.

`--currency` 若保留：仅作缺省行币种回退（当解析行无币种时），**不得**选择或覆盖账户名。推荐优先使用行内币种。

## `ft convert FILE --source SOURCE [-o OUT] [--password-file PATH]`

账户路由与 import 相同（账单 + mapping）。不提供账户覆盖作为正式合同。
