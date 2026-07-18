# Phase 2 PostgreSQL Storage（历史记录）

> Superseded。Phase 2 曾验证 PostgreSQL adapter、workspace 隔离、来源记录和 Alembic，但其双 backend、
> 本地账本迁移和 shadow comparison 实验已经全部删除。当前架构见
> [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md)。

Phase 2 的可复用成果已进入当前单一 PostgreSQL 基线：

- workspace-scoped accounts、cash transactions、investment events 和 projection；
- import batches、raw files、immutable raw records、fact lineage 和追加式 revisions；
- `NUMERIC(38,18)`、UTC `timestamptz`、稳定 account ID 和同 workspace 组合约束；
- PostgreSQL repositories、UoW、query services、Alembic 与 gated live tests。

当前 schema 从一个干净 initial revision 初始化：

```bash
uv run alembic upgrade head
uv run alembic heads
```

当前运行时只接受 `FT_DATABASE_URL` 与 `FT_WORKSPACE_ID`，普通命令启动时验证连接、schema revision 和
workspace。不会自动建表、创建 workspace、读取旧文件账本或提供存储回退。

Phase 2 的旧 migration/cutover/export 操作说明不再保留；未上线开发数据不享有兼容或迁移承诺。
