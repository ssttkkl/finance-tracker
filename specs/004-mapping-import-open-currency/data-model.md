# Data Model: Mapping Import & Open Currency

## Account

`currency`：规范化 3 位字母，非枚举白名单。unique `(workspace_id, name, currency)`。

## Payment mapping（配置文件，非 DB）

`~/.ft/mapping.yaml`：rules + default。  
匹配：优先 `{bill_type}_{card_number}` + `*`，否则 bill_type + payment_method fnmatch（长优先）。

## ImportBatch

- `target_account_id`：**nullable**（多账户导入为 NULL；不再表示强制单账户）
- digest 幂等键不变

追溯：batch → raw_records → facts.account_id。

迁移：`20260720_03` 允许 NULL。

## Formal facts

每行 `account_name`+`currency` → account_id；现金/贷款行币种必须等于账户币种。

## Flow

```
raw file → parse → mapping route each row → ImportBatch(digest)
→ raw + multi-account facts + projection → complete
```

无 “CLI account override” 分支。
