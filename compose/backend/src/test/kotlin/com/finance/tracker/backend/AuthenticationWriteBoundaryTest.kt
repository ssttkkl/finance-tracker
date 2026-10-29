package com.finance.tracker.backend

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class AuthenticationWriteBoundaryTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_AUTH_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun sessionCreationFailureRollsBackTheRegistrationAndMembership() {
        val database = database()
        val service = AuthenticationService(database, newToken = { error("injected token generation failure") })
        assertFailsWith<IllegalStateException> { service.register("rollback-auth@example.invalid", "a secure password") }
        database.read { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM users WHERE email='rollback-auth@example.invalid'").use {
                it.next(); assertEquals(0, it.getInt(1))
            }
        } }
    }

    @Test fun malformedUnicodeNeverBecomesAReplacementCharacterCredential() {
        val database = database()
        try {
            assertFailsWith<java.nio.charset.CharacterCodingException> {
                AuthenticationService(database).register("unicode-invalid@example.invalid", "\ud800".repeat(12))
            }
            database.read { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM users WHERE email='unicode-invalid@example.invalid'").use {
                    it.next(); assertEquals(0, it.getInt(1))
                }
            } }
        } finally { database.write { connection -> connection.createStatement().use {
            it.executeUpdate("DELETE FROM users WHERE email='unicode-invalid@example.invalid'")
        } } }
    }

    @Test fun wrongPasswordDoesNotAcquireTheSqliteWriteLock() {
        val database = database()
        // On PostgreSQL this also proves that a separate transaction may stay open during verification.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { database.write { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val login = executor.submit<Boolean> {
                try { AuthenticationService(database).login("admin@example.invalid", "wrong password"); false }
                catch (_: AccessFailure) { true }
            }
            assertTrue(login.get(3, TimeUnit.SECONDS), "bad credentials should be checked without waiting for a write lock")
            release.countDown(); writer.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun concurrentRegistrationHasOneUserAndOneSession() {
        val database = database()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (0..1).map { executor.submit<String> {
                start.await()
                try { AuthenticationService(database).register("racing-auth@example.invalid", "a secure password"); "registered" }
                catch (_: CredentialFailure) { "rejected" }
            } }
            start.countDown()
            assertEquals(listOf("registered", "rejected"), attempts.map { it.get(15, TimeUnit.SECONDS) }.sorted())
            database.read { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id=s.user_id WHERE u.email='racing-auth@example.invalid'").use {
                    it.next(); assertEquals(1, it.getInt(1))
                }
            } }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS)
            database.write { connection -> connection.createStatement().use {
                it.executeUpdate("DELETE FROM users WHERE email='racing-auth@example.invalid'")
            } }
        }
    }
}
