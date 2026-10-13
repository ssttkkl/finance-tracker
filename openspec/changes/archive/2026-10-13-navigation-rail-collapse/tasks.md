## 1. Thinking

- [x] 1.1 Read the shared presentation requirement, project context, UI rules, app shell, and existing browser coverage; confirm this is a localized interaction regression with explicit expected behavior.
- [x] 1.2 Cross-platform impact: shared `commonMain` rail behavior affects Compose Web, Android, and iOS; browser hover applies to pointer-capable targets, touch remains click-driven, and keyboard focus remains supported. No React/Expo, API, route, persistence, or database impact.

## 2. Plan

- [x] 2.1 Record the updated hover, mouse-focus, collapse-control, and keyboard-focus contract in the modified presentation requirement.
- [x] 2.2 No Pen or standalone prototype update is needed: this local defect restores sidebar behavior without changing layout, visual hierarchy, or navigation structure; verify the final control and focus behavior in the production browser.

## 3. Task consistency and test-first

- [x] 3.1 Added separate Playwright regressions for mouse navigation followed by pointer exit, and for collapsing while hover-expanded including re-entry. Baseline run `npm run test:e2e:compose -- --grep "desktop rail collapses after mouse navigation leaves focus behind|desktop hover-expanded rail control collapses then resumes hover after exit"` failed both tests at 768×900: the top-bar title stayed at x=256 px instead of returning to x=0 after pointer exit / collapse click. The initial test selector was corrected to use the shared top-bar title before this baseline run.

## 4. Build

- [x] 4.1 Separate keyboard-driven focus expansion from pointer-generated focus; the expanded-state control now collapses immediately and suppresses re-expansion until pointer/focus exit. Its accessible name now states the available expand/collapse action.
- [x] 4.2 Update the feature map's shell interaction evidence; preserve routes, destinations, account actions, and compact behavior.

## 5. Review

- [x] 5.1 Final diff review confirmed the two regressions and their fixes match the requirement. Keyboard focus, pointer exit/re-entry, compact drawer, workspace callbacks, route changes, and account menu remain covered; no unrelated implementation changes or new dependencies.
- [x] 5.2 The Hallmark audit action was not callable through this runtime, so I followed the repository's documented audit rubric and visually reviewed the final Chrome captures against the previously approved shell states. The change only alters focus/action semantics and adds no new layout, tokens, or components. Findings: 0 critical, 0 major, 0 minor; no fixes outstanding.

## 6. Validation and QA

- [x] 6.1 Chrome/Playwright production preview URL `http://127.0.0.1:5187/w/workspace-1/`. `FT_COMPOSE_PREVIEW_PORT=5187 npm run test:e2e:compose -- tests/compose-app-shell.e2e.ts` → 7 passed; final targeted rerun `FT_COMPOSE_PREVIEW_PORT=5187 npm run test:e2e:compose -- --grep "desktop rail collapses after mouse navigation leaves focus behind|desktop hover-expanded rail control collapses then resumes hover after exit"` → 2 passed. Covered pointer entry/exit at 600×900, 768×900, 1440×900; mouse navigation then leave and explicit collapse/re-entry at 768×900 and 1440×900; keyboard focus and Escape; compact drawer/account sheet at 320×844, 375×844, 390×844, 414×844. No console errors, failed requests, static HTTP errors, or horizontal overflow. Screenshots: `/tmp/compose-app-shell-after-mouse-navigation-768.png`, `/tmp/compose-app-shell-toggle-collapsed-768.png`, `/tmp/compose-app-shell-after-mouse-navigation-1440.png`, `/tmp/compose-app-shell-toggle-collapsed-1440.png`, `/tmp/compose-app-shell-compact-390.png`.
- [x] 6.2 `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:allTests :shared:compileAndroidDeviceTest --no-configuration-cache` → BUILD SUCCESSFUL (135 tasks; JS, Wasm, Android host, iOS Simulator tests; Android device-test compile). Compose Web production Wasm build ran through the Playwright `webServer` command and succeeded. `git diff --check` clean. Android device runtime QA was unavailable: `adb devices -l` returned no attached devices. Current `HEAD` and comparison base: `c7283cabe5fed6e619127c87c53470fd781fdd3c` on `refactor/kmp`; validation 2026-10-13 11:00 CST. Existing Gradle warnings include an unrelated deprecated Material 3 sheet API and bundler size/dynamic-dependency notices.
- [x] 6.3 Before archive, `openspec validate --all --strict` → 26 passed, 0 failed; after archiving, it → 25 passed, 0 failed; `openspec doctor` → no issues. The completed change was archived as `2026-10-13-navigation-rail-collapse`, which synchronized the modified requirement into the main spec. No database behavior changed, so PostgreSQL verification is not applicable. Cross-platform behavior is shared through `commonMain`; browser hover is pointer-specific, touch remains click-driven, and shared JS/Wasm/Android-host/iOS-Simulator tests passed.

## 7. Release

- [x] 7.1 Rollback is a revert of this focused rail-state change. No deployment or external release is in scope.

## 8. Reflection

- [x] 8.1 The two new browser regressions protect mouse-focus collapse, immediate toggle collapse, and restored hover after exit. Native device visual QA remains a follow-up when an Android device is available; shared Native test/build coverage passed.
