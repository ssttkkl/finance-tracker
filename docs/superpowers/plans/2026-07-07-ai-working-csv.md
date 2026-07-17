# AI 工作 CSV 驱动的可暂停账单整理流程 Implementation Plan

## 目标

实现 `docs/superpowers/specs/2026-07-07-ai-working-csv-design.md` 中定义的流程：

- `convert` / `reconcile` 在需要 AI 介入时进入 pending 状态
- 程序输出 `ai_working.csv` 作为调用方 AI 的编辑底稿
- 调用方 AI 编辑 CSV 后，通过 `--continue-with-decisions` 恢复流程
- `--abort` 可随时放弃 pending 会话
- 待决策过程中不污染现有 `.ft` 数据

---

## 总体实施策略

采用“先建事务骨架，再接入 convert，再接入 reconcile，最后补校验与测试”的顺序。

优先保证：

1. pending 会话管理稳定
2. continue / abort 语义稳定
3. 正式数据不被污染
4. AI 工作 CSV 格式稳定

在此基础上，再逐步把 convert / reconcile 接入这套流程。

---

## 阶段 1：建立 pending 会话基础设施

### 目标

新增统一的 pending session 管理能力，支持：

- 创建 session
- 查询当前 session
- 标记状态
- 删除 session
- 统一目录布局

### 涉及文件

建议新增：

- `src/ft/pending.py`

以及修改：

- `src/ft/models.py`
- `src/ft/cli.py`

### 实现内容

#### 1. 定义 pending 根目录

在 `models.py` 中增加：

- `PENDING_DIR = FT_DIR / "pending"`

#### 2. 定义会话目录结构

支持两类：

- `pending/convert/<session_id>/`
- `pending/reconcile/<session_id>/`

#### 3. 提供统一 API

建议函数：

- `create_pending_session(kind: Literal["convert", "reconcile"], manifest: dict) -> Path`
- `load_pending_session(kind: str) -> dict | None`
- `require_single_pending_session(kind: str) -> Path`
- `write_status(session_dir: Path, status: str)`
- `clear_pending_session(kind: str)`

#### 4. 会话唯一性约束

同一时刻：

- 最多一个 pending convert session
- 最多一个 pending reconcile session

如果已有未完成 session，再次执行同类命令时应直接报错，提示先 continue 或 abort。

### 交付标准

- 可以创建、读取、删除 pending session
- `manifest.json` / `status.json` 有稳定格式
- 同类 session 唯一性得到保证

---

## 阶段 2：定义 AI 工作 CSV 格式与读写工具

### 目标

把 AI 工作 CSV 变成一等公民，而不是临时拼接产物。

### 涉及文件

建议新增：

- `src/ft/ai_working_csv.py`

可能修改：

- `src/ft/models.py`

### 实现内容

#### 1. 定义统一字段顺序

为 AI 工作 CSV 定义稳定 header，包括：

- 基础字段
- 原始字段
- 状态字段

建议在单一位置维护，例如：

- `AI_WORKING_FIELDS = [...]`

#### 2. 定义记录到工作行的转换

建议函数：

- `build_ai_working_row(row: dict, *, session_id: str, defaults: dict | None = None) -> dict`
- `write_ai_working_csv(path: Path, rows: list[dict])`
- `read_ai_working_csv(path: Path) -> list[dict]`

#### 3. 生成稳定 record_id

为每一行生成稳定 `record_id`。

要求：

- 同一次 pending 会话内唯一
- 可用于 `ai_action` 引用
- 不依赖 AI 生成

建议：

- `c_000001` / `r_000001` 这种顺序 ID
- 并在 `manifest.json` 中记录映射或至少记录生成策略

#### 4. 填充默认状态列

默认填充：

- `row_status=active`
- `ai_action=leave_as_is`
- `ai_group=`
- `ai_reason=`
- `rule_hint=`

### 交付标准

- 稳定输出 AI 工作 CSV
- record_id 生成稳定
- CSV header 固定、可复用、可测试

---

## 阶段 3：convert 接入 pending 流程

### 目标

当 `ft convert` 需要 AI 介入时：

- 不再直接输出最终 CSV
- 而是进入 pending
- 生成 `ai_working.csv`
- 等待 `--continue-with-decisions`

### 涉及文件

修改：

- `src/ft/convert.py`
- `src/ft/cli.py`

可能辅助修改：

- `src/ft/pending.py`
- `src/ft/ai_working_csv.py`

### 实现内容

#### 1. 拆分 convert 的两个阶段

当前 `do_convert(...)` 需要拆成两个逻辑层：

##### A. prepare 阶段
负责：

- 账单解析
- 基础标准化
- 轻量规则处理
- 产出中间 rows
- 生成 `ai_working.csv`
- 判断是否需要 pending

##### B. finalize 阶段
负责：

- 读取 AI 编辑后的 CSV
- 根据 `ai_action` / `row_status` 解释最终结果
- 写正式 `output.csv`
- 写退款追踪 CSV

#### 2. 定义“需要 AI”判定

初版可先采用保守策略：

- 只要存在退款相关的模糊候选或程序明确不想直接决定的项，就进入 pending

不要求一开始就做完美检测；先保证流程打通。

#### 3. 生成 convert pending session

在 pending 目录中写：

- `manifest.json`
- `status.json`
- `ai_working.csv`
- `proposed_output.csv`
- `proposed_refunds.csv`

#### 4. CLI 新增 continue / abort 入口

支持：

- `ft convert --continue-with-decisions <edited.csv>`
- `ft convert --abort`

### 交付标准

- `ft convert` 在需要 AI 时进入 pending
- pending 期间不生成正式 output
- `--continue-with-decisions` 能生成正式结果
- `--abort` 能完整清理会话

---

## 阶段 4：reconcile 接入 pending 流程

### 目标

当 `ft reconcile` 遇到需要 AI 判断的去重 / 转账场景时：

- 不直接改正式 records
- 将本次范围内的工作结果隔离到 pending
- 生成 `ai_working.csv`
- 等待 `--continue-with-decisions`

### 涉及文件

修改：

- `src/ft/reconcile.py`
- `src/ft/dedup.py`
- `src/ft/cli.py`

### 实现内容

#### 1. 拆分 reconcile 的两个阶段

##### A. prepare 阶段
负责：

- 读取 scope 内 records
- 轻量规则判断
- 生成 AI 工作 CSV
- 复制 staged records
- 判断是否进入 pending

##### B. finalize 阶段
负责：

- 读取 AI 编辑后的 CSV
- 解释 `ai_action` / `row_status`
- 生成最终 rows_by_file
- 正式写回 `records/`
- rebuild snapshot
- 写正式 audit

#### 2. staged_records 隔离

pending 时应复制出：

- `staged_records/cash/...`
- `staged_records/loan/...`

正式 records 不动。

#### 3. reconcile pending 触发条件

初版建议：

- 只要出现多候选 dedup / transfer，或规则明确不想直接决定，就进入 pending

#### 4. CLI 新增 continue / abort 入口

支持：

- `ft reconcile --continue-with-decisions <edited.csv>`
- `ft reconcile --abort`

### 交付标准

- `ft reconcile` 在需要 AI 时不污染正式 records
- continue 成功后才落地 records / snapshot
- abort 不留残留、不修改正式数据

---

## 阶段 5：实现 edited CSV 校验器

### 目标

确保调用方 AI 编辑后的 CSV 符合协议，并防止非法或危险修改。

### 涉及文件

建议新增：

- `src/ft/ai_working_validate.py`

或整合进：

- `src/ft/ai_working_csv.py`

### 实现内容

#### 1. 会话一致性校验

- `session_id` 必须匹配当前 pending session

#### 2. record_id 完整性校验

- 不允许丢行
- 不允许重复 `record_id`
- 不允许新增未知 `record_id`

#### 3. 只读字段校验

阻止修改：

- `date`
- `amount`
- `currency`
- `bill_source`
- `raw_*`
- `record_id`
- `session_id`

#### 4. ai_action 语法校验

校验：

- `keep`
- `drop`
- `merge_refund_into:<record_id>`
- `net_with:<record_id>`
- `mark_transfer_out_to:<record_id>`
- `mark_transfer_in_from:<record_id>`
- `leave_as_is`

#### 5. 引用关系校验

例如：

- `merge_refund_into:x` 中的 `x` 必须存在
- transfer 双边引用应成对成立
- group 内动作应自洽

#### 6. 结果可解释性校验

例如：

- refund 动作不能指向不存在的 expense
- transfer 动作两边金额至少应满足基本约束
- drop 行应有合理上下文

### 交付标准

- 非法 edited CSV 会被阻断
- 错误提示足够具体，能指导 AI / 调用方修正

---

## 阶段 6：定义 AI 工作 CSV 到最终结果的解释器

### 目标

把 edited CSV 转换成正式输出或正式 records 的逻辑集中管理，而不是散落在 convert / reconcile 代码里。

### 涉及文件

建议新增：

- `src/ft/ai_apply.py`

### 实现内容

#### 1. convert 解释器

根据 edited CSV：

- 保留 / 删除行
- 执行退款核销
- 生成净额结果
- 写最终 output
- 写退款追踪

#### 2. reconcile 解释器

根据 edited CSV：

- 删除 dedup 行
- 修改 transfer category / transfer_account
- 重新组织 rows_by_file
- 写正式 `records/`
- 生成正式审计输出

#### 3. 单一解释入口

例如：

- `apply_convert_working_csv(...)`
- `apply_reconcile_working_csv(...)`

### 交付标准

- convert / reconcile continue 都能通过统一解释器落地
- 逻辑集中，便于测试和后续扩展

---

## 阶段 7：CLI 集成与用户体验打磨

### 目标

让命令行语义清晰稳定。

### 涉及文件

修改：

- `src/ft/cli.py`

### 实现内容

#### 1. 新增参数

- `ft convert --continue-with-decisions <edited.csv>`
- `ft convert --abort`
- `ft reconcile --continue-with-decisions <edited.csv>`
- `ft reconcile --abort`

#### 2. 冲突参数校验

避免：

- 同时传正常参数和 continue 参数
- 无 pending session 却 continue / abort
- 已有 pending session 时重复发起同类命令

#### 3. 输出提示

进入 pending 时输出：

- session 目录
- `ai_working.csv` 路径
- continue 命令
- abort 命令

### 交付标准

- CLI 入口完整
- 错误信息清晰
- 用户能顺利理解下一步怎么做

---

## 阶段 8：测试覆盖

### 目标

覆盖整个 pending / continue / abort 主流程。

### 涉及文件

新增或扩展：

- `tests/test_convert.py`
- `tests/test_reconcile.py`
- 建议新增：
  - `tests/test_pending.py`
  - `tests/test_ai_working_csv.py`
  - `tests/test_convert_pending.py`
  - `tests/test_reconcile_pending.py`
  - `tests/test_ai_working_validate.py`

### 核心测试场景

#### pending 基础设施

- 创建 convert session
- 创建 reconcile session
- 同类 session 唯一性
- abort 后目录清理

#### convert

- 无需 AI 时仍直接输出
- 需要 AI 时进入 pending
- pending 时不生成正式 output
- continue 后生成正式 output
- abort 后无残留

#### reconcile

- 无需 AI 时仍直接改正式 records
- 需要 AI 时进入 pending
- pending 时不改正式 records / snapshot
- continue 后才正式落地
- abort 后正式数据不变

#### 校验器

- 修改只读字段时报错
- 丢 `record_id` 报错
- `ai_action` 非法时报错
- 引用不存在的 `record_id` 报错

#### 解释器

- refund merge 行为正确
- drop / keep 行为正确
- transfer 双边标记行为正确

### 交付标准

- 主流程有完整自动化测试
- 数据污染控制有测试保障
- continue / abort 行为可回归验证

---

## 阶段 9：文档与 Skill 配套

### 目标

让调用方 AI 能稳定使用这套流程。

### 涉及文件

建议更新：

- `SKILL.md`
- `README.md`
- 新增 references 文档（如需要）

### 实现内容

#### 1. 记录 working CSV 的字段语义

说明：

- 哪些列 AI 可以改
- 哪些列不能改
- `ai_action` 的合法值

#### 2. 记录 pending 工作流

说明：

- 首次运行
- 进入 pending 后怎么继续
- 怎么 abort

#### 3. 为 Skill 提供稳定提示词素材

帮助调用方 AI：

- 打开 `ai_working.csv`
- 修改允许编辑的列
- 保存后执行 continue 命令

### 交付标准

- 文档足够支撑外部 Skill 使用
- 调用方不需要读源码才能理解流程

---

## 建议实现顺序

按以下顺序落地：

1. pending 基础设施
2. AI 工作 CSV 工具
3. convert pending 流程
4. edited CSV 校验器
5. convert continue 解释器
6. reconcile pending 流程
7. reconcile continue 解释器
8. CLI 整合
9. 测试补齐
10. 文档 / Skill 配套

原因：

- convert 相对局部，先打通更容易
- reconcile 涉及正式 records / snapshot，放后面更稳
- 先有基础设施和校验器，再接复杂流程，返工少

---

## 风险与注意点

### 1. record_id 稳定性

如果 record_id 生成策略不稳定，会导致：

- AI 编辑结果无法回放
- continue 无法对齐原行

必须尽早固定。

### 2. continue 解释器不要偷偷“重新推断”

程序在 continue 阶段应尊重 AI 编辑结果，不能再用旧规则把 AI 的修改覆盖掉。

### 3. reconcile 的 staged_records 一定不能误写到正式目录

需要明确隔离，避免实现时路径混淆。

### 4. 首版不追求把所有模糊场景都做完

先把流程骨架打通，再逐步扩大“哪些情况进入 pending”。

---

## 完成定义

当以下条件全部满足时，本计划视为完成：

1. `ft convert` 支持 pending / continue / abort
2. `ft reconcile` 支持 pending / continue / abort
3. 待决策过程中不污染正式数据
4. AI 可通过编辑 `ai_working.csv` 驱动后续流程
5. continue 前会对 edited CSV 做严格校验
6. 主流程有自动化测试覆盖
7. 文档足够支持 Skill 串联使用
