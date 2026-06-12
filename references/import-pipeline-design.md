# 三步导入流水线设计

见项目文档：`~/Projects/finance-tracker/docs/superpowers/specs/2026-06-11-import-pipeline-design.md`

## 关键设计决策

- CSV **8** 字段，无 transfer_id、无 exchange_rate
- 日期精确到 `YYYY-MM-DD HH:MM:SS`
- YAML 映射规则支持 `*` 通配符，长规则优先
- 跨币种购汇：**只输出账单上实际出现的那一条**（不做拆分）。USD 入账那端由券商账单处理
- 两层错误：convert 阶段 mapping 匹配失败 → 按 default(error/skip) 处理；load 阶段 accounts 表匹配失败 → 报错
- 去重键：`(datetime, amount, currency, account_name)`
