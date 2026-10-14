## Context

See [proposal.md](./proposal.md) for motivation and scope. `compose/gradle/gradle-daemon-jvm.properties` requires Azul Java 21, while Render may not provide a Java runtime to start the Gradle Wrapper.

## Goals / Non-Goals

**Goals:** Render can build the Compose Wasm distribution from a clean Linux environment without a preinstalled Java runtime, and can reuse a verified JDK cache on later builds.

**Non-Goals:** Changing the web application, the existing `render.yaml` service, Gradle toolchain criteria, or cloud service settings.

## Decisions

- Query Foojay Disco for the latest available GA Azul Zulu JDK 21 package matching Linux, glibc, and the host architecture. This follows the user's choice to receive current GA patches; a fixed package version would require manual security updates.
- Use Python 3's standard library to query and validate JSON, and existing `curl`, `tar`, and SHA-256 utilities for download and installation. This avoids adding a package-manager dependency to the Render build.
- Verify the downloaded archive against Foojay's SHA-256 before extraction. Reject archive paths or links that escape the extraction directory.
- Cache by Foojay package ID below `$XDG_CACHE_HOME/finance-tracker/jdks`; fall back to `$HOME/.cache` if Render does not set `XDG_CACHE_HOME`. A new upstream package ID creates a separate cache entry, leaving other entries available for build-cache eviction.
- Configure `JAVA_HOME` and `PATH` in the same process that starts Gradle, so both the Wrapper and Gradle daemon can use the Azul JDK selected for the build.
- For Render Static Site settings, use root `compose`, build command `./build-wasm-web.sh`, and publish directory `webApp/build/dist/wasmJs/productionExecutable`. Keep the existing root `render.yaml` configuration unchanged because it targets `web/`.

## Risks / Trade-offs

- Foojay or Azul availability blocks a cache miss → Fail with a bounded network timeout and a clear step-specific message; a prior cache entry can be reused when package metadata is available.
- Latest GA package metadata can change between builds → Cache by immutable package ID and verify each newly downloaded archive before installation.
- The script assumes Render's Linux build image provides Python 3, curl, tar, and SHA-256 tooling → Check these commands before network access and name any missing command in the error.
- Only Linux/glibc x86_64 and aarch64 are supported → Reject unsupported platforms before downloading.

## Migration Plan

Set the Render Static Site root directory to `compose`, use `./build-wasm-web.sh` as its build command, and set the publish directory as listed above. Rollback consists of restoring the previous Render build command and removing this script and its README section; no application data or cloud configuration is changed by the repository script itself.
