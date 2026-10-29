plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":business-contracts"))
    implementation(project(":business-domain"))
    implementation("io.ktor:ktor-server-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-status-pages:${libs.versions.ktor.get()}")
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("org.xerial:sqlite-jdbc:${libs.versions.sqliteJdbc.get()}")
    implementation("org.postgresql:postgresql:${libs.versions.postgresJdbc.get()}")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation(libs.kotlin.testJunit)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
}

application { mainClass.set("com.finance.tracker.backend.MainKt") }
tasks.test {
    maxParallelForks = 1
    // Live isolated database state is an explicit test input; never reuse a prior database's result.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
