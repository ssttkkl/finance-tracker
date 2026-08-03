# Implementation Plan: Mapping Import & Open Currency

**Branch**: `codex/mapping-import-open-currency` | **Date**: 2026-07-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-mapping-import-open-currency/spec.md`

## Summary

`ft import` **禁止** `--account`；每一行账户只从账单字段 + `~/.ft/mapping.yaml` 推断。同时开放币种（3 位字母，无 CNY/USD/HKD 白名单）。实现：恢复 `ft.mapping`；parser 输出已路由的多账户 rows；`ImportBatch.target_account_id` 可空；convert 与 import 共用路由；领域/CLI 形态校验币种。

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, PyYAML, openpyxl, uv  
**Storage**: PostgreSQL 与文件 SQLite（显式 URL；无回退/双写）  
**Testing**: pytest；SQLite + 真实 PostgreSQL 矩阵  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: 单文件单事务 import  
**Constraints**: Decimal；digest 幂等；无 import 账户覆盖  
**Scale/Scope**: 个人多支付方式与多币种账单

## Constitution Check

| 原则 | 状态 |
|---|---|
| I 财务正确性 | PASS — 账单内推断 + 事务原子 + 幂等 |
| II Spec Kit | PASS — 004 修订 001 强制账户 |
| III 测试先行 | PASS |
| IV 双后端等价 | PASS — schema 可空 target + 矩阵 |
| V 边界 | PASS — mapping 为配置非账本 |

### Parity Matrix

| 维度 | PG | SQLite | 说明 |
|---|---|---|---|
| target_account_id NULL | yes | yes | 多账户 batch |
| digest unique | yes | yes | 幂等 |
| currency 3 字母 | yes | yes | 开放 |
| 回退/双写 | 禁 | 禁 | 禁 |

## Project Structure

```text
src/ft/mapping.py
src/ft/domain/accounts.py, imports.py
src/ft/application/accounts.py, statement_import.py
src/ft/adapters/statement_import.py, relational/{models,imports}.py
src/ft/convert.py, cli.py, schema.py
migrations/versions/20260720_03_import_batch_multi_account.py
tests/test_mapping.py, test_open_currency.py, test_statement_import_mapping.py
```

## Complexity Tracking

无。
