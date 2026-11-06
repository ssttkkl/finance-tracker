## 1. 思考

- [x] 1.1 读取上下文、编码规范、规格、词表、部署日志与代码；独立调查驱动合同；按澄清后实际影响评为 C 类，依据见 proposal。grilling 范围沿用用户已确认的修复方案，无新增取舍；真实连接安全、双后端与完整后端验证保留。

## 2. 计划

- [x] 2.1 完成 proposal、完整 MODIFIED delta 与 design，界定参数、安全、双后端及发布边界。

## 3. 任务拆分

- [x] 3.1 对齐红例、参数拒绝与脱敏、TLS/SCRAM 集成、SQLite、独立复核和文档收尾。

## 4. 构建

- [x] 4.1 以实际启动解析入口复现含两个安全参数的 URL 被拒绝，补失败回归。`parser-red.log` 中两例为 `storage.config`，修复后同组 11 项通过。
- [x] 4.2 最小修复 query 校验、映射及凭据传递，不扩大未知参数或影响 SQLite。
- [x] 4.3 显式本地 TLS／SCRAM PostgreSQL 与 SQLite 的同组 13 项通过。安装产物另以 7 个独立进程验证 require+binding require、可信 CA 和非绑定认证对照成功；不可信 CA、错误主机、无 TLS 和无绑定认证拒绝，成功例均实际完成健康、注册、工作区、账户创建与读取。

## 5. 审查

- [x] 5.1 独立上下文按产品编码规范工程检查面复核异常、输入、安全语义、连接生命周期、复杂度与最终 diff，无阻断 finding；建议已补允许值、大小写、JDBC 证书语义及大写 SSL 正例。最终复核已核对 7 项安装产物安全证据、脚本、部署参考和用户授权的分级规则调整，无新增 finding；线上部署未覆盖。
- [x] 5.2 独立 codebase-degradation-review → contract-design-review：配置边界有实际隔离价值，query 和凭据单一来源，所有 Database 连接一致复用，无重复领域模型或持久化状态；UI 生产代码未改，UI 复用不适用。评审者只读，报告位于仓库外 QA 目录。

## 6. 测试与 QA

- [x] 6.1 完整默认后端 870、codegen 2、contracts 3、domain 10 项全部通过，零失败、零 skip；ktlint、detekt／Main／Test、安装构建通过，显式本地 TLS PostgreSQL 与 SQLite 均执行。首次完整运行出现未改动的 CCXT 503 重试断言失败；单独 4 项和完整重跑均通过，偶发原因未定位，不将其记为已修复。
- [x] 6.2 安装产物实际启动、健康和业务冒烟；安全失败不泄漏输入，TLS 专项 7/7 通过。
- [x] 6.3 OpenSpec 严格校验 27 项通过，doctor 健康；改动文档的 123 个本地链接／锚点与 diff 检查通过。真实 Render／Neon 验收及外部历史迁移证据未运行，不计入本轮结果。

验证基线与 HEAD 均为 `2950f7908ac944f649db3ad69ef43a1b751edbee` 加当前工作树；执行于 2026-11-06（Asia/Shanghai）。运行命令为 `./compose/gradlew -p compose :backend:test :backend:codegenTest :business-contracts:jvmTest :business-domain:jvmTest :backend:ktlintCheck :backend:detekt :backend:detektMain :backend:detektTest :backend:installDist --no-configuration-cache`，以环境变量显式传入操作者准备的本地 TLS／SCRAM `_test` 数据库。完整重跑耗时 2 分 15 秒；部分未变的辅助任务为 Gradle up-to-date，未把它们描述为重新执行。

凭据、测试证书、运行脚本及日志留在仓库外 `/Users/huangwenlong/Library/Caches/finance-tracker/neon-database-url-qa/`：`parser-red.log`、`parser-green.log`、`runtime-green.log`、`backend-full-first.log`、`ccxt-isolated.log`、`backend-full.log`、`verification-counts.json`、`production-tls-results.json`、`documentation-link-check.json`、`independent-review.json`。成功安装进程均实际调用 `/health`、注册、工作区创建、账户创建及读取；安全负例确认进程退出且诊断无原始连接串或密码。CI 默认发现新增 7 项测试，真实证书／主机名／认证模式矩阵为本轮本地专项，不宣称已接入 CI。

## 7. 发布

- [x] 7.1 已核对 README、后端部署参考、功能地图、词表、技能与规格。连接安全要求落在本 change，不新增通用产品或数据库规范；已有失败关闭和安全边界规则足够。用户明确要求先澄清再按实际影响评估 A/B/C，已获授权写入 AGENTS.md，删除技术主题、失败次数、模块数和存疑时的强制升级，保留按实际风险执行专项验证；同步设计简报中的分级表述。本次纯工作流规则调整不另建 OpenSpec。没有新增领域术语，UI 与客户端代码未变；Web、Native 和共享业务层合同不受影响，无需页面、设备或原生构建验证。
- [x] 7.2 已同步主规格并核对全部原场景保留，完成归档准备，交付保留可审查工作树；未提交、推送、创建 PR 或部署，新分支与 Render 各自等待具体外部写授权。回退本次代码即可，无数据迁移；线上仍须重新构建部署并核对启动、schema 和健康检查。
