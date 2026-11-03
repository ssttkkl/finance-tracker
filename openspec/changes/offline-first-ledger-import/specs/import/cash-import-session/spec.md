## MODIFIED Requirements

### Requirement: 临时导入会话保存来源但不写入业务账本

系统 MUST 在预览与最终确认之前不写入任何业务账本数据。CSV 与 XLSX 账单 MUST 由端侧在本地解析，MUST NOT 上传原始账单到服务端临时存储。仅 PDF 允许通过服务端无状态 parse-only 入口解析，该入口 MUST NOT 建立会话、MUST NOT 读取账本、MUST NOT 创建或修改账户、账户支持币种、账单账户映射、现金流水、交易关系、余额或收支投影。

#### Scenario: 端侧解析不写业务账本

- **WHEN** 使用者导入一份受支持的 CSV 或 XLSX 账单并进入预览
- **THEN** 服务端 MUST NOT 收到原始账单，也 MUST NOT 出现任何本次导入的业务写入

#### Scenario: PDF parse-only 无副作用

- **WHEN** 使用者导入一份受支持的 PDF 账单
- **THEN** 服务端 MUST 只返回归一化行
- **AND** MUST NOT 建立会话、读取账本或写入任何业务数据

#### Scenario: 确认前离开导入

- **WHEN** 使用者在提交写集前离开导入流程
- **THEN** MUST NOT 在数据库中留下任何本次导入的业务写入

## REMOVED Requirements

### Requirement: 会话必须绑定工作区并在期限后失效

**Reason**: 服务端不再建立导入会话与临时对象，端侧本地计算取代了多文件会话与其 TTL 语义。
**Migration**: 端侧以本地缓存事实与账本版本完成查重与配对；PDF 通过无状态 parse-only 入口按请求鉴权。工作区隔离与鉴权改由 `shared/ledger-snapshot-sync` 与 `import/local-import-pipeline` 约束。

### Requirement: 关系决定只在最终确认时提交

**Reason**: 关系决定不再保存在服务端会话草稿中，而由端侧在本地持有并以完整写集一次性提交。
**Migration**: 端侧在提交前仅在本地保留决策；提交与落库要求见 `import/local-import-pipeline`。

### Requirement: 最终确认必须幂等且可安全重试

**Reason**: 会话级幂等被写集提交的两层幂等取代，不再以会话加幂等键确定提交。
**Migration**: 数据层幂等与提交层幂等见 `import/local-import-pipeline`；结果未知时按原幂等键核实。

### Requirement: 临时来源遵守隐私和清理边界

**Reason**: 端侧解析后原始账单不再上传，服务端没有临时对象需要清理；PDF 只经无状态解析。
**Migration**: 密码与原始账单的隐私边界见 `import/local-import-pipeline` 的 CSV/XLSX 与 PDF parse-only 要求。

### Requirement: 文件级扫描状态必须可重试且全部就绪后才能继续

**Reason**: 扫描改由端侧完成，服务端不再维护多文件扫描状态机。
**Migration**: 端侧在本地呈现扫描状态；需要密码的 PDF 由 parse-only 入口返回可操作错误。

### Requirement: 导入只应用通过关系图校验的自动候选

**Reason**: 关系候选改由端侧计算产出，服务端不再运行匹配器，只做关系图合法性校验。
**Migration**: 服务端写入前的不变量校验与"只校验不重算"边界见 `import/local-import-pipeline`。
