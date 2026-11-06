## Why

完整 Compose 浏览器套件在工作区管理与退出登录流程中偶发失败：Playwright 尝试滚动“退出登录”按钮时，该目标因 Compose 重建而脱离 DOM。该用例本地重复通过，CI 完整套件再次复现同一同步问题。

## What Changes

- 在该用例中复用现有稳定几何采样与坐标点击助手，避免对短暂失效的 DOM 节点执行滚动。
- 保留现有退出请求、页面状态和诊断断言。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。此 change 只调整浏览器测试同步方式，不改变用户可见行为。

## Impact

仅影响 `compose/qa/web/tests/compose-app-shell.e2e.ts` 与本 change 的验证记录；不修改生产代码、UI、财务规则、接口、数据库或依赖。按 C 类局部修复处理，CI 失败用例为复现证据；回退测试调用即可恢复原状。
