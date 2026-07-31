# 本地验证：工行卡退货退款信号

真实 PDF、密码、卡号和商户详情均不得加入仓库或测试输出。自动化测试只使用去标识化行。

```bash
uv run pytest -q \
  tests/test_convert.py \
  tests/test_statement_import_mapping.py \
  tests/test_relations_index_injection.py \
  tests/test_transaction_relations_refund.py

uv run pytest -q
uv run ruff check src tests
uv run mypy src
uv run python -m build
git diff --check
```

若环境配置了真实 PostgreSQL 测试 URL，使用项目现有 PostgreSQL fixture 运行相同的关系服务契约测试；缺失该受控测试环境时必须记录跳过原因和补跑命令，不能将 SQLite 结果冒充为双后端证据。

受控人工校准可在本地重新导入工行信用卡或借记卡文件后运行关系检查，确认仅“摘要=退货”的正数行有退款冲销候选；不要把本地路径、密码、截图或数据库内容写入版本控制。
