# 现金关系模型迁移

本页说明把既有账本从旧关系模型升级到当前模型所需的本机操作。冻结的迁移逻辑、逐项预检内容、失败样例和风险表保存在归档变更 [govern-cash-relation-model](../../openspec/changes/archive/2026-10-30-govern-cash-relation-model/design.md#migration-plan)；本页只保留当前发布需要执行的步骤和边界。

## 执行顺序

1. 停写并等待在途事务结束，备份选定数据库和匹配的应用版本。使用显式 `FT_DATABASE_URL`；不自动探测环境，不自动回退或双写。
2. 在 revision 52 数据库上运行只读预检：

   ```bash
   PYTHONPATH=src:. uv run python scripts/cash_relation_migration_check.py preflight --report <仓库外文件>
   ```

   预检按工作区检查旧关系决定、对象身份锚、成员完整性、人工公共值、金额、账户、币种、退款与资金占用。任何无法保持原决定的记录都会阻断迁移；命令不升级数据库，也不重建投影。
3. 通过预检后运行 revision 53 迁移，并按既有重建入口发布当前投影。迁移在事务中完成；中断时保持旧版或完整新版，不保留半新结构。
4. 使用同一报告运行核验：

   ```bash
   PYTHONPATH=src:. uv run python scripts/cash_relation_migration_check.py verify --report <仓库外文件>
   ```

   核验覆盖关系金额、正式与草稿成员、稳定对象归属、公共值、统计资格、代表／日期／标签／金额及发布版本。全部通过后才开放新服务和新客户端写入。

## 兼容与回滚

- 未消费的旧计划在升级后失效并返回 `plan_changed`；客户端重新打开任务生成新计划。已成功命令按原幂等键读取历史收据，不重新执行。
- 客户端持久筛选中的旧 `transfer_subtype` 需映射为新 `business_label`；未知字段请求失败，不能静默忽略旧写字段。
- 新写入开放前，可直接恢复数据库备份与旧应用／客户端。开放后，新跨类别关系和部分平台退回可能不符合旧校验，不承诺 downgrade 可逆；必须停写、核对新增事实并制定单独恢复方案，不得为启动旧版本删除新关系。

## 观察项

预检阻断数、确认拒绝码、未确认建议数量、分币种总额、旧键核实成功率和标签／月份变化。指标不得写入账单正文、账户名称或备注。
