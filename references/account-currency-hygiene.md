# 账户同名/币种一致性

## 触发信号
- `ft acct list` 出现**同名账户**但币种不同
- `ft stock list` / `ft report` 中同一账户的币种显示与 `accounts.yaml` 不一致
- `snapshot.yaml` 里的账户币种被旧脏数据带偏

## 处理原则
1. **同名账户只能保留一个权威币种**，不要让 `accounts.yaml` 与 `snapshot.yaml` 各自保留不同版本。
2. 账户管理修改后，要同时核对：`accounts.yaml`、`snapshot.yaml`、`ft acct list` 三者是否一致。
3. 财务汇总严格按币种分开展示；未经汇率折算，不要把 USD/CNY/HKD 混加。

## 本次排查要点
- `港股证券` 的正确币种是 `HKD`
- `accounts.yaml` 中若存在同名 CNY/HKD 双条目，应删除脏条目，仅保留 HKD
- `snapshot.yaml` 若仍保留旧币种，需同步改写，否则 `ft` 读出来会继续混乱

## 参考验证命令
```bash
ft acct list
ft stock list
```
