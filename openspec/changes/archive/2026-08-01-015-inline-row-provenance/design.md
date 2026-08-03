# 设计：功能规格：正式事实结构清理（内联溯源 + 去掉冗余表/列）

## 上下文

本 change 是从旧规格目录迁移到 OpenSpec 的记录。原始技术方案、研究、数据模型、契约和快速开始材料均保存在
`legacy/015-inline-row-provenance/` 下；本文件只说明迁移后的目录关系。当前实现已完成，change 仅作为历史审计记录。

## 设计决策

- `openspec/specs/015-inline-row-provenance/spec.md` 是当前能力的行为源事实。
- `openspec/changes/015-inline-row-provenance/specs/015-inline-row-provenance/spec.md`（或对应 archive 路径）保存本次迁移的 delta 快照。
- 原始文档不在迁移过程中压缩或删除，避免丢失财务语义、数据库等价性和验证证据。
- 后续行为变化使用 OpenSpec change；纯技术背景继续放入 `design.md`，实施步骤放入 `tasks.md`。

## 回滚与审计

迁移不改变产品数据和运行时代码。若需要回看旧格式，直接读取 `legacy/`；若要撤销迁移，可从版本控制恢复旧目录，
但不得在运行时引入旧规格作为行为事实源。
