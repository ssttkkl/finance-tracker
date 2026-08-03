## 1. 测试先行

- [x] 1.1 新增 CLI 回归，验证工银亚洲来源能通过参数解析并到达导入服务。

## 2. 构建与复核

- [x] 2.1 将来源加入现金导入与转换入口的 choices，并复核现金账户限制保持不变。

## 3. 验证与交付

- [x] 3.1 已运行 `uv run pytest tests/test_cli.py tests/test_icbc_asia_current_account.py -q`（42 passed）、CLI 帮助文本、`git diff --check` 与 OpenSpec 严格校验；真实导入前置为尾号映射和已存在现金账户。
