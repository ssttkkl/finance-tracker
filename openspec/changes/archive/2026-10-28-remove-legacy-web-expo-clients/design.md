## Context

参见 [proposal.md](proposal.md)。实时 `origin/main` 和当前 `HEAD` 均为 `0c038a75e34264bd6d4aa5cce2070727df9d9ff7`，工作区起始时干净。当前默认客户端由 `compose/` 提供；根目录 `web/`、`mobile/` 与 `packages/` 均不进入 Compose CI。五个 TypeScript 包只被旧客户端引用。根 `render.yaml` 只配置 `web/` 下的旧 React 静态站点。删除 `mobile/` 还要求改写 `tests/test_ci_workflow_contracts.py` 中对旧 README、签名插件和测试签名文件的断言，并将断言改为检查三个目录和旧站点配置均已删除。

## Goals / Non-Goals

**Goals:**

- 移除旧 React Web、Expo 客户端及只供这两个客户端使用的 TypeScript 包。
- 让当前主规格、导航和开发文档只描述仍支持的 Compose 客户端。
- 保留财务 API、数据、后端、Compose 页面和已归档变更证据。

**Non-Goals:**

- 不改 Compose UI、导航、客户端 API、服务端接口、认证语义、财务规则或持久化。
- 不清理 Git 历史，不改写已归档的 OpenSpec 变更。
- 不执行发布、应用商店下架、合并或推送。

## Decisions

### 删除整个旧客户端边界

删除根目录 `web/`、`mobile/` 和 `packages/`。每个 TypeScript 包都只由旧客户端使用；Compose QA 的 package 和 Kotlin 实现独立存在，不引用这些包。`mobile/` 中的 Expo 配置、插件、资源、测试及仓库内测试签名文件随目录一并移除。

**备选方案：** 只删应用目录并保留 `packages/`。不采用，因为这会留下没有仓库内消费者的客户端契约和无构建入口的代码。

### 用 Compose 合同替代旧客户端状态约束

通过主规格 delta 移除 React 源码保留要求、Expo 登录地址覆盖能力和旧客户端布局保持不变的要求。跨端规格保留可验证的 Compose Web、Android、iOS 行为；账本表格列宽改为规格中的明确比例，不再依赖已删除的 React 源码作为比较基准。工作区与详情面板的 Compose 行为保持原合同。

### 把现行文档收敛到当前入口

更新 `AGENTS.md`、`README.md`、`SKILL.md`、`DOMAIN_GLOSSARY.md`、Compose 部署说明、现金导入暂存说明、投资架构文档、UI 规范、设计简报、功能地图、认证规格导航和迁移对照。功能地图保留当前 Compose 页面及真实 QA 缺口，删除旧 React/Expo 页面索引和已过时的 Native 对齐 TODO。归档变更、旧 QA 证据和 Git 历史保留原样。

### 删除旧静态站点部署配置

删除根 `render.yaml`。它唯一配置的服务名为 `finance-tracker-web`，根目录为 `web`，构建命令为 `npm ci && npm run build`，发布目录为 `dist`；Compose Web 的 Render 构建已由 `compose/README.md` 中独立配置说明，不依赖此文件。

### 保持默认 CI 边界不变

CI 已只构建 Compose。保留 `test_ci_workflow_contracts.py` 中的 Compose wrapper 检查，把旧客户端存在性断言改为确认 `web/`、`mobile/`、`packages/` 已退役，并继续确认没有旧 Expo CI 工作流。先运行这条回归确认它因目录仍存在而失败，再删除目录并复跑。

## Risks / Trade-offs

- **依赖旧客户端的开发者无法继续在仓库内运行它们** → 这是已确认的目标；README 与技能说明明确 Compose 是当前客户端入口。
- **删目录会移除旧客户端测试签名 keystore** → 不读取或迁移文件，也不重写 Git 历史；因此该文件仍可从先前提交恢复。本变更不触及外部签名配置或密钥轮换。
- **删除静态站点配置不会删除远端服务** → 本变更只移除仓库中的根 `render.yaml`，不触发 Render 服务下线或部署。
- **历史文档含有已删除路径** → 归档 OpenSpec 和明确标为历史的迁移记录保留；现行索引不再链接到已删除实现。
- **删除后可能存在漏改路径引用** → 搜索现行源码和文档的 `web/`、`mobile/`、`packages/`、Expo 配置和旧 Render 服务引用，并检查 Markdown 链接和 CI 契约测试。

## Migration Plan

1. 更新测试契约与 OpenSpec delta，并验证 delta 覆盖范围。
2. 删除旧目录及仅配置旧 React 站点的根 `render.yaml`，修正现行说明与导航，退役 Expo 专属主规格。
3. 运行定向测试、Python/Compose 全回归、受影响构建、严格 OpenSpec 校验、链接与 diff 检查。
4. 归档前同步全部 delta；若需要回滚，恢复删除的目录和旧规格，并反向恢复文档与测试断言。没有数据库或用户数据迁移。

## Open Questions

无。
