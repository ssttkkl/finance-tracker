# Research: Mapping Import & Open Currency

## Decision 1: Import never accepts account override

**Decision**: `ft import` 删除 `--account`。全部行经账单 payment_method/card_number/bill_type + mapping 路由。

**Rationale**: 用户明确要求；避免 CLI 覆盖与账单事实不一致。

**Alternatives rejected**: 可选 `--account` 覆盖（曾写入 draft，已废止）。

## Decision 2: File mapping restored

**Decision**: 恢复 master `ft.mapping`（`~/.ft/mapping.yaml`，长 match 优先，default skip|error）。

## Decision 3: Multi-account batch

**Decision**: `import_batches.target_account_id` 可空；账户只在 facts 上。

## Decision 4: Open currency

**Decision**: 3 位字母 upper；去掉白名单；符号表仅展示。

## Decision 5: Convert/import same router

**Decision**: 共用路由；convert 不作为账户覆盖入口（与 import 一致从账单推断）。

## Decision 6: Bank/broker via mapping sources

**Decision**: `icbc_debit`/`icbc_credit`/`ccb_debit_*`/`dfzq` 等 source 规则覆盖单账户账单，无需 CLI 账户。

## Decision 7: Revises 001

**Decision**: 废止 001「import 必须显式账户、不读 mapping」。
