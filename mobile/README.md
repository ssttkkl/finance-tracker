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

需要在同一个调试构建中切换后端时，额外设置 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED='1'`。登录页会显示“后端地址”，成功登录或注册后保存本机选择；不设置该开关时不会显示该控件，也不会使用历史调试地址。

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

`.github/workflows/mobile-ci.yml` 会在 Pull Request、推送到 `refactor/web` 或手动触发时运行共享包和 Mobile 校验，并生成不依赖 Metro 的 Release-like 测试产物。原生构建前必须在 GitHub Repository Variables 中配置：

```text
EXPO_PUBLIC_FT_API_ORIGIN=https://api.example.com
```

该值必须是非空的 HTTPS origin，不得包含用户凭据、路径、查询参数或片段。缺失或不合法时，CI 会在构建和上传 artifact 前失败。JavaScript bundle 和资源会直接内置到 Android APK 与 iOS Simulator App 中，启动这些产物不需要运行 Metro。

- `finance-tracker-android-release`：内置 JavaScript、使用仓库测试密钥签名的 Android Release-like APK。
- `finance-tracker-ios-simulator-release`：内置 JavaScript、未签名的 iOS Simulator Release-like `.app` 压缩包。

Android 测试签名文件位于 `mobile/ci/finance-tracker-test.keystore`，alias 为 `finance-tracker-test`，store/key password 均为 `finance-tracker-test`。这是故意提交到仓库的非生产测试凭据，只用于 CI 安装和签名校验，不能用于发布、升级正式包或保护真实用户数据。iOS 产物不使用生产签名；商店发布需要后续单独配置 EAS、证书和受保护的 CI secrets。
