package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AuthenticationContractTest {
    @Test fun passwordHashesRemainCompatibleWithThePythonOracle() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_AUTH_CONTRACT_DIR")))
        val fixture = Json.parseToJsonElement(root.resolve("password.json").readText()).jsonObject
        val passwords = Passwords()
        val password = fixture.getValue("password").jsonPrimitive.content
        assertTrue(passwords.verify(fixture.getValue("encoded").jsonPrimitive.content, password))
        assertFalse(passwords.verify(fixture.getValue("encoded").jsonPrimitive.content, "wrong"))
        for (invalid in listOf("", "not-a-hash", "\$argon2id\$v=19\$m=0,t=3,p=4\$bad\$bad"))
            assertFalse(passwords.verify(invalid, password))
        val first = passwords.hash(password)
        val second = passwords.hash(password)
        assertNotEquals(first, second)
        assertTrue(passwords.verify(first, password))
        assertTrue(first.startsWith("\$argon2id\$v=19\$m=65536,t=3,p=4\$"))
        assertFailsWith<java.nio.charset.CharacterCodingException> { passwords.hash("\ud800".repeat(12)) }
        assertFailsWith<java.nio.charset.CharacterCodingException> { passwords.verify(first, "\udc00".repeat(12)) }
        java.nio.file.Files.writeString(root.resolve("kotlin-password.txt"), first)
    }

    @Test fun authenticationMatchesPythonIncludingDatabaseEffectsAndStrictCredentialTypes() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_AUTH_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        database.validateSchema()
        val sequence = AtomicInteger()
        val auth = AuthenticationService(database, now = { Instant.parse("2026-10-28T14:00:00Z") },
            newToken = { "anonymous-auth-token-${sequence.getAndIncrement()}" })
        application { financeModule(database, clock = { Instant.parse("2026-10-28T14:00:00Z") }, authentication = auth) }
        try {
            val initial = effects(database)
            for (path in listOf("register", "login")) for (field in listOf("email", "password")) {
                for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(1234567890123456L),
                    JsonArray(listOf(JsonPrimitive("not-a-string@example.invalid"))),
                    JsonObject(mapOf("key" to JsonPrimitive("not-a-string@example.invalid"))))) {
                    val body = buildJsonObject {
                        put("email", "strict@example.invalid"); put("password", "a secure password"); put(field, value)
                    }
                    val response = client.post("/api/v1/auth/$path") { contentType(ContentType.Application.Json); setBody(body.toString()) }
                    assertEquals(400, response.status.value, "$path $field $value")
                    assertEquals(buildJsonObject { putJsonObject("error") {
                        put("code", "invalid_credentials"); put("message", "请求未获授权或无效。")
                    } }, Json.parseToJsonElement(response.bodyAsText()))
                    assertEquals(initial, effects(database))
                }
            }
            for (value in Json.parseToJsonElement(root.resolve("auth.json").readText()).jsonArray) {
                val case = value.jsonObject
                val response = client.request(case.getValue("path").jsonPrimitive.content) {
                    method = HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                    case.getValue("headers").jsonObject.forEach { (name, value) -> header(name, value.jsonPrimitive.content) }
                    if (case["request"] != JsonNull) { contentType(ContentType.Application.Json); setBody(case.getValue("request").toString()) }
                }
                val context = case.getValue("path").toString() + " " + case.getValue("request")
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                assertEquals(case["body"], Json.parseToJsonElement(response.bodyAsText()), context)
                assertEquals(case["effects"], effects(database), context)
                assertFalse(response.headers.contains(HttpHeaders.SetCookie))
            }
            val before = effects(database)
            assertFailsWith<IllegalStateException> { database.write { connection ->
                connection.createStatement().use { it.executeUpdate("DELETE FROM user_sessions") }
                error("injected failure before commit")
            } }
            assertEquals(before, effects(database), "failed write must roll back every change")
            database.read { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT expires_at FROM user_sessions WHERE token_digest='${tokenDigest("anonymous-auth-token-2")}'").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getString(1).startsWith("2026-11-27"))
                }
            } }
        } finally {
            database.write { connection ->
                connection.prepareStatement("DELETE FROM user_sessions WHERE token_digest=?").use { statement ->
                    for (index in 0..99) { statement.setString(1, tokenDigest("anonymous-auth-token-$index")); statement.executeUpdate() }
                }
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM users WHERE email IN ('new@example.invalid','unicode@example.invalid','admin@ssttkkl.fun')")
                }
                // Restore the one expired seed removed by the logout oracle, for repeatability.
                connection.prepareStatement("INSERT INTO user_sessions(id,user_id,token_digest,active_workspace_id,expires_at,created_at) VALUES(?,?,?,?,?,?)").use { statement ->
                    statement.setString(1, "session-expired"); statement.setString(2, "admin")
                    statement.setString(3, tokenDigest("synthetic-expired")); statement.setString(4, "fixture-a")
                    statement.setObject(5, if (database.sqlite) "2020-01-01 00:00:00.000000" else java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                    statement.setObject(6, if (database.sqlite) "2026-01-01 00:00:00.000000" else java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                    connection.prepareStatement("SELECT id FROM user_sessions WHERE id=?").use { check ->
                        check.setString(1, "session-expired")
                        if (!check.executeQuery().use { it.next() }) statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun effects(database: Database): JsonObject = database.read { connection ->
        fun strings(sql: String) = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) }.sorted() }
        }
        buildJsonObject {
            put("users", JsonArray(strings("SELECT email FROM users").map(::JsonPrimitive)))
            put("sessions", JsonArray(strings("SELECT token_digest FROM user_sessions").map(::JsonPrimitive)))
            put("memberships", connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM workspace_memberships").use { it.next(); it.getInt(1) }
            })
        }
    }
}
