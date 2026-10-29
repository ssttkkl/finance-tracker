plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(21) }

val jooqVersion = "3.21.9"
val codegen by sourceSets.creating
val codegenTest by sourceSets.creating
kotlin.sourceSets.main { kotlin.srcDir("src/generated/kotlin") }

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
    implementation("org.jooq:jooq:$jooqVersion")
    add(codegen.implementationConfigurationName, "org.jooq:jooq-codegen:$jooqVersion")
    add(codegen.runtimeOnlyConfigurationName, "org.postgresql:postgresql:${libs.versions.postgresJdbc.get()}")
    add(codegenTest.implementationConfigurationName, libs.kotlin.testJunit)
    testImplementation(libs.kotlin.testJunit)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
}

tasks.register<Test>("codegenTest") {
    group = "verification"
    description = "Verify code generation rejects unsafe database URLs without exposing their contents."
    dependsOn(codegen.classesTaskName, codegenTest.classesTaskName)
    testClassesDirs = codegenTest.output.classesDirs
    classpath = codegenTest.runtimeClasspath + codegen.runtimeClasspath
    systemProperty("jooq.codegen.classpath", codegen.runtimeClasspath.asPath)
}
tasks.named("check") { dependsOn("codegenTest") }

tasks.register<JavaExec>("generateJooq") {
    group = "code generation"
    description = "Generate database types from an explicitly prepared PostgreSQL 18 test schema."
    classpath = codegen.runtimeClasspath
    mainClass.set("com.finance.tracker.backend.codegen.GenerateJooq")
    args(providers.gradleProperty("jooqOutput").getOrElse(layout.projectDirectory.dir("src/generated/kotlin").asFile.path))
    inputs.files(fileTree(rootProject.projectDir.parentFile.resolve("migrations")), "src/codegen/java")
    // The external schema is an explicit input and must be verified on every invocation.
    outputs.upToDateWhen { false }
}

application { mainClass.set("com.finance.tracker.backend.MainKt") }
tasks.test {
    maxParallelForks = 1
    if (providers.gradleProperty("backendFunctionalOnly").map(String::toBoolean).getOrElse(false)) {
        filter.excludeTestsMatching("*CategoryDeletionPerformanceTest")
        filter.excludeTestsMatching("*CashClassificationPerformanceTest")
    }
    // Live isolated database state is an explicit test input; never reuse a prior database's result.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
