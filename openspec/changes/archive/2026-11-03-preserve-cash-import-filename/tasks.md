# 导入文件名按上传原样展示

## 1. 思考

- 阅读 `openspec/specs/import/statement-import/spec.md`、`openspec/specs/import/cash-import-session/spec.md`、`src/ft/application/cash_import_context.py`、`src/ft/application/cash_import_funding.py` 与相关测试。
- 根因：`_display_filename` 主动改写上传文件名（删密码、删密码标记、清空括号、长数字替换为尾号、扩展名改写），前端 `CashImportPreparation.kt:82` 直接展示服务端 `display_name`；上传中显示本地原名，解析后跳变为改写名。
- 用户已明确要求停止重命名/脱敏；B 类变更，不升级理由：不新增领域能力、不改金额/币种/持久化/权限/路由/公共契约形状或依赖，只改一个应用层展示值及其测试。

## 2. 计划

- 移除 `_display_filename` 的脱敏与改写，只保留 basename 与 255 字符上限。
- 微信缺少昵称时来源账户会话标签改走 `_label(file.filename)`，保持账户标签仍脱敏，避免移除整体脱敏后退化为完整账号。
- 按用户决定撤销旧设计“从展示文件名去除密码”的约束；在 change delta 中用 `MODIFIED Requirements` 明确限定密码禁令只针对用户填写的密码字段值。
- 回滚：恢复 `_display_filename` 原逻辑及对应测试，无数据迁移。

## 3. 任务拆分

- 先改测试为期望原样展示并确认失败。
- 再最小修改生产代码。
- 更新 OpenSpec delta、契约文档与任务证据。
- 执行受影响测试、OpenSpec strict 校验、编译与 diff 检查。

## 4. 构建

- [x] `src/ft/application/cash_import_context.py`：`_display_filename` 只取 basename 并截断 255。
- [x] `src/ft/application/cash_import_funding.py`：微信无昵称回退标签改为 `_label(file.filename)`。
- [x] 更新 `tests/test_cash_import_multipart.py`、`tests/contract/test_parsed_cash_import_context.py`、`tests/contract/test_cash_import_session_api.py`、`tests/test_cash_import_funding.py`。
- [x] 新增 `openspec/changes/preserve-cash-import-filename/` proposal、spec delta 与 tasks。
- [x] 更新 `docs/import/cash-import-redesign-contracts.md` 的 `display_name` 与 `session_label` 说明。

## 5. 审查

### 5a 范围与工程审查

- 范围 diff 仅涉及文件名展示、微信来源账户标签兜底、对应测试与文档；未改 API 字段结构、数据库、路由或依赖。
- 生产 diff 复核：`_display_filename` 由 13 行改写逻辑缩减为 basename + 截断；调用方同步改为单参数；无新增异常路径。

### 5b 代码库防劣化检查

- 范围判断：触及公开响应字段 `display_name` 的取值语义 → 调度 `$contract-design-review`；未改 UI 组件、布局或 token → 不调度 `$ui-reuse-review`。
- 独立契约评审基线：工作树未提交改动，`HEAD` `6c436265904da59a75ece6722f3f48a95e345f4e`。
- 评审发现与处置：
  - **阻断：新增需求与主规格密码禁令冲突。** 已在 delta 增加 `MODIFIED Requirement`「导入失败不泄露敏感来源数据」「临时导入会话保存来源但不写入业务账本」与「文件级扫描状态可分别重试且有效文件可继续」，把禁令限定为用户填写的密码字段值，并明确文件名文本按原样展示规则处理。
  - **中：契约文档仍写“脱敏 display_name”。** 已更新 `docs/import/cash-import-redesign-contracts.md`。
  - **低-中：缺少“提交密码与文件名字面同值”的 HTTP 用例。** 已新增 `test_filename_containing_submitted_password_is_returned_verbatim`；预览 `file_results` 复用 `cash_import_file_view`，无需重复实现。
  - **低：两处 `display_name` 语义分叉。** 已在契约文档分别说明文件元素与来源账户元素的语义和信任级别，不新增字段。
- 复审结论：4 项发现全部关闭，无新增阻断。另按复审建议同步 proposal 范围、为「临时导入会话保存来源但不写入业务账本」补消歧正文，并更正下方分支证据。

## 6. 测试与 QA

- [x] 红：新增/修改回归共 7 failed，覆盖文件名原样、255 截断、微信标签脱敏；之后受影响回归转绿。
- [x] 完整后端回归 4625 passed、1446 skipped、73 performance deselected；另以显式专用 PostgreSQL 16 UTF8 测试库跑同一会话/恢复/API矩阵 140 passed。详细命令见同组并发追加 tasks。
- [x] `uv run python -m compileall -q src` 通过。
- [x] 真实 Chrome、390×844、`http://127.0.0.1:5187` 上传含长数字和密码字样的合成文件，上传前后原名一致；见 `retry-browser-final.log` 及 `review-retry-final/`。
- [x] 同组共享完整 471 tests 通过，Android/iOS 编译与 production Wasm 构建通过；无原生设备交互验证。
- [x] OpenSpec strict 28 passed、doctor、当前 diff 检查通过；归档后再校验主规格。

## 7. 发布

- [x] 文档收尾：文件名与来源账户标签区分、密码字段边界已进入 delta 和导入契约；功能地图与 OpenSpec apply 参考同组维护。README、配置、迁移和词表无新内容，无需改动。
- [x] 回滚恢复原 `_display_filename` 逻辑及测试，无数据库迁移。
- [x] 用户已授权与并发追加、加载失败阻断一起 commit/push，并提交同一 PR 到 main；无 merge/deploy 授权。
- [x] 创建 PR（本次交付，与文件名、并发追加、加载阻断合并）；演示确认与归档待办，等待 PR 检查。

## 验证证据

- 分支 `fix-import-original-filename-display`；HEAD/比较基线 `6c436265904da59a75ece6722f3f48a95e345f4e`，验证对象为本次工作树 diff；2026-11-02，Node 26.8.1、OpenSpec 1.7.0、Python 3.11.12。
- 日志 `/tmp/cash-import-backend-regression.log` 与 `/tmp/ft-cash-import-qa-20261102/dual-backend.log`、`retry-compose-final.log`、`retry-browser-final.log`。
- 旧会话保留已有名称至 TTL 过期；没有迁移旧会话。文件名文本可含用户自带密码字样，解密密码字段仍不写日志、会话或账本。
