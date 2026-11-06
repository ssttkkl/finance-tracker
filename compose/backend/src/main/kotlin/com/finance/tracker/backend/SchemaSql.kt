package com.finance.tracker.backend

import java.io.InputStream

internal object SchemaSql {
    fun sqlite(): List<String> = load("sqlite-baseline.sql")

    fun postgresql(): List<String> = load("postgresql-baseline.sql")

    private fun load(name: String): List<String> {
        val text = resource(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val withoutComments = text.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        return withoutComments.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { "$it;" }
    }

    private fun resource(name: String): InputStream =
        SchemaSql::class.java.getResourceAsStream("/schema/$name")
            ?: throw StorageFailure("storage.schema")
}
