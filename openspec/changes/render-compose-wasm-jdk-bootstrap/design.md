## Context

See [proposal.md](./proposal.md) for motivation and scope. `compose/gradle/gradle-daemon-jvm.properties` requires Azul Java 21, while Render may not provide a Java runtime to start the Gradle Wrapper.

## Goals / Non-Goals

**Goals:** Render can build the Compose Wasm distribution from a clean Linux environment without a preinstalled Java runtime, reuse a verified JDK cache on later builds, and inject an optional backend API origin into the generated web config.

**Non-Goals:** Changing the API protocol or backend behavior, the existing `render.yaml` service, Native API configuration, Gradle toolchain criteria, or cloud service settings.

## Decisions

- Query Foojay Disco for the latest available GA Azul Zulu JDK 21 package matching Linux, glibc, and the host architecture. This follows the user's choice to receive current GA patches; a fixed package version would require manual security updates.
- Keep `build-wasm-web.sh` as Render’s stable entry point and delegate the full bootstrap flow to `build-wasm-web.py`. Python 3’s standard library handles Foojay JSON, HTTPS requests, SHA-256 calculation, cache management, and Gradle process setup; the existing `tar` utility extracts the verified archive. No package-manager dependency is added.
- Accept the SHA-256 value from Foojay’s package detail when present, or read the HTTPS `checksum_uri` when the API supplies a checksum file instead. Verify the downloaded archive against that SHA-256 before extraction. Reject archive paths or links that escape the extraction directory.
- Cache by Foojay package ID below `$XDG_CACHE_HOME/finance-tracker/jdks`; fall back to `$HOME/.cache` if Render does not set `XDG_CACHE_HOME`. A new upstream package ID creates a separate cache entry, leaving other entries available for build-cache eviction.
- Configure `JAVA_HOME` and `PATH` in the same process that starts Gradle, so both the Wrapper and Gradle daemon can use the Azul JDK selected for the build.
- After a successful Wasm distribution, read optional `FT_API_ORIGIN` from the build process environment and write it into the generated `config.js` using JSON string encoding. Empty or unset values explicitly restore the source default, so a previous cached distribution cannot retain a stale API origin and local builds remain valid.
- When supplied, validate `FT_API_ORIGIN` as an HTTPS origin only: no path, query, fragment, credentials, or invalid port. This matches the API client's production origin contract and keeps credentials out of the static bundle.
- For Render Static Site settings, use root `compose`, build command `./build-wasm-web.sh`, and publish directory `webApp/build/dist/wasmJs/productionExecutable`. Set `FT_API_ORIGIN` in that service's Build Environment to the backend's public HTTPS origin. Set backend `FT_WEB_ORIGIN` to the Compose site's HTTPS origin for CORS. Keep the existing root `render.yaml` configuration unchanged because it targets `web/`.

## Risks / Trade-offs

- Foojay or Azul availability blocks a cache miss → Fail with bounded retries and a step-specific diagnostic naming the missing metadata field or failed network step; a prior cache entry can be reused only after the current package metadata and checksum are available.
- `FT_API_ORIGIN` is embedded in public static JavaScript → Accept only a public HTTPS origin without credentials or paths; do not treat it as a secret. If unset, write the existing empty default and allow the build to complete.
- Latest GA package metadata can change between builds → Cache by immutable package ID and verify each newly downloaded archive before installation.
- The script assumes Render's Linux build image provides Python 3 and `tar` → Check for Python 3 before network access and name extraction failures clearly.
- Only Linux/glibc x86_64 and aarch64 are supported → Reject unsupported platforms before downloading.

## Migration Plan

Set the Render Static Site root directory to `compose`, use `./build-wasm-web.sh` as its build command, set the publish directory as listed above, and add `FT_API_ORIGIN` to the service's Build Environment. Configure backend `FT_WEB_ORIGIN` with the deployed Compose site origin. Rollback can clear `FT_API_ORIGIN` to regenerate the empty default config, then restore the previous build command and remove the bootstrap script and README section; no application data is changed.

## Render 故障修正记录

Render 曾报告 `Foojay 返回了不完整的包信息`。无法从本机重放 Render 收到的原始 Foojay 响应（本机 API 请求返回 HTTP 403），因此不把单一缺失字段当作已证实根因。原实现通过制表符拼接 JSON 派生字段，再由 Bash 拆分；修正版取消这段脆弱的 shell 数据交接，并在 SHA-256 未内联返回时读取 `checksum_uri`。对仍然不完整的响应，错误会标明具体字段或 Foojay 步骤。

缓存策略沿用原决定：仅当本次 Foojay 元数据和校验和可用时才可复用匹配缓存；不在元数据不可用时使用旧 JDK。Render 设置、发布目录、Gradle 任务和 Java 21 要求均不变。
