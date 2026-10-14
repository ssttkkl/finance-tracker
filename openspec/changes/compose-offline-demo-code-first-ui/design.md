## Context

See `proposal.md` for motivation. Compose `commonMain` already owns the shared Android, iOS, and Web screens, while `FinanceTrackerGraph` currently creates a Ktor API client and the platform token store at application start. Cash import currently opens the system file picker. The change therefore needs an explicit graph boundary and target-specific build entry points; a UI-only flag would not keep credentials and networking out of demo mode.

The root workflow and `docs/ui-design-rules.md` currently make Pen and standalone HTML prototypes prerequisites. The selected replacement is the rendered Compose implementation, with OpenSpec retaining information architecture and acceptance and Hallmark retaining the design direction and final audit. Existing Pen files remain historical assets.

Compose Resources is already configured for shared strings and fonts. This change also stores the shared navigation, selection, import, access-screen, and secondary-page icons as local Material icon Android Vector XML drawables in `commonMain/composeResources/drawable`; generated `Res.drawable` accessors and `painterResource` render them with Finance semantic-color tint. This covers the app rail and menu, page back/close, dropdown, file/upload, success, and relation-direction icons without font glyphs or a new icon dependency. Compose Multiplatform Resources does not load SVG drawables on Android, so the shared targets use vector XML Material icons instead of SVG.

**Hallmark direction retained for this UI change:** preserve the current Finance token palette and component contracts, keep the existing staged import information hierarchy, and make the bundled statement the only file affordance in demo mode. Do not add a second import screen, new color system, decorative onboarding copy, or demo-only route. Self-review favors a clear `内置演示账单` heading, one `使用内置账单` action, an explicit selected state, and no upload-format instructions when platform file access is unavailable. The Hallmark design action is not exposed as a callable runtime tool in this environment; this direction is a manual project-token-based substitute, not a claimed Hallmark run.

## Goals / Non-Goals

**Goals:**

- Build a demo graph that has no reference to `FinanceApiClient`, `TokenStore`, Ktor repositories, persistent storage, or platform file access.
- Select demo mode at a development/QA build entry point for each supported target. Release entry points must pass production mode unconditionally.
- Reuse the same Compose routes, screens, labels, semantic IDs, responsive rules, and supported actions as the production graph.
- Keep seeded data and all demo mutations inside repository instances owned by one app process.
- Keep Git token JSON and generated Compose token code as the active design-token chain.

**Non-Goals:**

- Changing production authentication, API contracts, or persistence behavior.
- Adding an in-app mode/scenario picker, persistent demo fixtures, arbitrary failure controls, or a visual redesign of the Compose pages.
- Deleting historical `.pen` files or rewriting archived OpenSpec evidence.
- Replacing the separate backend-connected Compose preview command.

## User Task and Information Architecture

The developer/QA user opens a fixed, signed-in demo workspace, moves through the existing navigation destinations, exercises their ordinary actions, and reviews the actual Compose rendering. Keep the current page hierarchy and primary destination set: cash ledger, built-in statement import, cash categories, investment holdings, investment events, workspace management, and synthetic invitation acceptance. The import keeps the existing sequence of choose, scan, map the account, preview, review relations, and commit.

Demo import removes the production file picker, file-format/size guidance, and actual-file count. It presents one built-in statement action and a selected state in the same import screen. The change adds no scenario picker, second import screen, or duplicate normal-state help text.

## Cross-platform Impact Check

| Layer | Impact | Reason |
| --- | --- | --- |
| Web Compose | Affected | Adds the opt-in offline distribution, same-app route coverage, no-proxy server behavior, and browser evidence for the shared screens. |
| Native Compose (Android/iOS) | Affected | Adds debug-only demo selectors at both platform entry points and verifies Release remains on the production graph. |
| Shared Compose | Affected | Adds the in-memory graph and repositories, demo import injection, shared validation/filter behavior, and the demo-specific import affordance. |

All three targets render the same `commonMain` screens and domain contracts; the only registered platform difference is how a developer selects the demo build/launch.

## Decisions

### 1. Select the demo graph at the platform entry point

`App` receives an explicit mode, and the graph factory branches before constructing production-only dependencies. Production mode creates the platform token store and Ktor client; demo mode creates only in-memory session and finance repositories. `FinanceTrackerGraph.apiClient` becomes optional so application disposal closes only a client that exists. The demo graph does not contact an API server; Web UI may still request static resources such as fonts from their configured CDN.

The shared module uses the pinned BuildKonfig Gradle plugin to generate `ComposeBuildConfig` from the `FT_COMPOSE_DEMO_MODE` and `FT_API_ORIGIN` build environment variables. Web, Android, and iOS read these shared values. When `FT_COMPOSE_DEMO_MODE` is unset or false, demo mode is disabled. Android passes it to `App` only when the Android-generated `BuildConfig.DEBUG` is true. The Swift iOS entry reads it only under `#if DEBUG`. The production Web builder and backend-connected local preview explicitly set the mode to `false`; the offline Web launcher injects `true` and clears `FT_API_ORIGIN` in its Gradle build process. No URL parameter, persisted preference, JavaScript global, or in-app control selects demo mode. BuildKonfig generates source only; it adds no runtime library dependency.

`FT_API_ORIGIN` is a public API endpoint, not a credential. Android, iOS, JS, and Wasm obtain it from the generated shared value; platform `BuildConfig`, iOS `Info.plist`, and the runtime `window.FT_API_ORIGIN` setting no longer supply it. An unset value remains empty on Android and iOS. Web uses the current page origin when its compiled value is empty, preserving the backend-connected local preview proxy. The production Compose Web bootstrap validates the configured HTTPS origin and keeps its existing `http://127.0.0.1:8000` fallback before invoking Gradle, so the resulting value is also compiled by BuildKonfig.

**Alternatives considered:** A URL/query/local-storage switch is easy to use but leaves a runtime demo flag in the production artifact. A remote mock API or Ktor mock engine would still instantiate the production client and transport boundary. A separate UI implementation would not review the real shared screens.

### 2. Keep mock repositories behind the existing domain interfaces

Implement a seeded `DemoSessionRepository` and `DemoFinanceRepository` in `commonMain`, composed only by `createDemoFinanceTrackerGraph`. The repositories own mutable lists and revisions in memory; each graph creation starts from deterministic seed data. Cash create/edit/classify/delete, current ledger filters (including exact signed amount ranges), categories, session/workspace actions, and synthetic import update that state. Invalid amount filters use the existing recoverable ledger error state. Investment screens use deterministic read data and preserve the current read/refresh UI contract without making external quote requests. Repository operations that have no writable UI path return consistent domain results without inventing additional flows.

Use an authenticated fixed demo session on launch, with sign-out/login/register and workspace/invitation methods handled locally so access screens remain reachable. A normal demo workspace and ordinary seed ledger are the only runtime seed profile. Empty/error/loading states remain the responsibility of the same screens and can be supplied by repository-level tests or targeted QA fixtures; no fixture picker is added.

**Alternatives considered:** Duplicating Compose pages would drift from production. Persisting fake changes would make restart behavior ambiguous and could mix demo data with real data. A general scenario framework would add a product-like control surface beyond the confirmed scope.

### 3. Inject the synthetic statement into the existing import screen

The import screen accepts an optional built-in `FileSource`. Production keeps the current platform picker; the demo graph supplies a deterministic in-memory statement and the screen offers that sample through the same selection stage. The demo repository implements recognition, scan, mapping, preview, and commit locally and appends committed rows to the current demo session.

**Alternatives considered:** Bundling the statement as a platform file would require filesystem/resource handling on every target. Bypassing the import screen would not exercise the actual import journey.

### 4. Make Compose code and Git tokens the maintained UI sources

Revise `AGENTS.md`, `docs/ui-design-rules.md`, active specifications, Compose setup docs, design workspace docs, and the component registry to remove Pen/HTML prototype prerequisites, Pen-to-code mapping, and pre-implementation design approval. Remove the active Pen CLI and replace Pen-based registry inputs with Git token files and Compose implementations. Retain IA, acceptance, redundant-information removal, Hallmark direction/self-review for major UI work, responsive constraints, implementation review, screenshots, and final Hallmark audit. A major UI remains pending until the user confirms the implementation shown in demo mode.

Keep `openspec/specs/shared/design-system/tokens/*.json` as token input and generated Compose Kotlin as its compiled output. Stop generating or validating the Pen variable mirror as a current artifact. Preserve the existing mirror file and `.pen` assets as historical references. Update the feature map to describe implementation and screenshot evidence, not Pen approval.

**Alternatives considered:** Keeping Pen as an optional parallel source would leave unclear which source resolves a mismatch. Deleting historical files would discard audit context without helping the new workflow.

### 5. Keep shared UI icons local and resource-backed

Reuse the existing Compose Resources dependency and generated accessor package for Material icon vector drawables. Keep the icon artwork in common resources and tint it at the call site from the same semantic roles used by the adjacent component. Replace character, custom `ImageVector`, and Canvas-rendered icons in `AppShell`, `FeatureComponents`, `CashImportScreen`, `SecondaryPageContainer`, and `AccessScreens` so Android, iOS, and Web render the same local paths. Do not add a Material Icons runtime dependency or fetch icon fonts at runtime.

**Alternatives considered:** SVG `painterResource` is not available on Android in the current Compose Multiplatform Resources contract. Unicode icon characters depend on font coverage and can render as missing-glyph boxes; Canvas paths duplicate artwork in Kotlin. Material icon XML vectors are compiled as shared Compose Resources and support all three targets.

## Risks / Trade-offs

- [The domain repository surface is broad and a missing operation can leave a destination inert] → Implement every interface method, trace each Compose destination/action to its repository call, and review the route coverage against `App.kt` and the feature map.
- [A demo build could accidentally reach production dependencies] → Keep mode selection at each platform entry, branch before token-store/client construction, make release selectors constant-false, and add source/build-configuration checks.
- [Synthetic import behavior may drift from production import stages] → Reuse the shared screen and domain models, keep stage transitions and result shapes aligned, and include a complete sample journey in the offline demo review.
- [Generated token checks may still assume Pen] → Update token generation and CI checks in the same change; keep Git JSON as the canonical input and Compose output checked in.
- [Removing pre-implementation review can defer discovery of visual issues] → Retain information architecture and acceptance up front, require demo review for major changes, keep screenshots as evidence, and leave completion pending explicit user confirmation.

## Migration Plan

1. Add and validate the OpenSpec deltas before changing implementation.
2. Add repository and entry-point tests before switching the graph; preserve the existing backend-connected preview command.
3. Update the workflow and active specs in the same change as demo support, leaving archived changes and `.pen` assets intact.
4. Validate OpenSpec, run the affected Compose and workflow checks, and prepare the offline Web demo plus Android/iOS launch instructions.
5. Rollback by reverting this change: the production graph and API routes remain unchanged, and no schema or persisted data migration is introduced.

## Open Questions

None. The user confirmed the platform scope, build boundary, fixed seed, in-memory mutation behavior, import sample, and post-implementation review point.
