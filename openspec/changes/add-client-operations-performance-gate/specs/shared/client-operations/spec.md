## ADDED Requirements

### Requirement: 所有 main PR 验证共享操作性能预算

仓库 MUST 以同一清单按公开入口、场景和预算逐项验证共享客户端操作，并覆盖实际安装的 CLI。以 main 为目标的每次 PR 更新 MUST 运行普通负载及最大负载，包括纯文档变更。

#### Scenario: 公共接口或场景缺失

- **WHEN** 共用操作对外入口变更或预期场景执行不完整、结果错误、泄漏写入或超预算
- **THEN** `client-operations-performance` MUST 失败，普通和最大作业均成功才可通过

#### Scenario: 19 文件一次确认

- **WHEN** 安装的 CLI 核对、确认、整理结果并重新进程读取同一个合成批次
- **THEN** 各阶段 MUST 独立满足墙钟及内存预算，校验一次原子写入、完整原收据、逐关联成员和断网恢复

#### Scenario: 热点相对退化

- **WHEN** 同机同夹具热点相对耗时增加超过 20% 且达到场景的绝对增量
- **THEN** MUST 独立复测，复现时失败。无兼容 base 时记录原因并完整执行 head 的绝对预算
