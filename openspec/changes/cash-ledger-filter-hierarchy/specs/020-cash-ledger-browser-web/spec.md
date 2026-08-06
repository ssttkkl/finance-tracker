## ADDED Requirements

### Requirement: 数据库驱动的经济类型筛选树

系统 MUST 在收支投影列表响应的 `filter_options.economic_types` 返回当前工作区活动数据集全部可见投影的经济类型—子类型树。每个一级项 MUST 包含 `economic_type` 和按稳定字典序去重的 `transfer_subtypes`；没有子类型的一级项返回空数组。该树不得包含隐藏投影、空子类型、其他数据集或客户端预设项，且必须独立于本次列表的日期、账户、交易信息、分类、币种、金额、经济类型、子类型和组成方式筛选。**优先级理由**：使用者只能选择当前账本实际存在的类型，新增子类型无需同步修改前端选项清单。

#### Scenario: 只返回活动数据集的可见类型

- **WHEN** 当前活动数据集有可见的 `expense`、`income`、`internal_transfer(ordinary_transfer)` 和 `internal_transfer(bank_security_transfer)`，同时有隐藏的 `internal_transfer(balance_adjustment)`
- **THEN** `filter_options.economic_types` 必须依次返回 `expense([])`、`income([])`、`internal_transfer([bank_security_transfer, ordinary_transfer])`，且不得包含 `balance_adjustment`

#### Scenario: 筛选后仍可撤销类型选择

- **WHEN** 使用者以任何经济类型、子类型或其他筛选参数读取空结果
- **THEN** 响应仍必须返回活动数据集完整的 `economic_types` 树，不得仅返回本页结果中的类型

### Requirement: 经济类型与子类型的规范化筛选

系统 MUST 接受可选的 `economic_type` 与 `transfer_subtype` 查询参数，并将两者与版本化 cursor 的筛选摘要绑定。`transfer_subtype` 被提供时，系统 MUST 只返回该子类型且其父级为 `internal_transfer` 的投影；未提供父级时必须自动使用 `internal_transfer`，提供其他父级时必须返回 `invalid_filter`。旧的 `economic_type=bank_security_transfer` MUST 继续等价于 `economic_type=internal_transfer&transfer_subtype=bank_security_transfer`。该行为不得新增经济类型、数据库字段或迁移。**优先级理由**：子类型选择必须精确且可共享链接，同时不破坏既有银证转账筛选调用。

#### Scenario: 选择银证转账子类型

- **WHEN** 使用者选择数据库返回的 `internal_transfer` 下 `bank_security_transfer`
- **THEN** 客户端请求必须携带 `economic_type=internal_transfer` 与 `transfer_subtype=bank_security_transfer`，结果只包含该子类型投影

#### Scenario: 子类型与错误父级组合

- **WHEN** 调用方以 `economic_type=expense&transfer_subtype=bank_security_transfer` 请求投影
- **THEN** 服务必须返回稳定的 `invalid_filter` 错误，且不得读取或修改投影数据

### Requirement: 可访问的分层筛选控件

系统 MUST 以数据库返回的经济类型筛选树渲染原生的分组选择控件：一级项可选择其全部投影，内部转账的子类型在该一级项的分组内可选择。客户端不得维护经济类型或子类型的可选值清单；中文显示名称可以是稳定展示映射，但未知数据库值必须可见地回退为原始值。加载期间控件必须禁用，成功、空数据和错误状态不得清空最后一次成功获得的选项；控件必须具有可访问名称、键盘操作和可见焦点。**优先级理由**：筛选结构应反映账本事实，同时让键盘使用者能够无差别完成筛选。

#### Scenario: 切换父级类型

- **WHEN** 使用者从任一内部转账子类型切换到 `internal_transfer` 父级
- **THEN** 客户端必须清除 `transfer_subtype` 并从首页重新读取完整内部转账结果

#### Scenario: 未知子类型展示

- **WHEN** 响应包含尚无中文展示映射的内部转账子类型
- **THEN** 筛选控件必须显示该原始值并允许选择，不得隐藏、替换为其他子类型或发送不同参数
