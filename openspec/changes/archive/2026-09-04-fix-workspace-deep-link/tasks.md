# 实施与验证任务

## 1. 思考

- [x] 复现线上根路径与工作区深链接的 HTTP 响应。
- [x] 阅读 UI 约束、项目上下文及现有 Web 路由和访问代码。
- [x] 按 `grilling` 设计树复核范围、非目标、验收和风险；结论已写入 `proposal.md` 与 `design.md`。

## 2. 计划

- [x] 确定为 Render 静态托管补 SPA rewrite。
- [x] 确定恢复工作区前缀解析、会话选择和客户端导航范围。

## 3. 测试先行与实现

- [x] 先添加路由解析、直接工作区入口和 Render rewrite 回归测试，并确认在缺少实现时失败。缺少实现时直接工作区测试未找到“分类管理”页面；配置测试在 `render.yaml` 添加前无法读取入口文件。
- [x] 实现最小路由与访问恢复逻辑。
- [x] 更新工作区导航的 URL 生成。
- [x] 添加 `render.yaml` 的静态站点 rewrite 声明，并用 Ruby YAML 解析检查通过。

## 4. 审查

- [x] 复核根因、修复边界、错误处理和现有根路径兼容性：URL 中的工作区 ID 只用于调用既有 `selectWorkspace`，无权时不会绕过服务端校验；未修改数据和权限模型。
- [x] 复核变更 diff 与 OpenSpec 一致性：无视觉结构改动，Hallmark 设计审查不适用；已覆盖托管 rewrite、客户端恢复、导航保留和错误回退。

## 5. 测试与 QA

- [x] 运行受影响 Vitest、TypeScript 构建、`git diff --check`：完整 `npm test -- --run` 为 12 个文件、114/114 通过；`npm run build` 通过；`git diff --check` 通过。
- [x] 用真实 Chromium 验证工作区根路径和子页面：`npm run test:e2e -- --grep '直接打开工作区根路径'` 为 1/1 通过；覆盖 1440 px、390 px、键盘焦点、控制台与请求失败检查，截图为 `/tmp/workspace-entry-1440.png`、`/tmp/workspace-entry-390.png`。工作区相关 E2E 为 3/3 通过。
- [x] 运行 `openspec validate --all --strict` 与 `openspec doctor`：28 项通过，0 失败；doctor 根目录正常。
- [x] 运行生产预览回归 `npm run test:preview`：7/7 通过。完整开发环境 E2E 为 27/28，唯一失败是既有暗色侧栏测试将实际 8 个导航文字节点固定断言为 7 个；本次未修改 CSS 或该测试，作为残余风险保留。
- [x] PostgreSQL 不适用：本次不涉及数据库、持久化或财务计算。

## 6. 发布准备

- [x] 记录验证基线、线上 rewrite 同步要求、回滚方式和未解决风险。实现验证基线为 `HEAD=efa313b3e604b38c0dbbd65582da8d84be55ef1e`，比较基线为 `git merge-base HEAD origin/main=8c18ed7ecff6b31cd5adcc18becb4e4e09035f55`；已获用户授权提交、推送、创建并合并目标为 `refactor/web` 的 PR，本次不执行部署。线上复核仍为根路径 200、工作区深链接 404；需将 `render.yaml` 的 rewrite 同步到现有 Render Static Site 后重新部署，回滚为移除该 rewrite 与本次路由改动。

## 7. 反思

- [x] 记录防复发测试和托管配置的验证入口：保留路由单测、AccessApp 直接入口测试、真实浏览器深链接测试和 `render.yaml` 配置契约测试；发布后必须用真实工作区 URL 检查 HTTP 200 与应用渲染。
