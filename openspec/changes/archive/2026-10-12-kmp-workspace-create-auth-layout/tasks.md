# Tasks

## 1. Think

- [x] Confirm the target is the KMP Native workspace-entry page on branch `refactor/kmp`.
- [x] Read the workspace-entry spec, KMP `WorkspaceAccessScreen`, auth responsive shell, auth controls, project context, and UI design rules.
- [x] Review `DOMAIN_GLOSSARY.md` and the Chinese documentation rules; reuse existing terms and add no new domain concept.
- [x] Record the existing empty-name validation, busy state, request-error retry, and success navigation behavior.
- [x] Classify as A: major user-visible page layout and responsive behavior change.

## 2. Plan and prototype

- [x] Create the OpenSpec proposal and full modified requirement for first-use layout and preserved behavior.
- [x] Create `design/workspace-entry.pen` prototype with `COMPACT` 390 px (`KV5AK`), `REGULAR` 768 px (`HetEf`), and `WIDE` 1440 px (`N1D59`) representative frames.
- [x] Cover default/empty, focus, validation error, request error, disabled/creating, and success route in Pen state board `Uqok8`.
- [x] Create standalone `prototype/index.html` for responsive preview; no production routing, backend, credentials, or network calls.
- [x] Record information architecture, removed redundancy, token/state decisions, and Pen-to-Compose property mapping in `design.md`.
- [x] Obtain user approval of the Pen prototype before implementing Compose UI; the user subsequently asked to complete the named change after reviewing the design.

## 3. Task breakdown and cross-platform consistency

- [x] Map each existing workspace-entry scenario to the updated requirement and state board.
- [x] Cross-platform impact check: **Web** layout unchanged; **Native KMP** first-use layout affected; **Expo** unchanged because this task targets `refactor/kmp`. The KMP-only layout difference is explicitly registered in the modified `Native workspace entry follows the Web access presentation` requirement; labels, validation, error/disabled states, and success navigation remain shared.
- [x] Scope out the existing-workspace list and add-another-workspace form; preserve their current presentation.
- [x] Update `docs/feature-map.md` with KMP first-use implementation evidence; Web and Expo remain unchanged under the registered presentation contract, so no alignment TODO is needed.

## 4. Build

- [x] User confirmed the Pen design by asking to complete this OpenSpec change.
- [x] Add Compose tests first for first-use structure, blank-name validation, focus, request error and retry, busy disabling, existing-workspace behavior, and server-confirmed navigation. Before implementation, `:shared:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.WorkspaceAccessScreenDeviceTest` failed only because the first-use screen exposed two “Create workspace” text nodes; the remaining five device tests passed. This isolated the intended layout defect.
- [x] Implement the approved layout in `WorkspaceAccessScreen`; preserve non-empty workspace behavior and all existing semantics.
- [x] Update the feature map in the same change.

## 5. Review

- [x] Design-scope review: first-use only, one task card, one heading, no instructional copy, no success stop page.
- [x] Engineering-scope review: KMP commonMain route identified; no service, API, auth, permission, data, or persistence change; preserve existing workspace-list branch.
- [x] Hallmark audit of the current prototype and Pen screenshots: **0 critical · 0 major · 0 minor**. Scope: information hierarchy, redundant copy, responsive composition, component nesting, state coverage, text/focus contrast. The CTA uses the existing accent/on-accent token pair at 18 px bold (3.68:1, above the large-text 3:1 threshold); small account text has at least 4.5:1; the focus outline exceeds 3:1. No edits were requested by the audit.
- [x] Independently review the final diff and audit the final KMP UI. Final scope: `WorkspaceAccessScreen` and its first-use states in `AccessScreens.kt`, Pen-approved `390 × 844`, `768 × 1024`, and `1440 × 900` Native screenshots, information hierarchy, responsive positions, text hierarchy, component nesting, state coverage, and focus/error contrast. Hallmark audit: **0 critical · 0 major · 0 minor**. No audit finding or re-review required. Final screenshots are `screenshots/native-390.png`, `screenshots/native-768.png`, and `screenshots/native-1440.png`.

## 6. Test and QA

- [x] Review Pen exports for `390 × 844`, `768 × 1024`, and `1440 × 900`; no clipped content or horizontal overflow in representative frames.
- [x] Open the HTML prototype in Codex In-app Browser at `http://127.0.0.1:4180/`; inspected `320`, `375`, `390`, `414`, `768`, and `1440` px widths. Measured `document.documentElement.scrollWidth` equal to viewport width at each size; card widths were 288/343/358/382/440/440 px with 16 px compact inset and no clipped text or horizontal overflow.
- [x] Browser interaction preview: submitted an empty name and confirmed inline validation with an enabled retry button; focused the field by keyboard-accessible control and confirmed accent focus outline; previewed busy state and confirmed field/action disabled with 「正在创建…」; previewed request failure and confirmed the typed name remains editable with retry feedback. Success reference points directly to the ledger.
- [x] Browser console warning/error log: empty. The prototype uses inline resources and made no external network requests. Browser viewport screenshots were inspected in the interactive session; saved visual evidence is the official Pen export set below.
- [x] `openspec validate kmp-workspace-create-auth-layout --strict` — valid; `git diff --check` passed for the tracked Pen change. New artifacts had no trailing whitespace (`rg -n '[[:blank:]]+$' openspec/changes/kmp-workspace-create-auth-layout --glob '!screenshots/**'`). Ran 2026-10-12 04:13 UTC at `HEAD df8672b29f572fecbbc87dc928a0a434fe61860e` against the same baseline.
- [x] Revalidated OpenSpec after terminology and cross-platform-difference edits at 2026-10-12 04:15 UTC; change remains valid at the same `HEAD`.
- [x] After Compose implementation, run OpenSpec validation, affected Compose tests, relevant Android/iOS Simulator/Wasm builds, and `git diff --check`.
- [x] Complete Native visual QA at `390`, `768`, and `1440` logical px. The six device tests cover empty/default, field focus and normalized submission, validation error, request error/retry, creating/disabled controls, and the preserved existing-workspace branch. Compare rendered account headers/cards/controls with Pen and save screenshots below.
- [x] Record actual commands, results, `HEAD`, baseline, execution time, and residual risk. PostgreSQL is not applicable because this change does not touch persistence. Desktop JVM is not configured; the production Wasm web target was built. Expo is unchanged; the required Expo prebuild was attempted but cannot run because `expo` is not installed in `mobile/` and no `mobile/ios` project exists. The KMP iOS Simulator framework and tests passed independently.

## 7. Release

- [x] Record delivery and rollback readiness: delta spec synced to the main `workspace/workspace-entry` spec, preserving the other access requirements; a post-sync comparison found no remaining delta. The completed change is archived at `openspec/changes/archive/2026-10-12-kmp-workspace-create-auth-layout/`. Implementation and validation evidence are local to this worktree; rollback is the single first-use layout change in `AccessScreens.kt`. No commit, push, PR, merge, or deployment was performed because this request did not authorize those external writes.

## 8. Reflect

- [x] Reusable lesson: assert card bounds at representative logical widths and inspect actual device screenshots; viewport-centering intuition missed 4 px errors at both desktop frames. Correcting from measured bounds aligned the Native screen with the approved Pen prototype.

## Current evidence

- Branch: `refactor/kmp`
- `HEAD` and comparison baseline: `df8672b29f572fecbbc87dc928a0a434fe61860e` (no commits made)
- Design tool: `@pen.dev/cli` 0.3.11, official headless Pen editor. `design/workspace-entry.pen` was edited and saved through that editor.
- Pen exports: `screenshots/KV5AK.png`, `screenshots/HetEf.png`, `screenshots/N1D59.png`, `screenshots/Uqok8.png`.
- Browser preview uses only local URL `http://127.0.0.1:4180/` (state panel at `?states`); the default route is left open for design review.
- Terminology review: 沿用「工作区」「收支账本」等现有术语；没有新增业务概念，因此未更新 `DOMAIN_GLOSSARY.md`。
- OpenSpec CLI: 1.7.0; Node.js: 24.16.0.
- Final validation commands in `compose/`:
  - `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.WorkspaceAccessScreenDeviceTest` at emulator overrides `390x844`, `768x1024`, and `1440x900`, density `160`: **6/6 passed at each width** after the final layout adjustment.
  - `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:allTests`: **passed** (Android Host, JS, Wasm, and iOS Simulator tests).
  - `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :androidApp:assembleDebug :shared:linkDebugFrameworkIosSimulatorArm64 :webApp:wasmJsBrowserDistribution`: **passed**. Existing Webpack warnings remain: a dynamic dependency warning and bundles over the default size budget (`webApp.js` 532 KiB; Wasm assets 6.58 MiB and 8.24 MiB). This change does not modify Web runtime code.
  - `openspec validate kmp-workspace-create-auth-layout --strict`: valid after implementation and documentation updates.
  - `git diff --check`: passed for the final diff.
- Native QA device: `codex_api36` Android API 36 emulator; screenshot captures use density 160 and logical viewports `390 × 844`, `768 × 1024`, and `1440 × 900`. Final card bounds: compact x=16/y=136/w=358; regular x=164/y=392/w=440; wide x=500/y=348/w=440. The emulator's active language was English; the production strings remain resource-localized. The screenshots document layout, not localization QA.
- Final review: Hallmark audit on the Native first-use UI and final screenshots returned **0 critical · 0 major · 0 minor**. The one QA geometry discrepancy (desktop card vertical offset) was corrected before the final audit and rerun.
- Web/Native/shared impact: Native KMP first-use layout changed; Web presentation and Expo app are unchanged. Shared user-visible workspace creation semantics and state callbacks are preserved. This Native presentation difference is registered in the updated workspace-entry spec. No persistence/API/permission behavior changed; no PostgreSQL matrix applies.
- Remaining risk: no KMP desktop JVM target is configured; Expo app prebuild was blocked by the missing Expo dependency, but this request only changes KMP Compose and its iOS Simulator framework build passed. Existing production bundle-size warnings are unrelated.
- Final device/build validation ran during `2026-10-12 05:15–05:21 UTC`; post-sync OpenSpec validation and diff/whitespace checks passed at `2026-10-12 05:24 UTC`, at `HEAD df8672b29f572fecbbc87dc928a0a434fe61860e` against the same baseline. The archive move completed at `2026-10-12 05:25 UTC`; post-archive `openspec validate workspace/workspace-entry --type spec --strict`, `git diff --check`, and trailing-whitespace checks passed at `2026-10-12 05:25 UTC`. The HTML file remains a standalone prototype, not a production Web page.
