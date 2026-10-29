package com.finance.tracker.backend

import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSqlTransactionMigrationTest {
    private fun database():Database {
        val config=Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),"database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,System.getenv("FT_KOTLIN_TEST_DB_USER"),System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun end(c:Connection,sqlite:Boolean,commit:Boolean) {
        if(sqlite) c.createStatement().use {it.execute(if(commit) "COMMIT" else "ROLLBACK");it.execute("BEGIN IMMEDIATE")}
        else if(commit) c.commit() else c.rollback()
    }
    private fun incomplete(action:()->Unit) {assertEquals("projection.incomplete",assertFailsWith<CashProjectionFailure>(block=action).code)}
    @Test fun proofIsBoundToOneRepositoryAndCannotBeMutatedOrSubstituted() {
        val db=database();db.write {c ->val context=CashSqlContext(c,"fixture-a",db.sqlite);val repository=CashProjectionSourceRepository(context);val proof=repository.readValidatedSources()
            assertEquals(repository.sourceDigest(),proof.digest);proof.requireFor(repository,proof.digest)
            incomplete {proof.requireFor(CashProjectionSourceRepository(context),proof.digest)}
            incomplete {proof.requireFor(repository,"different")}
            assertFailsWith<UnsupportedOperationException> {(proof.facts as MutableList<*>).clear()}
            assertFailsWith<UnsupportedOperationException> {(proof.relations as MutableList<*>).clear()}
        }
    }
    @Test fun commitAndRollbackInvalidateOldProofEvenWhenConnectionIsReused() {
        for(commit in listOf(true,false)) {val db=database();db.write {c ->
            val context=CashSqlContext(c,"fixture-a",db.sqlite);val repository=CashProjectionSourceRepository(context);val proof=repository.readValidatedSources()
            end(c,db.sqlite,commit);assertFalse(c.isClosed)
            incomplete {proof.requireFor(repository,proof.digest)};incomplete {repository.readSources()}
            val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val next=fresh.readValidatedSources();next.requireFor(fresh,next.digest)
        }}
    }
    @Test fun savepointRollbackKeepsOuterProofAndContextValid() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            c.createStatement().use {it.execute("SAVEPOINT cash_proof");it.execute("ROLLBACK TO SAVEPOINT cash_proof");it.execute("RELEASE SAVEPOINT cash_proof")}
            proof.requireFor(repository,proof.digest);assertEquals(proof.digest,repository.sourceDigest())
            if(!db.sqlite) {val point=c.setSavepoint();c.rollback(point);c.releaseSavepoint(point);proof.requireFor(repository,proof.digest)}
        }
    }
    @Test fun closedConnectionAndCompletedDatabaseScopeRejectProof() {
        val db=database();lateinit var repository:CashProjectionSourceRepository;lateinit var proof:CashValidatedProjectionSources
        db.read {c ->repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));proof=repository.readValidatedSources()}
        incomplete {proof.requireFor(repository,proof.digest)};incomplete {repository.readSources()}
    }
    @Test fun jdbcAutoCommitTransitionAndSqlEndInvalidateOldContext() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            c.createStatement().use {it.execute("END");if(db.sqlite) it.execute("BEGIN IMMEDIATE")}
            incomplete {proof.requireFor(repository,proof.digest)}
            if(!db.sqlite) {val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",false));val next=fresh.readValidatedSources();c.autoCommit=true;c.autoCommit=false;incomplete {next.requireFor(fresh,next.digest)}}
        }
    }

    @Test fun standardJdbcOwnershipAndUnwrapNeverReturnTheRawConnection() {
        val paths:List<(Connection)->Connection> = listOf(
            {it.unwrap(Connection::class.java)},
            {it.metaData.connection},
            {it.metaData.unwrap(java.sql.DatabaseMetaData::class.java).connection},
            {c ->c.createStatement().use {it.unwrap(java.sql.Statement::class.java).connection}},
            {c ->c.createStatement().use {s ->s.executeQuery("SELECT 1").use {it.statement.connection}}},
            {c ->c.createStatement().use {s ->s.executeQuery("SELECT 1").use {it.unwrap(java.sql.ResultSet::class.java).statement.connection}}}
        )
        for(path in paths) {val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            val alias=path(c);assertSame(c,alias);end(alias,db.sqlite,true);incomplete {proof.requireFor(repository,proof.digest)}
        }}
    }
    @Test fun transactionControlsAreRejectedFromBothStatementAndPreparedBatches() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            c.createStatement().use {s ->for(sql in listOf("COMMIT","BEGIN","SELECT 1; COMMIT","ROLLBACK","END")) assertFailsWith<java.sql.SQLFeatureNotSupportedException> {s.addBatch(sql)}}
            c.prepareStatement("COMMIT").use {s ->assertFailsWith<java.sql.SQLFeatureNotSupportedException> {s.addBatch()}}
            c.prepareStatement("UPDATE cash_transactions SET note=note WHERE workspace_id=?").use {it.setString(1,"fixture-a");it.addBatch();assertEquals(1,it.executeBatch().size)}
            c.createStatement().use {it.addBatch("UPDATE cash_transactions SET note='; COMMIT; BEGIN /* text */' WHERE workspace_id='none'");assertEquals(1,it.executeLargeBatch().size)}
            val native=Class.forName(if(db.sqlite) "org.sqlite.SQLiteConnection" else "org.postgresql.PGConnection")
            assertFalse(c.isWrapperFor(native));assertFailsWith<java.sql.SQLFeatureNotSupportedException> {c.unwrap(native)}
            proof.requireFor(repository,proof.digest)
        }
    }
    @Test fun autoCommitWindowCannotMintProofAndNewBeginInvalidatesItsOldContext() {
        val db=database();db.write {c ->
            if(db.sqlite) c.createStatement().use {it.execute("COMMIT")} else {c.commit();c.autoCommit=true}
            val context=CashSqlContext(c,"fixture-a",db.sqlite)
            try {incomplete {CashProjectionSourceRepository(context).readValidatedSources()}}
            finally {if(db.sqlite) c.createStatement().use {it.execute("BEGIN IMMEDIATE")} else c.autoCommit=false}
            incomplete {context.requireActive()}
            val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=fresh.readValidatedSources();proof.requireFor(fresh,proof.digest)
        }
    }
    @Test fun commentedSavepointCommandsKeepOuterIdentityAndPostgresAbortOrMultiStatementsEndIt() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            val commands=mutableListOf("ROLLBACK /* comment */ TO SAVEPOINT p","ROLLBACK TRANSACTION /* comment */ TO p")
            if(!db.sqlite) commands+="ROLLBACK WORK TO SAVEPOINT p"
            for(command in commands) {c.createStatement().use {it.execute("SAVEPOINT p");it.execute(command);it.execute("RELEASE SAVEPOINT p")};proof.requireFor(repository,proof.digest)}
            if(db.sqlite) {c.createStatement().use {it.execute("CREATE TEMP TRIGGER cash_scope_trigger AFTER UPDATE ON main.cash_transactions BEGIN SELECT CASE WHEN 1=1 THEN 1 ELSE 0 END; SELECT 2; END")};proof.requireFor(repository,proof.digest)}
            c.createStatement().use {s ->s.executeQuery("SELECT '; COMMIT; BEGIN' AS text").use {assertTrue(it.next());assertEquals("; COMMIT; BEGIN",it.getString(1))}};proof.requireFor(repository,proof.digest)
            if(!db.sqlite) {c.createStatement().use {it.execute("ABORT")};incomplete {proof.requireFor(repository,proof.digest)}
                val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",false));val next=fresh.readValidatedSources()
                c.createStatement().use {it.execute("SELECT 1; COMMIT; BEGIN")};incomplete {next.requireFor(fresh,next.digest)}
            }
        }
    }

    @Test fun sqliteReadSqlEndCannotMintProofButSuccessfulJdbcEndRenewsTransaction() {
        val db=database()
        for(commit in listOf(true,false)) db.read {c ->
            val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            c.createStatement().use {it.execute(if(commit) "COMMIT" else "ROLLBACK")}
            incomplete {proof.requireFor(repository,proof.digest)}
            if(db.sqlite) {
                try {
                    incomplete {CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",true)).readValidatedSources()}
                    assertFailsWith<java.sql.SQLException> {c.commit()}
                    incomplete {CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",true)).readValidatedSources()}
                }
                finally {c.createStatement().use {it.execute("BEGIN")}}
            }
            val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val next=fresh.readValidatedSources()
            if(commit) c.commit() else c.rollback()
            incomplete {next.requireFor(fresh,next.digest)}
            val renewed=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val current=renewed.readValidatedSources();current.requireFor(renewed,current.digest)
        }
    }
    @Test fun sqliteNonNestedCommentCannotHideACommitOrBatchBoundary() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            val sql=if(db.sqlite) "/* /* */ COMMIT" else "/* /* nested */ outer */ COMMIT"
            c.createStatement().use {s ->assertFailsWith<java.sql.SQLFeatureNotSupportedException> {s.addBatch(sql)}}
            c.createStatement().use {it.execute(sql);if(db.sqlite) it.execute("BEGIN IMMEDIATE")}
            incomplete {proof.requireFor(repository,proof.digest)}
        }
    }
    @Test fun jdbcMetadataKeepsItsDeclaredInterfaceAndNeverExposesDriverObjects() {
        val db=database();db.read {c ->c.prepareStatement("SELECT ? AS value").use {s ->
            s.setInt(1,42);val parameters=s.parameterMetaData;assertEquals(1,parameters.parameterCount)
            assertSame(parameters,parameters.unwrap(java.sql.ParameterMetaData::class.java))
            assertFalse(parameters.isWrapperFor(java.sql.PreparedStatement::class.java));assertFailsWith<java.sql.SQLFeatureNotSupportedException> {parameters.unwrap(java.sql.PreparedStatement::class.java)}
            s.executeQuery().use {r ->assertTrue(r.next());val columns=r.metaData;assertEquals(1,columns.columnCount);assertEquals("value",columns.getColumnLabel(1))
                assertSame(columns,columns.unwrap(java.sql.ResultSetMetaData::class.java));assertFalse(columns.isWrapperFor(java.sql.ResultSet::class.java));assertFailsWith<java.sql.SQLFeatureNotSupportedException> {columns.unwrap(java.sql.ResultSet::class.java)}
            }
        }}
    }

    @Test fun sqlErrorsInvalidateProofIncludingEngineRollbackBatchAndResultReadFailures() {
        val db=database();val modes=if(db.sqlite) listOf("conflict","batch","trigger","result") else listOf("conflict","batch","result")
        for(mode in modes) db.write {c ->
            c.createStatement().use {it.execute("CREATE TEMP TABLE cash_scope_unique(value INTEGER PRIMARY KEY)");it.execute("INSERT INTO cash_scope_unique VALUES(1)")
                if(mode=="trigger") it.execute("CREATE TEMP TRIGGER cash_scope_rollback BEFORE INSERT ON cash_scope_unique BEGIN SELECT RAISE(ROLLBACK,'synthetic rollback'); END")}
            val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            val duplicate=if(db.sqlite) "INSERT OR ROLLBACK INTO cash_scope_unique VALUES(1)" else "INSERT INTO cash_scope_unique VALUES(1)"
            assertFailsWith<java.sql.SQLException> {c.createStatement().use {s ->when(mode) {
                "batch" ->{s.addBatch(duplicate);s.executeBatch()}
                "trigger" ->s.executeUpdate("INSERT INTO cash_scope_unique VALUES(2)")
                "result" ->s.executeQuery("SELECT 1").use {assertTrue(it.next());it.getString(99)}
                else ->s.executeUpdate(duplicate)
            }}}
            incomplete {proof.requireFor(repository,proof.digest)}
            if(db.sqlite) {
                incomplete {CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",true)).readValidatedSources()}
                c.createStatement().use {try {it.execute("ROLLBACK")} catch(_:java.sql.SQLException) {} ;it.execute("BEGIN IMMEDIATE")}
            } else c.rollback()
            val fresh=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val next=fresh.readValidatedSources();next.requireFor(fresh,next.digest)
        }
    }
    @Test fun quotedControlWordsStayDataButDollarSignsInPostgresIdentifiersDoNotHideCommit() {
        val db=database();db.write {c ->val repository=CashProjectionSourceRepository(CashSqlContext(c,"fixture-a",db.sqlite));val proof=repository.readValidatedSources()
            c.createStatement().use {s ->s.executeQuery("SELECT 1 AS \"END; COMMIT\"").use {assertTrue(it.next());assertEquals(1,it.getInt(1))}};proof.requireFor(repository,proof.digest)
            if(!db.sqlite) {
                c.createStatement().use {it.execute("SELECT 1 AS x${'$'}tag${'$'}; COMMIT; SELECT ${'$'}tag${'$'}ignored${'$'}tag${'$'}")}
                incomplete {proof.requireFor(repository,proof.digest)}
            }
        }
    }
}
