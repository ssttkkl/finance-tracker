# Mobile Login API Origin Override Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让开启调试构建开关的 Native 登录/注册流程可以选择并持久化一个经过校验的 API origin，同时保持默认构建使用构建地址。

**Architecture:** 在 `mobile/src/platform/config.ts` 中集中处理构建开关、origin 校验和进程内地址覆盖；在独立的 SecureStore adapter 中保存覆盖值。`SessionProvider` 在首次 `session` 请求前恢复覆盖，并在登录/注册成功后保存；`mobileApiClient` 继续通过动态 `baseUrl` 读取当前 origin。登录页面只在开关开启时渲染地址字段和恢复默认操作。

**Tech Stack:** Expo 57、React Native、TypeScript、Vitest、`expo-secure-store`、共享 `@finance-tracker/presentation`。

**Spec:** `openspec/changes/mobile-login-api-origin-override/specs/mobile-login-api-origin/spec.md` 与 `openspec/changes/mobile-login-api-origin-override/design.md`

## Global Constraints

- `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` 只有值为 `1` 或 `true` 时开启，缺省关闭。
- `https` origin 允许使用；`http` 仅非生产构建且带显式端口时允许；不得接受路径、查询参数、片段、凭据或空值。
- 只保存成功认证使用的有效地址；失败认证不替换已保存地址；退出登录不清除地址。
- 默认构建不读取、不使用历史地址覆盖，并且不展示调试控件。
- 不增加依赖，不修改后端协议、Token、工作区、账本或 Web 登录页。
- Native 调试控件复用 Cobalt tokens、4-point 间距、44 px 触控目标，并保持 320/375/414/768 px 无横向滚动。
- 生产代码变更前必须观察到测试因行为缺失而失败；不提交、不推送，除非用户另行授权。

---

### Task 1: Origin configuration contract

**Files:**
- Modify: `mobile/src/platform/config.ts`
- Modify: `mobile/src/env.d.ts`
- Create: `mobile/src/platform/config.test.ts`
- Modify: `mobile/package.json`

**Interfaces:**
- Produces `nativeApiOriginOverrideEnabled(): boolean`、`normalizeNativeApiOrigin(value: string): string`、`nativeBuildApiOrigin(): string`、`nativeApiOrigin(): string`、`selectNativeApiOrigin(value: string): string`、`currentNativeApiOriginOverride(): string | null` 和 `resetNativeApiOriginOverride(): void`。
- `nativeApiOrigin()` remains the function passed as `createApiClient({ baseUrl })` and returns the current request origin.

- [x] **Step 1: Write the failing tests** for the exact flag values, origin normalization, invalid origin rejection, production HTTP rejection, disabled-build fallback, and runtime selection.

```ts
it("enables only explicit debug flag values", () => {
  vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "true");
  expect(nativeApiOriginOverrideEnabled()).toBe(true);
  vi.stubEnv("EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED", "0");
  expect(nativeApiOriginOverrideEnabled()).toBe(false);
});

it("normalizes a valid origin and rejects a path", () => {
  expect(normalizeNativeApiOrigin("https://api.example.com/")).toBe("https://api.example.com");
  expect(() => normalizeNativeApiOrigin("https://api.example.com/api")).toThrow("api_origin_invalid");
});
```

- [x] **Step 2: Run the focused test and verify the expected red failure**.

Run: `npm exec vitest run mobile/src/platform/config.test.ts`

Expected: FAIL because the new configuration exports do not exist or do not yet implement the requested behavior.

- [x] **Step 3: Implement the minimum configuration behavior**: parse the build flag, trim and validate the origin with the existing HTTP/HTTPS rules, keep an in-memory override only when the flag is enabled, and return the build address otherwise.

- [x] **Step 4: Run the focused test and verify green**.

Run: `npm exec vitest run mobile/src/platform/config.test.ts`

Expected: PASS with no unrelated test changes.

### Task 2: Local address persistence

**Files:**
- Create: `mobile/src/platform/apiOriginStorage.ts`
- Modify: `mobile/src/platform/config.ts`
- Modify: `mobile/src/platform/config.test.ts`

**Interfaces:**
- `ApiOriginStorage` has `get(): Promise<string | null>`, `set(value: string): Promise<void>`, and `clear(): Promise<void>`.
- `restoreNativeApiOriginOverride(storage): Promise<string>` restores one valid value before the first session request; malformed values clear and fall back.
- `persistNativeApiOriginOverride(value, storage): Promise<string>` validates, selects, and stores one origin only when debug mode is enabled.
- `clearNativeApiOriginOverride(storage): Promise<string>` clears the local override and returns the build address.
- `nativeApiOriginStorage` uses the existing `expo-secure-store` dependency and key `finance-tracker-api-origin-override`.

- [x] **Step 1: Write failing storage tests** using an in-memory `ApiOriginStorage` double; cover valid restore, malformed restore with clear, disabled-build ignore, normalized persistence, and reset.
- [x] **Step 2: Run `npm exec vitest run mobile/src/platform/config.test.ts` and verify these tests fail for missing restore/persist/clear behavior.**
- [x] **Step 3: Implement the injected storage functions and the SecureStore adapter without sharing the session-token key.** Storage failures may reject the helper, but the caller must keep a successful authentication result and the in-memory origin.
- [x] **Step 4: Rerun the focused storage tests and verify green.**

### Task 3: Session bootstrap and authentication lifecycle

**Files:**
- Modify: `mobile/src/state/session.tsx`
- Modify: `mobile/src/platform/api.ts`
- Modify: `mobile/src/platform/config.ts`
- Modify: `mobile/src/platform/apiOriginStorage.ts`

**Interfaces:**
- `SessionContextValue.apiOrigin` exposes `enabled`, `value`, `buildValue`, `select(value: string): string`, and `reset(): Promise<string>` to the auth screen.
- Session bootstrap awaits `restoreNativeApiOriginOverride(nativeApiOriginStorage)` before calling `client.session()`.
- Login and register call the existing `run` flow, then best-effort persist the selected override only after the server returns a successful `Session`.

- [x] **Step 1: Add a failing lifecycle test** proving a failed authentication does not call storage `set` and a successful authentication does.
- [x] **Step 2: Run that focused test and verify it fails because authentication persistence is not wired.**
- [x] **Step 3: Wire the existing dynamic `mobileApiClient` to `nativeApiOrigin`, hydrate before the first session request, expose the context state, persist after login/register success, and leave logout clearing only the session token/state.**
- [x] **Step 4: Run the lifecycle test plus the existing mobile guard tests and verify green.**

### Task 4: Native authentication UI and shared semantics

**Files:**
- Modify: `packages/presentation/src/semantic-ids.ts`
- Modify: `packages/presentation/src/copy.ts`
- Modify: `packages/presentation/src/platform-differences.ts`
- Modify: `mobile/src/app/(auth)/login.tsx`
- Modify: `mobile/src/env.d.ts`
- Modify: `mobile/README.md`
- Modify: `README.md`

**Interfaces:**
- Adds `semanticIds.authApiOrigin` and `semanticIds.authApiOriginReset`.
- Adds `copy.auth.apiOrigin`, `copy.auth.apiOriginReset`, and `copy.auth.apiOriginInvalid` without changing Web rendering.
- The Native form keeps one local draft across login/register mode changes and invokes `apiOrigin.select` only on submit.

- [x] **Step 1: Add failing presentation assertions** for the stable IDs and debug-only platform difference, then run the affected presentation test to observe red.
- [x] **Step 2: Implement the shared IDs/copy/difference and rerun the presentation test to green.**
- [x] **Step 3: Add the Native field after password and before authentication errors, with `keyboardType="url"`, no autocorrection, `testID={semanticIds.authApiOrigin}`, a 44 px reset target, and `testID={semanticIds.authApiOriginReset}`.**
- [x] **Step 4: Handle invalid input before the API call, disable field/reset during login, preserve the draft across mode toggles, and reset to the build address through the SessionProvider API.**
- [x] **Step 5: Update both Native and root README instructions with the exact build flag and keep the default flag-off behavior explicit.**

### Task 5: Verification and QA evidence

**Files:**
- Modify: `openspec/changes/mobile-login-api-origin-override/tasks.md`
- Modify: `openspec/changes/mobile-login-api-origin-override/design.md`

- [x] **Step 1: Run `npm run test --workspace finance-tracker-mobile` and `npm run typecheck --workspace finance-tracker-mobile`; fix failures without weakening assertions.**
- [x] **Step 2: Run `npm run test:shared`, `npm run typecheck:shared`, the relevant Mobile exports, `openspec validate --all --strict`, `openspec doctor`, and `git diff --check`; record exact results and `HEAD`.**
- [x] **Step 3: Run Hallmark `audit` on the final Native auth UI and the prototype; record critical/major/minor findings and remediation in `tasks.md`.**
- [x] **Step 4: Run real Native QA on phone `390×844`, tablet `768×1024`, and the applicable wide presentation window; cover default hidden, debug prefill, valid switch, invalid block, reset, success persistence, failed-login retention, logout retention, focus, keyboard, loading, and no horizontal scroll. Record screenshots, device/simulator, steps, and console/network errors.** iPhone 16 at 393×852, iPad (A16) at 820×1180, and Android Medium Phone API 36 at 1080×2400 passed the executable Native scope. The original Metro mismatch was corrected by binding the dev-client to its actual default port `8081`; Android NDK `27.1.12297006` was repaired before building. Android flag-on/off, origin validation, actual login routing through `10.0.2.2:8765`, logout retention, and cold-relaunch restoration passed. The app manifest is portrait-only, so the wide contract is covered by `layoutClassForWidth(1024/1366)` and prototype checks rather than an invalid forced-rotation screenshot. Screenshots and network/console evidence are recorded in the archived `tasks.md`.
- [x] **Step 5: Record PostgreSQL dual-backend verification as not applicable because this change has no database, schema, persistence fact source, migration, or backend behavior change.**

### Task 6: Final review and handoff

**Files:**
- Review: all files changed by this plan and the active change artifacts

- [x] **Step 1: Review the diff against `proposal.md`, `spec.md`, `design.md`, and the confirmed prototype; verify no Web or backend scope leaked in.**
- [x] **Step 2: Run a scoped search for user-visible wording and implementation terms; confirm the field uses “后端地址” and no database/service terminology.**
- [ ] **Step 3: Mark all completed OpenSpec tasks with evidence, sync the delta spec to `openspec/specs/`, and archive the completed change only after artifact/task completion checks pass.** Native QA follow-up is complete for the executable iOS scope; sync and archive remain pending.
