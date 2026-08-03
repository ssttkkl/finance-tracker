## Context

导入服务与现金来源集合均已接入工银亚洲，唯一缺口是 argparse 的来源 choices。

## Goals / Non-Goals

**Goals:**

- 让 CLI 接受已存在的工银亚洲来源并将其传给导入服务。

**Non-Goals:**

- 不改变解析、映射、持久化或 CLI 参数语义。

## Decisions

将来源同时加入 `import` 与 `convert` 的现金账单 choices，保持两条现有 CLI 路径的来源集合一致。

## Risks / Trade-offs

- [只改 `import` 而遗漏 `convert`] → 以同一 CLI 回归和帮助文本复核两个入口。

## Migration Plan

无需迁移；回滚仅移除该参数值。
