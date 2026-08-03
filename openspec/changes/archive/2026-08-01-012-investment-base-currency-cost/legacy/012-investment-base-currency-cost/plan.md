# Implementation Plan: Investment Base-Currency Cost Semantics

**Branch**: `012-investment-base-currency-cost` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

## Summary

Thread `account.metadata.base_currencies` into investment projection; base tickers use face-value only (no cost basis); non-base keep 009 release/accumulate cost. Replace fiat-hardcode-as-product-rule with base set + documented fallback.

## Technical Context

**Language**: Python 3.11+ | **Storage**: no schema change | **Testing**: pytest unit domain + wire adapters

## Constitution Check

I–V: Decimal exact; Spec Kit feature; tests first; dual backend same domain; minimal — only projection + call sites.

## Project Structure

```text
src/ft/domain/investment_projection.py   # base set param + face base legs
src/ft/adapters/relational/investments.py
src/ft/application/investment_import.py
tests/unit/domain/test_investment_base_currency_cost.py
```

## Complexity

| Item | Why | Rejected |
|------|-----|----------|
| Pass base set into domain | Domain stays pure; account is application concern | Domain reading DB |
| Face total_cost=shares for base | Keeps schema; query code still reads fields | Separate cash map type (larger refactor) |
