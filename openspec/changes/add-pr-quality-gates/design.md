## Context

本变更不改变产品运行时，只补齐仓库级质量门禁。当前根级 `package-lock.json` 支持 npm workspace；Web 已有 Vitest、TypeScript、生产构建和三套 Playwright 配置；Python 依赖和测试命令由 `pyproject.toml` 与 `uv` 管理，仓库按现有约定忽略 `uv.lock`，因此 CI 使用 `uv sync` 根据项目声明解析依赖，并以 `pyproject.toml` 作为缓存键。现有快照文件使用 `darwin` 平台后缀，因此完整前端浏览器 job 需要使用 macOS runner，避免在 Linux 上寻找不存在的 `linux` 基线。远程 `macos-26-arm64` 与本机同版 Chromium 仍出现少量中文字体栅格差异，因此 Web 视觉配置为 CI 使用独立的 `ci/` 快照目录；本机默认目录继续保留开发者基线。

本地复现确认了两个独立问题：

1. 工作区创建页测试切换路由时会卸载 `CashLedgerPage`，其 `AbortController` 按设计取消未完成的账户和投影请求；Chromium 将这类取消报告为 `net::ERR_ABORTED`，测试当前把它当作真实网络故障。
2. `1024×768` 的收支账本快照仍反映旧列集合，当前实现已经显示交易类型、金额和行操作；其他视口的快照保持通过。

3. PostgreSQL 后端复跑暴露了三类门禁问题：投影锁查询把工作区与状态行合并，无法保证测试约定的锁序；来源指纹查询复用了会话中已加载的旧 ORM 实例，漏掉独立事务提交的来源字段变化；迁移回归测试从 `head` 回退时穿过了明确不可逆的收支分类迁移。

后端测试在未设置 `FT_TEST_POSTGRES_URL` 时会显式跳过 PostgreSQL 参数。CI 必须运行 SQLite 功能 job、PostgreSQL 功能 job 和独立性能 job：SQLite job 保留本地基线，PostgreSQL 功能 job 与性能 job 使用一次性 `postgres:16-alpine` 服务和以 `_test` 结尾的数据库，并设置 `FT_REQUIRE_TEST_POSTGRES=1`，使缺失或不可用的 PostgreSQL 不能静默变成通过。

## Goals / Non-Goals

**Goals:**

- 让前端和后端在每个 Pull Request 上拥有可重复、可读的独立检查结果。
- 保留现有真实 Web 流程和视觉回归覆盖，同时修复已确认的测试误报和过期基线。
- 在 CI 中保留 SQLite 与 PostgreSQL 的双后端证据，并禁止测试触碰生产数据库。
- 失败时上传 Playwright 诊断目录，便于从 Pull Request 直接定位问题。
- 复用现有 npm、`uv`、Playwright 和 pytest 入口，不引入新的产品运行时依赖。

**Non-Goals:**

- 不修改账本、API、权限、金额、幂等、来源溯源或数据库 schema。
- 不修改现有 `mobile-ci.yml`、Native 构建产物、EAS、商店签名或部署流程。
- 不设置 GitHub 分支保护或 Required checks；仓库规则配置需要单独授权和变更。
- 不在 CI 中运行真实账单、真实凭据或生产 API。

## Decisions

### 1. 使用一个独立的 `pr-checks.yml`

新增 `.github/workflows/pr-checks.yml`，包含 `frontend`、`backend-sqlite`、`backend-postgres` 和 `backend-performance` 四个 job；触发器为所有 `pull_request` 与 `workflow_dispatch`，不使用路径过滤。这样共享包、Web、后端和迁移测试的影响不会因为路径判断遗漏，且与现有 Mobile CI 的 Native 职责分开。

备选方案是把所有检查追加到 `mobile-ci.yml`，但会让移动端构建失败掩盖 Web/API 质量状态，也会把后端 PostgreSQL 服务耦合到 Native workflow，因此不采用。

### 2. 前端 job 使用 macOS 并执行完整 Web 门禁

前端 job 使用 `macos-26`、Node 24 和 npm cache，安装 Chromium 后依次运行共享包测试、共享包类型检查、Web Vitest、Web 生产构建、E2E、生产预览和视觉回归。macOS runner 与仓库现有 `darwin` 快照命名及字体环境一致；视觉回归仍以快照差异失败，不通过放宽阈值隐藏差异。失败时上传 `web/test-results/` 和可能存在的 `web/playwright-report/`，仅保留 7 天。

视觉配置保持默认本机路径 `web/tests/cash-ledger.visual.e2e.ts-snapshots/`，在 `CI` 环境切换到其下的 `ci/` 子目录。CI 基线只接受已审查的远程 runner 实际截图，当前差异仅为中文字体栅格化，不接受布局、文案、颜色或交互差异；因此没有放宽 `toHaveScreenshot` 的像素阈值。

备选方案是在 Ubuntu 重新生成整套 Linux 快照，或修改快照路径移除平台后缀；前者会引入跨平台字体基线，后者会重写所有快照并改变既有测试约定，本次不扩大范围。

### 3. 后端 job 显式分成功能与性能门禁

两个功能 job 都使用 Python 3.11、`astral-sh/setup-uv`、`uv sync` 和 `PYTHONPATH=tests:.:src uv run pytest -q -m "not performance"`。固定规模性能文件显式标记为 `performance`，功能 job 不再误跑分类、投影、投资浏览、工作区和财富性能门禁；现金投影性能文件中的 PostgreSQL 配置回归仍保持未标记并继续属于功能契约。仓库不提交 `uv.lock`，所以 CI 以 `pyproject.toml` 作为 uv cache dependency，依赖解析漂移由后续显式锁文件变更另行治理。SQLite job 不设置 PostgreSQL URL，保留显式的 PostgreSQL skip 作为本地后端基线；PostgreSQL job 提供 `postgres:16-alpine` service container，数据库名为 `finance_tracker_test`，使用 `FT_TEST_POSTGRES_URL=postgresql+psycopg://postgres:postgres@127.0.0.1:5432/finance_tracker_test` 与 `FT_REQUIRE_TEST_POSTGRES=1` 强制运行 PostgreSQL 参数。

`backend-performance` 使用标准 `macos-26-intel` runner，在 runner 上通过 Homebrew 启动专用 PostgreSQL 16 数据库，连接到 `finance_tracker_test`，并强制设置同样的 `_test` URL。远程 `ubuntu-latest` 的实际 cold p95 稳定为 SQLite `8.490s`、PostgreSQL `8.745s`，标准 `macos-26` ARM64 runner 的实际 cold p95 又达到 SQLite `11.794s`、PostgreSQL `9.031s`，均超过既有 `5s`/`6.5s` 预算；本机相同 Python 3.11、SQLite 和 Chromium 所在的 macOS 26 ARM64 环境 cold p95 为 `4.386s`，因此性能 job 使用 4 核/14 GiB 的标准 Intel macOS runner，避免继续依赖 3 核/7 GiB 的 ARM64 runner。该测试自身包含 SQLite 与 PostgreSQL 参数，因此两个后端的固定 cold/hot 性能预算仍都被执行；把它从功能套件中隔离是为了避免长时间迁移、并发和关系测试污染 p95 样本，不删除测试、不放宽预算。

功能 job 的 PostgreSQL service container 通过 `pg_isready` 健康检查；性能 job 的本地 PostgreSQL 由同一步骤初始化并创建专用 `_test` 数据库。测试夹具继续负责清理专用 schema、执行 migration 和恢复状态。CI 不自动探测数据库，也不复用开发机或生产连接串；每次 job 的数据库随 runner 销毁。

备选方案是只运行 SQLite，速度更快但会重新留下当前 PostgreSQL skip；或把功能和性能混入同一个长 job，性能样本会受套件负载影响且失败来源不清晰，因此均不采用。

### 4. 只忽略可确认的浏览器主动取消

工作区入口 E2E 的请求失败收集器仅忽略错误文本精确为 `net::ERR_ABORTED` 的浏览器主动取消，其他 request failure 继续进入断言。该逻辑只作用于测试诊断，不改变 `CashLedgerPage` 的请求取消和错误处理；因此真实 `net::ERR_FAILED`、HTTP 错误和应用错误仍会失败。

视觉快照只更新已确认过期的 `1024×768` 主列表和证据抽屉文件，并立即复跑该视口和完整视觉套件，避免顺手接受未审查的其他差异。

### 5. 保留 PostgreSQL 投影并发合同

`RelationalCashProjectionRepository._state(lock=True)` 在 PostgreSQL 中先单独执行工作区 `FOR UPDATE`，再执行状态行 `FOR UPDATE`，保持所有写路径一致的锁序，避免两个事务以不同顺序获取资源。全量重建在读取输入前保存来源指纹，投影发布时再校验；分类同步是构建后的内部写入，放在发布校验之后执行，避免把预期的分类归一化误判为并发更新。`source_digest()` 的来源查询使用 `populate_existing`，使同一重建事务在读取过来源 ORM 实例后仍能看到已提交的独立事务更新；关系和资金关系也采用相同策略。

迁移回归只在 `20260729_11` 与 `20260811_26` 之间往返。它验证数据集索引迁移本身可逆，同时不穿越 `20260812_27` 这一明确标记为一次性、不可逆的分类重建迁移。

## Risks / Trade-offs

- **macOS runner 成本和排队时间较高** → 只在 Pull Request 与手动触发中运行，复用 npm cache；现有 Native CI 继续独立，可按职责单独重跑。
- **完整 pytest 运行时间较长** → 为四个 job 设置足够的超时，保留功能与性能 job 并行；不通过删减测试或放宽性能预算换取表面速度。
- **PostgreSQL service 或 Actions runner 漂移** → 功能 job 固定 `postgres:16-alpine`，性能 job 固定 `macos-26-intel`、Homebrew PostgreSQL 16、Python 3.11、Node 24 和 action major version，并在失败时保留完整日志；升级时重新执行本地双后端矩阵。性能预算保持不变，若 macOS runner 的数据库初始化或硬件发生漂移，必须重新取得双后端 p95 证据，不得用放宽预算掩盖。由于仓库当前不提交 `uv.lock`，依赖版本漂移需通过后续锁文件变更治理。
- **来源指纹读取旧会话状态** → 对指纹查询启用实体刷新，并保留独立事务变更回归；若未来切换到更高隔离级别，应重新验证并发契约。
- **视觉基线与 macOS 镜像仍可能漂移** → CI 与本机使用明确分离的 `ci/` 和默认快照目录；视觉差异继续阻断对应环境的检查，基线更新必须单独审查并记录，产品 UI 变化需要同时更新两套基线。
- **测试诊断 artifact 含有意外敏感信息** → 所有夹具使用去标识化数据，上传仅限失败时的测试结果目录，保留期为 7 天，不上传环境变量或凭据。
- **PostgreSQL 测试互相清理 schema** → 每次 workflow 使用独立 service container；同一 job 内保持 pytest 默认串行行为，不并发复用测试库。

## Migration Plan

1. 先复现并修复 Web 测试误报，更新已审查的 `1024×768` 快照。
2. 修复 PostgreSQL 投影并发/来源指纹回归，并将迁移测试限制在可逆迁移边界；先运行窄范围红绿验证。
3. 新增并本地静态校验 `pr-checks.yml`，使用 `uv sync` 安装 `pyproject.toml` 声明的依赖，分别运行标记过滤后的 SQLite/PostgreSQL 功能套件、在 `macos-26-intel` runner 专用 `_test` PostgreSQL 上隔离运行的双后端财富性能套件和前端全套命令；未来若提交 `uv.lock`，再切换到锁定同步。
4. 两个 worktree 分别完成各自变更的直接相关文件和单变更验证后，将本变更加入现有 `feat/cross-platform-experience`，保持 `refactor/web` 为基线；合并后的 feature 分支再统一运行联合验收，通过后推送并创建 `feat/cross-platform-experience` → `refactor/web` 的 PR。把 run URL、commit、时间和结果回写任务记录。
5. 若 CI 失败，优先回滚 workflow 文件或修正对应 job；不需要数据库迁移或应用回滚。若快照变更被拒绝，只恢复该二进制基线，不影响其他测试和 workflow。

本变更没有 Open Questions；所有会改变范围、检查集合或数据库策略的决策已在需求澄清中确认。
