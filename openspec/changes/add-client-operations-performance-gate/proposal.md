## Why

共享客户端操作目前只有功能合同与安装冒烟。整批现金导入已出现默认堆耗尽及长时间核对，首次运行、单项操作和收据恢复缺乏逐项性能门禁。

## What Changes

- 所有以 main 为目标的 PR 每次执行日常操作和 19 文件最大场景，包含文档 PR。固定合并检查为 `client-operations-performance`。
- 单一清单登记公开入口、负载、冷热、正确性和预算。用生产 Kotlin/JS、真实安装包和确定性合成账本逐项测量。
- 同一提交在五台独立 GitHub 托管 runner 校准。热点同机比较 base/head，有显著退化时独立复测。每次执行绝对预算。
- 合同和业务规则保持，测试使用独立资源，不发布或部署。校准通过后仅增量添加所需 main 检查。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/client-operations`：公开操作性能和最大导入的持续交付检查。

## Impact

A 类。覆盖整个共享操作层、安装包和所有 main PR 的合并资格。现有双库业务合同继续执行。无页面、生产 API、数据库迁移或财务语义变更。
