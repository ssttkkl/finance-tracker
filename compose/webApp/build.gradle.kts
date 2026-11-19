import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
        }
    }
}

val commitPattern = Regex("[a-f0-9]{40}")
val renderWebRelease = providers.environmentVariable("RENDER_GIT_COMMIT")
    .filter { it.matches(commitPattern) }
val gitWebRelease = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("sh", "-c", "git rev-parse --verify HEAD 2>/dev/null")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().takeIf(commitPattern::matches) ?: "unknown" }
val webRelease = renderWebRelease.orElse(gitWebRelease)

val webSentryDsn = providers.environmentVariable("FT_WEB_SENTRY_DSN").orElse("").map { value ->
    require(value.isEmpty() || value.matches(Regex(
        "(?:https://[A-Za-z0-9]+@[A-Za-z0-9.-]+(?::[0-9]+)?|http://[A-Za-z0-9]+@(?:localhost|127[.]0[.]0[.]1):[0-9]+)/(?:[A-Za-z0-9_-]+/)*[0-9]+"
    ))) { "Invalid FT_WEB_SENTRY_DSN" }
    value
}
val sentryBrowserVersion = "11.14.0"
val sentryBrowserChecksum = "5fdc26d11755569815db45a28eeb2a31849922e71f91192fde7d063c41240ed4"
val sentryBrowserResources = layout.buildDirectory.dir("generated/sentryBrowser")
val sentryBrowserLicense = layout.projectDirectory.file("third-party/sentry-browser-LICENSE.txt")
val prepareWebSentry = tasks.register("prepareWebSentry") {
    val enabled = webSentryDsn.map { it.isNotEmpty() }
    inputs.property("enabled", enabled)
    inputs.property("version", sentryBrowserVersion)
    inputs.property("sha256", sentryBrowserChecksum)
    inputs.file(sentryBrowserLicense)
    outputs.dir(sentryBrowserResources)
    doLast {
        val output = sentryBrowserResources.get().file("sentry-browser.min.js").asFile
        val license = output.resolveSibling("sentry-browser-LICENSE.txt")
        if (!enabled.get()) {
            output.delete()
            license.delete()
        } else {
            val connection = URI("https://browser.sentry-cdn.com/$sentryBrowserVersion/bundle.min.js").toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val bytes = connection.getInputStream().use { it.readNBytes(500_000) }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == sentryBrowserChecksum) { "Sentry Browser SDK checksum mismatch" }
            output.parentFile.mkdirs()
            output.writeBytes(bytes)
            sentryBrowserLicense.asFile.copyTo(license, overwrite = true)
        }
    }
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    from(prepareWebSentry)
    inputs.property("webSentryDsn", webSentryDsn)
    inputs.property("webRelease", webRelease)
    filesMatching("startup.js") {
        val release = webRelease.get()
        val dsn = webSentryDsn.get()
        filter { line: String -> line.replace("__FT_WEB_RELEASE__", release).replace("__FT_WEB_SENTRY_DSN__", dsn) }
    }
}
