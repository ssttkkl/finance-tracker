# Tasks: Material 3 authentication screens

## 1. Think

- [x] Confirm scope: migrate only Compose login/registration controls to Material 3 under `FinanceTheme`; preserve auth behavior, fields, copy, registration spacing, and the 44 dp return target; add about 15 dp to login; extract an internal `commonMain` responsive shell.
- [x] Read project context, current cross-platform presentation spec, UI design rules, existing auth artifacts, implementation, theme, and feature map.
- [x] Classify as B: local presentation change in the existing architecture; no API, route, domain, data, or permission changes.

## 2. Plan

- [x] Record the proposal and cross-platform presentation delta for M3 controls, title spacing, and the shared shell.
- [x] User authorized this Pen CLI session; update `design/login.pen` through the official Pen CLI. Updated 24 existing boards and added focus boards for both auth modes in all three classes (30 total).
- [x] Check representative `COMPACT` 390 × 900, `REGULAR` 768 × 900, and `WIDE` 1440 × 900 boards, including focus, validation, loading, and request-error states. Export light and dark review pages to `/tmp/auth-page-pen-review-20261011/export.pdf` and `/tmp/auth-page-dark-pen-review-20261011/export.pdf`.
- [x] Complete Pen node-to-Compose mapping in `design.md`, including responsive layout, geometry, spacing, colors, typography, shapes, semantics, and states.
- [x] Present the updated Pen preview and receive the user's confirmation before implementation.

## 3. Task breakdown and consistency

- [x] Reconcile proposal, delta, design, and tasks after Pen confirmation; each accepted requirement has implementation and review coverage.
- [x] Map the 44 dp back hit area, aligned first-email controls, field semantics, keyboard actions, validation states, and responsive shell in `design.md`.
- [x] Cross-platform Impact Check: shared Compose, Compose Web, Android, and iOS are affected because the screens live in `commonMain`; legacy React Web and Expo are out of scope; API and domain contracts are unchanged.

## 4. Build

- [x] Implement `AuthResponsiveShell` in `commonMain` and route both modes through it.
- [x] Replace auth-only Unstyled fields/buttons with Material 3 controls under FinanceTheme; remove custom field/button/arrow visuals and obsolete wrappers while retaining semantic, focus, and keyboard logic.
- [x] Preserve fields, copy, validation, error, busy/disabled behavior, and request flow; update `docs/feature-map.md`.
- [x] Keep Compose Unstyled dependencies used by other screens; auth code no longer imports or calls Unstyled controls.
- [x] Match Pen geometry after runtime review: use a separate M3 feedback `Text` to preserve live-region semantics, reduce the post-password helper gap to 2 dp, and set the submit button to the FinanceTheme small shape.

## 5. Review

- [x] Review source changes against confirmed Pen mapping and accepted scope; no out-of-scope screen, API, field, or copy changes found.
- [x] Compare production Web screenshots with confirmed Pen boards at all three responsive classes, in light/dark and represented focus, validation, busy, and request-error states.
- [x] Verify the first email control is at y=142 dp on compact for both modes and 110 dp below the card top on desktop for both modes; at 768/1440 the login card is 440 × 414 dp (412 ±2 target), registration is 440 × 466 dp, and the back target is 44 × 44 dp. No horizontal overflow at tested widths.
- [x] Review retained behavior: semantic IDs and accessible names remain; password fields use `PasswordVisualTransformation`; email advances to password; password advances to confirmation or submits; confirmation submits; return and mode-entry targets remain 44 dp.

### Review findings

- P1: none.
- P2: initial runtime comparison found the registration card 6 dp too tall and the M3 submit button pill-shaped. Resolved by changing the feedback spacing and explicitly using the FinanceTheme small button shape; the production screenshots were regenerated and rechecked against Pen. No open P2 findings.
- P3: none.
- Final design review conclusion: no blocking or major visual differences remain in the reviewed Compose Web states and viewports. Android/iOS device rendering remains unverified.

## 6. Test and QA

- [x] Run `cd compose && ./gradlew :shared:compileCommonMainKotlinMetadata :webApp:wasmJsBrowserDistribution --init-script /tmp/ft-remove-tencent.gradle --no-configuration-cache --console=plain` after final code changes — passed. Webpack emitted one dynamic-dependency warning and asset-size warnings; build completed successfully.
- [x] Run targeted Playwright auth E2E on the production Wasm preview, Playwright 1.62.1 / Chrome for Testing 151.0.7922.34:
  `cd web && PLAYWRIGHT_CHROME_PATH="$HOME/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" npx playwright test -c playwright.compose.config.ts tests/compose-access.e2e.ts --grep 'Compose 认证页面匹配确认稿的布局、标题与校验|Compose 登录显示提交错误并可切换注册|Compose 登录忙碌态禁止重复提交并锁定输入|Compose 认证必填校验写入不含输入值的诊断日志'`
  Result: 4 passed.
- [x] Run `cd web && npm test` — Vitest 3.2.7, 15 files / 152 tests passed.
- [x] Run production-preview browser QA at `http://127.0.0.1:5186/` in Chrome 151, at 390 × 900, 768 × 900, and 1440 × 900. Exercise login/register, keyboard focus and next-field navigation, validation, mocked request failure, busy/disabled state, and light/dark themes. API requests were intercepted with Playwright fixtures; no real account or backend data was changed. Busy-state retry produced one request and the field stayed locked.
- [x] The earlier visual-pass screenshots were superseded by the final Material 3 captures retained under `screenshots/auth-m3/{light,dark}/`; see the follow-up evidence below.
- [x] Browser result: no page errors, horizontal overflow, or unexpected failed requests. The mocked 401 responses and their console messages are expected. Chrome reported the existing Kotlin Wasm `wasmExports` memory-access deprecation warning; app diagnostic entries were expected for validation/error fixtures.
- [x] Run `git diff --check` — passed. Run `openspec validate auth-page-spacing-shell --type change --strict` — passed.
- [x] Record platform scope: Android/iOS target builds and device QA were not run in this turn; only common metadata and Web Wasm targets were compiled. Native rendering remains a follow-up verification item.

## 7. Release

- [x] Record delivery evidence, current `HEAD`, comparison baseline, unresolved platform verification, and rollback approach below. No commit, push, or deploy was performed.
- [x] 2026-10-12 follow-up: synced the delta into `shared/cross-platform-presentation` and archived this change at `openspec/changes/archive/2026-10-12-auth-page-spacing-shell/`. `openspec validate --all --strict` passed 28/28, `openspec doctor` reported no issues, and `git diff --check` passed. The archive CLI reported a non-blocking proposal heading warning because the scope section is titled `Scope` rather than `What Changes`.

## 8. Reflect

- [x] Removing auth-only Unstyled wrappers removed duplicated control visuals. Broader Compose controls remain out of scope. Record native device verification as the remaining follow-up.

## Follow-up visual revision: standard Material 3 appearance (2026-10-12)

- [x] Confirm the revised visual scope: use standard Material 3 outlined-field labels and supporting text, control dimensions, and auth-local shapes; preserve FinanceTheme colors/typography and all authentication behavior, fields, and copy.
- [x] Read the UI design rules, current Finance token sources, glossary, and Chinese documentation guidance. No domain term or user-facing copy changed, so `DOMAIN_GLOSSARY.md` did not need an update.
- [x] Update `design/finance-ui.lib.pen` through the official pen.dev CLI with reusable `Finance/Auth/M3/OutlinedTextField` (`FWJjz`), `PrimaryButton` (`O8M8q`), and `TextButton` (`rL9ZT`) components.
- [x] Update all 30 existing boards in `design/login.pen` through the official pen.dev CLI; keep the three responsive classes and all prior authentication states.
- [x] Review empty, floating-label focus, validation-error, busy/disabled, login request-error, registration request-error, and default desktop layouts. `TakeScreenshot` showed no clipping; a geometry query found no Pen clipping problems across the 30 boards. The 44 × 44 dp registration return targets remain present.
- [x] Export light and dark Pen review PDFs: `/tmp/auth-m3-pen-review-20261012/export.pdf` and `/tmp/auth-m3-dark-review-20261012/export.pdf`.
- [x] Update `design.md` with the responsive geometry, component IDs, source token mapping, implementation properties, and cross-platform impact.
- [x] Cross-platform Impact Check: shared `commonMain`, Compose Web, Android, and iOS are affected; legacy React Web and Expo, APIs, and domain contracts are unchanged.
- [x] Present these revised Pen boards and receive the user's confirmation before changing authentication code. The user confirmed with “继续” on 2026-10-12.

### Follow-up planning evidence

- Pen CLI: `pen version` reported `0.3.11`; both Pen files were edited through `pen interactive` and saved from the official CLI output.
- Visual review: Pen `TakeScreenshot` on compact login/register, focus, validation error, busy, request error, regular login/register, and wide login. A geometry query checked all 30 auth boards; it reported no clipped nodes and confirmed the 44 × 44 dp return target.
- Light preview: `/tmp/auth-m3-pen-review-20261012/export.pdf` — 14 pages at 390 × 900 pt.
- Dark preview: `/tmp/auth-m3-dark-review-20261012/export.pdf` — 6 pages at 390 × 900 pt.
- `git diff --check` — passed after the Pen and OpenSpec updates.
- `openspec validate auth-page-spacing-shell --type change --strict` — valid after the Pen and OpenSpec updates.
- Final Material 3 implementation build: `cd compose && ./gradlew :shared:compileCommonMainKotlinMetadata :webApp:wasmJsBrowserDistribution --init-script /tmp/ft-remove-tencent.gradle --no-configuration-cache --console=plain` — passed in 1m 35s. Webpack reported the existing dynamic-dependency and asset-size warnings; Wasm production distribution completed.
- Follow-up review time: 2026-10-12 00:47 Asia/Shanghai. `HEAD` and comparison baseline: `ea81381f5770a2fb68c951325eeaeae35beef149b`.
- The user confirmed the revised Pen with “继续” on 2026-10-12. The earlier Compose build, test, and browser QA apply only to the prior visual revision; rerun them after this implementation. No commit, push, or deploy was performed.

### Implementation and verification after Pen confirmation

- [x] Change the shared authentication fields to use the Material 3 `label` and `supportingText` slots and match the confirmed 56 dp field geometry and auth-local 8 dp shape.
- [x] Use the confirmed 8 dp primary-button shape and 12 dp desktop-card shape without changing FinanceTheme's global shape scheme.
- [x] Preserve keyboard actions, field semantics, IDs, accessibility names, live regions, request data, progress state, validation behavior, and all visible copy.
- [x] Update `docs/feature-map.md` to reflect the completed cross-platform presentation change.
- [x] Rebuild common metadata and the production Compose Web Wasm target after the code changes.
- [x] Run affected Vitest/Playwright checks and real-browser QA at 390 × 900, 768 × 900, and 1440 × 900 for login, registration, keyboard/focus, validation/error, busy/disabled, and request-error flows.
- [x] Compare final browser views with the confirmed Pen boards; record screenshots, node/Compose measurements, geometry differences, and independent design review findings. Keep differences within 2 dp and verify token colors in light and dark themes.
- [x] Re-run `git diff --check` and strict OpenSpec validation after implementation; recheck affected Android/iOS compilation and record any device QA that cannot be completed.
- [x] Record the validation `HEAD`, baseline, commands, results, remaining risks, rollback, and release readiness. The user later explicitly authorized committing and pushing to `origin/refactor/kmp`; deployment was not authorized.

### Final Material 3 browser and review evidence

- [x] Run the focused auth browser suite on the production Wasm preview in light and dark themes. Playwright 1.62.1 / Chrome for Testing 151.0.7922.34; each run passed 9 tests, covering accessible email/password names, Pen geometry and validation, successful registration request shape, submit/request errors, busy/disabled state, keyboard next-field navigation, floating labels, and privacy-safe diagnostics. API calls used test fixtures; no real account or backend data was changed.
- Commands (run from `web/`):
  ```sh
  PLAYWRIGHT_CHROME_PATH="$HOME/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" npx playwright test -c playwright.compose.config.ts tests/compose-access.e2e.ts --grep 'Compose 登录输入框向辅助技术公开邮箱和密码标签|Compose 认证页面匹配确认稿的布局、标题与校验|Compose 注册成功并且请求不包含确认密码|Compose 登录显示提交错误并可切换注册|Compose 登录请求错误适配三类宽度|Compose 登录忙碌态禁止重复提交并锁定输入|Compose 登录键盘操作从邮箱进入密码|Compose 登录字段获得焦点后显示浮动标签|Compose 认证必填校验写入不含输入值的诊断日志'
  FT_COMPOSE_COLOR_SCHEME=dark PLAYWRIGHT_CHROME_PATH="$HOME/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" npx playwright test -c playwright.compose.config.ts tests/compose-access.e2e.ts --grep 'Compose 登录输入框向辅助技术公开邮箱和密码标签|Compose 认证页面匹配确认稿的布局、标题与校验|Compose 注册成功并且请求不包含确认密码|Compose 登录显示提交错误并可切换注册|Compose 登录请求错误适配三类宽度|Compose 登录忙碌态禁止重复提交并锁定输入|Compose 登录键盘操作从邮箱进入密码|Compose 登录字段获得焦点后显示浮动标签|Compose 认证必填校验写入不含输入值的诊断日志'
  PLAYWRIGHT_CHROME_PATH="$HOME/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" npx playwright test -c playwright.compose.config.ts tests/compose-access.e2e.ts --grep 'Compose 访问页在目标窗口宽度无横向溢出'
  ```
- [x] Run `Compose 访问页在目标窗口宽度无横向溢出` in Chrome on the production preview. Passed at 320, 375, 390, 414, 768, and 1440 px.
- [x] Capture and retain 18 screenshots per theme under `screenshots/auth-m3/light/` and `screenshots/auth-m3/dark/`: login default at 390/768/1440, email focus at 390/768/1440, keyboard-next at 390, busy at 390, request error at 390/768/1440, registration default at 390/768/1440, validation at 390/768/1440, and registration submit error at 390.
- [x] Independently inspect the final production captures against the confirmed Pen boards. At 390 px, the visible email outline begins at y=118 dp in both modes; at 768/1440 px it begins 86 dp below the card top. Registration password/confirmation/submit outlines/actions align at 166/262/342 dp relative to the card on desktop; login and registration cards measure 378/414 dp high. Browser assertions include the M3 label slot's 8 dp clearance above its 56 dp outline and the 44 × 44 dp back hit target. No measured geometry difference exceeded 2 dp; light/dark FinanceTheme roles match the Pen. Final finding: no open P1/P2/P3 design findings.
- [x] Confirm browser diagnostics: no unexpected page errors or failed requests; expected mocked 401 responses and validation diagnostics were observed. The existing Kotlin Wasm `wasmExports` memory-access deprecation warning remains.
- [x] Compile affected Android, iOS Simulator, and iOS device targets. Native device rendering was not performed because no Android/iOS device session was available; record this as a presentation verification limitation.
- [x] Run final `git diff --check` and `openspec validate auth-page-spacing-shell --type change --strict`; both passed after code and task evidence updates.

## Current gate

The standard Material 3 implementation and confirmed Pen revision have passed the required Web, cross-platform compile, responsive, and artifact checks. No implementation or browser-QA gate remains. Native target compilation passed; native device rendering remains unverified. The user authorized committing and pushing this validated change to `origin/refactor/kmp`; deployment is not included.

## Evidence and release record

- Validation `HEAD`: `ea81381f5770a2fb68c951325eeaeae35beef149b`.
- Comparison baseline: the same `HEAD`. The tested working-tree snapshot is included in the delivery commit on `refactor/kmp`.
- Release authorization: the user explicitly requested commit and push to `origin/refactor/kmp`; no deployment was authorized.
- `git diff --check`: passed after implementation and final task evidence updates.
- OpenSpec validation: `openspec validate auth-page-spacing-shell --type change --strict` — valid after final task evidence updates.
- Vitest: `cd web && npm test` — 15 files / 152 tests passed in 2.93 seconds.
- Common metadata compile and Compose Web production build: command and result recorded above. A first dependency resolution attempt failed because the local Tencent mirror lacked Compose Unstyled artifacts; retry used Maven Central. A missing Ktor iOS metadata artifact was resolved by bypassing the Aliyun Ktor mirror for that invocation. Repository dependency declarations were not changed.
- Browser session: local production Wasm preview at `http://127.0.0.1:5186/`, Chrome for Testing 151.0.7922.34, Playwright 1.62.1. The focused authentication matrix passed 9/9 in light and 9/9 in dark; overflow check passed at 320/375/390/414/768/1440 px. The 36 final M3 screenshots are retained under `screenshots/auth-m3/{light,dark}/`; test routes used mocked API fixtures.
- Cross-platform compile: `cd compose && ./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --init-script /tmp/ft-remove-tencent.gradle --no-configuration-cache --console=plain` — `BUILD SUCCESSFUL` (Android, iOS Simulator arm64, and iOS arm64 targets; 21 actionable tasks, 3 executed / 18 up-to-date). No native device QA was possible in this run.
- Rollback: revert this change's auth code, Pen design, feature-map entry, and OpenSpec artifacts as one unit; no external side effects were made.
