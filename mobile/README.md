# Finance Tracker Native

`mobile/` 是 Finance Tracker 的 Expo Android/iOS 客户端。它使用 Expo Router 和根级 npm workspace；业务数据、权限、金额与导入结果仍由 Python API 负责，Native 只负责界面、设备存储和系统文件选择。

## 启动

在仓库根目录安装依赖并启动后端：

```bash
npm install
FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db' \
  uv run uvicorn ft.web.app:create_runtime_app --factory --host 127.0.0.1 --port 8000
```

模拟器可访问本机 API 时，在另一个终端运行：

```bash
EXPO_PUBLIC_FT_API_ORIGIN='http://127.0.0.1:8000' npm run start:mobile
npm run ios
```

真机调试时将地址换成开发机局域网地址，例如 `http://192.168.1.10:8000`；生产 Native 构建必须使用 HTTPS。Android development build 使用：

```bash
npm run android
```

## Native 路由

```text
src/app/
  index.tsx              # 会话守卫
  (auth)/login.tsx       # 登录 / 注册
  (app)/workspace.tsx    # 工作区选择
  (app)/ledger.tsx       # 收支账本
  (app)/record.tsx       # 手工记账
  (app)/import.tsx       # 账单导入与关系审查
```

跨平台 API 契约、请求客户端、导入状态和设计 token 位于 `../packages/`。Native 专属的 SecureStore、文件来源、导航和 `StyleSheet` 留在本目录。

## 校验

```bash
npm run test --workspace finance-tracker-mobile
npm run typecheck --workspace finance-tracker-mobile
npm run export:android --workspace finance-tracker-mobile
npm run export:ios --workspace finance-tracker-mobile
```

原生依赖的预构建兼容处理位于 `plugins/withExpoModulesJsiXcode26.js`；它只在依赖源码形状符合预期时执行，并由 Expo `prebuild` 自动应用。

## GitHub Actions Native CI

`.github/workflows/mobile-ci.yml` 会在 Pull Request、推送到 `refactor/web` 或手动触发时运行共享包和 Mobile 校验，并生成未签名的测试产物：

- `finance-tracker-android-debug`：Android Debug APK。
- `finance-tracker-ios-simulator`：iOS Simulator `.app` 压缩包。

这些产物仅用于开发和测试，不包含 App Store 或 Google Play 发布所需的签名；商店发布需要后续单独配置 EAS、证书和受保护的 CI secrets。
