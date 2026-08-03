## Purpose
User: main-branch investment model — account configures base currencies (USD/HKD/CNY/JPY, USDT/USDC, …); those base tickers do not carry cost basis. Current branch still treats positions uniformly with total_cost/cost_currency and only hardcodes fiat for multi-ccy labels. 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: Base tickers have no cost basis
系统 MUST 作为多币种证券/加密账户用户，我为账户配置本位币（如 USD+HKD 或 USDT），当我存取本位现金、或本位币之间换汇时，系统只更新余额数量，不维护这些本位仓的成本基础；股票与非本位资产仍累计/释放成本。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Projection API carries base set
作为系统，CLI 手动投资命令与 statement 导入 MUST 使用同一套「账户 base_currencies → 投影」规则。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: Multi-base HKD+USD deposit+FX+equity path: 0 cost currency conflicts.
- - **SC-002**: USDT-in-base crypto buy: USDT balance face-only; asset has cost.
- - **SC-003**: Unit tests for base vs non-base legs; import path integration smoke.
- - **SC-004**: Existing usmart/ibkr/dfzq focused suites still pass with bases seeded on test accounts.

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。
