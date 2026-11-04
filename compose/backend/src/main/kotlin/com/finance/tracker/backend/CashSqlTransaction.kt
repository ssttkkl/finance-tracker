package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.*
import java.util.concurrent.atomic.AtomicLong

internal class CashSqlTransactionStamp(private val state:CashSqlTransactionState,private val captured:Long) {
    fun isCurrent():Boolean=state.epoch.get()==captured && state.isActive()
}
internal class CashSqlTransactionState(private val connection:Connection,private val sqlite:Boolean) {
    val epoch=AtomicLong()
    private val ledgerVersions=LedgerTransactionVersions(connection,sqlite)
    private var autoCommit=connection.autoCommit
    private var explicit=false
    private var physical=false
    private var closed=false
    private val savepointRollbacks=mutableListOf<()->Unit>()
    private val beforeSavepoints=mutableListOf<()->Unit>()
    private fun clearListeners() {savepointRollbacks.clear();beforeSavepoints.clear();ledgerVersions.clear()}
    fun beforeSavepoint(listener:()->Unit) {beforeSavepoints+=listener}
    fun beforeSavepoint() {try {beforeSavepoints.toList().forEach {it()}} catch(failure:Throwable) {sqlEnd();throw failure}}
    fun onSavepointRollback(listener:()->Unit) {savepointRollbacks+=listener}
    fun savepoint(key:Any) {ledgerVersions.savepoint(key)}
    fun releaseSavepoint(key:Any) {ledgerVersions.release(key)}
    fun savepointRollback(key:Any) {ledgerVersions.rollbackTo(key);try {savepointRollbacks.toList().forEach {it()}} catch(failure:Throwable) {sqlEnd();throw failure}}
    fun markLedgerChanged(workspace:String):Long {
        check(isActive()) {"ledger.active_transaction_required"}
        return try {ledgerVersions.mark(workspace)} catch(failure:org.jooq.exception.DataAccessException) {sqlEnd();throw failure}
    }
    fun isActive():Boolean=!closed && (if(sqlite) physical else !autoCommit || explicit) && !connection.isClosed
    fun begin() {explicit=true;physical=true;epoch.incrementAndGet();clearListeners()}
    fun sqlEnd() {explicit=false;physical=false;epoch.incrementAndGet();clearListeners()}
    fun driverEnd(success:Boolean) {sqlEnd();if(sqlite && success) physical=!autoCommit}
    fun modeChanged(success:Boolean) {epoch.incrementAndGet();clearListeners();explicit=false;physical=false;try {autoCommit=connection.autoCommit;if(sqlite && success) physical=!autoCommit} catch(_:SQLException) {closed=true}}
    fun close() {closed=true;sqlEnd()}
}
internal interface CashSqlTransactionBoundary {
    fun cashMarkLedgerChanged(workspace:String):Long
    fun cashTransactionStamp():CashSqlTransactionStamp
    fun cashOnSavepointRollback(listener:()->Unit)
    fun cashBeforeSavepoint(listener:()->Unit)
}
private enum class CashSqlControl { BEGIN, END, SAVEPOINT, SAVEPOINT_BEGIN, SAVEPOINT_ROLLBACK }
private data class CashSqlCommand(val kind: CashSqlControl, val savepoint: String? = null)
private data class CashSqlWord(val value: String, val quoted: Boolean = false, val unicode: Boolean = false) {
    val keyword: String? get() = if (quoted) null else value.uppercase(java.util.Locale.ROOT)
    fun decodeIdentifier(escape:String):CashSqlWord {
        if(!unicode) return this
        val decoded=StringBuilder();var index=0
        while(index<value.length) {
            if(!value.startsWith(escape,index)) decoded.append(value[index++])
            else {
                index+=escape.length
                if(value.startsWith(escape,index)) {decoded.append(escape);index+=escape.length}
                else {
                    val length=if(value[index]=='+') {index++;6} else 4
                    decoded.appendCodePoint(value.substring(index,index+length).toInt(16));index+=length
                }
            }
        }
        return CashSqlWord(decoded.toString(),quoted=true)
    }
    fun savepointKey(sqlite: Boolean): String {
        val normalized=if(sqlite || !quoted) value.map {if(it in 'A'..'Z') it.lowercaseChar() else it}.joinToString("") else value
        if(sqlite) return normalized
        // PostgreSQL 18 identifiers use at most 63 UTF-8 bytes, ending at a character boundary.
        val bytes=normalized.toByteArray(Charsets.UTF_8)
        if(bytes.size<=63) return normalized
        var end=63
        while(end>0 && bytes[end].toInt() and 0xc0==0x80) end--
        return String(bytes,0,end,Charsets.UTF_8)
    }
}
private val cashSqlControlWords=setOf("BEGIN","START","COMMIT","END","ABORT","ROLLBACK","SAVEPOINT","RELEASE")
private val cashSqlDollarQuote=Regex("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$")
private fun cashSqlControls(sql:String,sqlite:Boolean):List<CashSqlCommand> {
    val commands=mutableListOf<List<CashSqlWord>>();val words=mutableListOf<CashSqlWord>();var index=0;val multiple=';' in sql
    var compoundCreate=false;var compoundDepth=0
    while(index<sql.length) {
        val ch=sql[index]
        when {
            ch.isWhitespace() ->index++
            sql.startsWith("--",index) ->{index=sql.indexOf('\n',index+2).let {if(it<0) sql.length else it+1}}
            sql.startsWith("/*",index) ->{
                index+=2;var depth=1
                while(index<sql.length && depth>0) when {!sqlite && sql.startsWith("/*",index) ->{depth++;index+=2};sql.startsWith("*/",index) ->{depth--;index+=2};else ->index++}
            }
            ch==';' ->{if(compoundDepth==0) {commands+=words.toList();words.clear();compoundCreate=false};index++}
            ch in "'\"" || sqlite && ch in "`[" || !sqlite && ch in "uU" && sql.startsWith("&\"",index+1) ->{
                val unicode=ch in "uU"
                if(unicode) index+=2
                val quote=if(unicode) '"' else ch
                val end=if(quote=='[') ']' else quote
                val escapes=!sqlite && ch=='\'' && index>0 && sql[index-1] in "eE" && (index<2 || !sql[index-2].isLetterOrDigit())
                val capture=words.size<8
                val value=StringBuilder();index++
                while(index<sql.length) {
                    if(escapes && sql[index]=='\\') {index++;if(index<sql.length) {if(capture) value.append(sql[index]);index++}}
                    else if(sql[index]==end) {index++;if(index<sql.length && sql[index]==end) {if(capture) value.append(end);index++} else break}
                    else {if(capture) value.append(sql[index]);index++}
                }
                if(words.size<8) words+=CashSqlWord(value.toString(),quoted=true,unicode=unicode)
            }
            !sqlite && ch=='$' ->{
                val tag=cashSqlDollarQuote.find(sql,index)?.takeIf {it.range.first==index}?.value
                if(tag==null) {if(words.isEmpty()) words+=CashSqlWord("__other__");index++}
                else {if(words.isEmpty()) words+=CashSqlWord("__quoted__",quoted=true);val close=sql.indexOf(tag,index+tag.length);index=if(close<0) sql.length else close+tag.length}
            }
            ch.isLetter() || ch=='_' || ch.code>=128 ->{
                val start=index++;while(index<sql.length && (sql[index].isLetterOrDigit() || sql[index] in "_$" || sql[index].code>=128)) index++
                val value=sql.substring(start,index);val word=value.uppercase(java.util.Locale.ROOT)
                if(words.firstOrNull()?.keyword=="CREATE" && word in setOf("TRIGGER","FUNCTION","PROCEDURE")) compoundCreate=true
                if(compoundCreate) when {word=="BEGIN" ->compoundDepth++;compoundDepth>0 && word=="CASE" ->compoundDepth++;compoundDepth>0 && word=="END" ->compoundDepth--}
                if(words.size<8) words+=CashSqlWord(value)
                if(!multiple && words.size==1 && words[0].keyword !in cashSqlControlWords) return emptyList()
            }
            else ->{if(words.isEmpty()) words+=CashSqlWord("__other__");index++}
        }
    }
    commands+=words.toList()
    return commands.mapNotNull {tokens ->
        fun key(index:Int):String? {
            val token=tokens.getOrNull(index) ?: return null
            val escape=if(tokens.getOrNull(index+1)?.keyword=="UESCAPE") {
                val offset=if(tokens.getOrNull(index+2)?.keyword=="E") 3 else 2
                val literal=tokens.getOrNull(index+offset)
                if(literal==null || !literal.quoted || literal.value.codePointCount(0,literal.value.length)!=1)
                    throw SQLFeatureNotSupportedException("Unsupported savepoint escape literal")
                literal.value
            } else "\\"
            return token.decodeIdentifier(escape).savepointKey(sqlite)
        }
        when(tokens.firstOrNull()?.keyword) {
            "BEGIN" ->CashSqlCommand(CashSqlControl.BEGIN)
            "START" ->if(tokens.getOrNull(1)?.keyword=="TRANSACTION") CashSqlCommand(CashSqlControl.BEGIN) else null
            "COMMIT","END","ABORT" ->CashSqlCommand(CashSqlControl.END)
            "ROLLBACK" ->{
                val next=if(tokens.getOrNull(1)?.keyword in setOf("WORK","TRANSACTION")) 2 else 1
                if(tokens.getOrNull(next)?.keyword=="TO") CashSqlCommand(CashSqlControl.SAVEPOINT_ROLLBACK,key(if(tokens.getOrNull(next+1)?.keyword=="SAVEPOINT") next+2 else next+1))
                else CashSqlCommand(CashSqlControl.END)
            }
            "SAVEPOINT" ->CashSqlCommand(CashSqlControl.SAVEPOINT_BEGIN,key(1))
            "RELEASE" ->CashSqlCommand(CashSqlControl.SAVEPOINT,key(if(tokens.getOrNull(1)?.keyword=="SAVEPOINT") 2 else 1))
            else ->null
        }
    }
}
internal fun managedCashSqlConnection(connection:Connection,sqlite:Boolean):Connection {
    val state=CashSqlTransactionState(connection,sqlite)
    fun call(target:Any,method:Method,args:Array<out Any?>?):Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(f:InvocationTargetException) {
        val failure=f.targetException
        if(failure is SQLException) state.sqlEnd()
        throw failure
    }
    fun unwrap(proxy:Any,args:Array<out Any?>?,check:Boolean):Any {
        val type=args?.firstOrNull() as? Class<*> ?: throw SQLFeatureNotSupportedException("Native JDBC unwrap is unavailable")
        if(check) return type.isInstance(proxy)
        if(type.isInstance(proxy)) return proxy
        throw SQLFeatureNotSupportedException("Native JDBC unwrap is unavailable")
    }
    fun identity(proxy:Any,method:Method,args:Array<out Any?>?):Any?=when(method.name) {"equals" ->proxy===args?.firstOrNull();"hashCode" ->System.identityHashCode(proxy);else ->"ManagedCashSql${proxy.javaClass.interfaces.first().simpleName}"}
    fun controls(actions:List<CashSqlCommand>) {for(action in actions) when(action.kind) {
        CashSqlControl.BEGIN ->state.begin()
        CashSqlControl.END ->state.sqlEnd()
        CashSqlControl.SAVEPOINT_BEGIN ->state.savepoint(checkNotNull(action.savepoint))
        CashSqlControl.SAVEPOINT ->state.releaseSavepoint(checkNotNull(action.savepoint))
        CashSqlControl.SAVEPOINT_ROLLBACK ->state.savepointRollback(checkNotNull(action.savepoint))
    }}
    fun jdbcSavepointKey(point:Savepoint):Any = try {
        CashSqlWord(point.savepointName,quoted=true).savepointKey(sqlite)
    } catch(_:SQLException) {
        // The supported PostgreSQL JDBC driver emits this unquoted name for an unnamed savepoint.
        if(sqlite) point else "jdbc_savepoint_${point.savepointId}"
    }
    lateinit var proxy:Connection
    lateinit var returned:(Any?,Statement?,String?,Class<*>)->Any?
    fun typedMetadata(target:Any,type:Class<*>,owner:Statement?):Any=Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(type)) {self,method,args ->when {
        method.declaringClass==Any::class.java ->identity(self,method,args)
        method.name=="unwrap" || method.name=="isWrapperFor" ->unwrap(self,args,method.name=="isWrapperFor")
        else ->returned(call(target,method,args),owner,null,method.returnType)
    }}
    fun statement(target:Statement,sql:String?):Statement {
        val type=when(target) {is CallableStatement ->CallableStatement::class.java;is PreparedStatement ->PreparedStatement::class.java;else ->Statement::class.java}
        return Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(type)) {self,method,args ->when {
            method.declaringClass==Any::class.java ->identity(self,method,args)
            method.name=="getConnection" ->proxy
            method.name=="unwrap" || method.name=="isWrapperFor" ->unwrap(self,args,method.name=="isWrapperFor")
            else ->{
                val text=sql ?: args?.firstOrNull() as? String
                val actions=if(method.name in setOf("execute","executeUpdate","executeLargeUpdate","executeQuery","addBatch")) text?.let {cashSqlControls(it,sqlite)}.orEmpty() else emptyList()
                if(method.name=="addBatch" && actions.isNotEmpty()) throw SQLFeatureNotSupportedException("Transaction control SQL cannot be batched")
                if(actions.any {it.kind==CashSqlControl.SAVEPOINT_BEGIN} && actions.none {it.kind in setOf(CashSqlControl.BEGIN,CashSqlControl.END)}) state.beforeSavepoint()
                try {val result=call(target,method,args);controls(actions);returned(result,self as Statement,null,method.returnType)}
                catch(f:Throwable) {if(actions.isNotEmpty()) state.sqlEnd();throw f}
            }
        }} as Statement
    }
    fun resultSet(target:ResultSet,owner:Statement?):ResultSet=Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(ResultSet::class.java)) {self,method,args ->when {
        method.declaringClass==Any::class.java ->identity(self,method,args)
        method.name=="unwrap" || method.name=="isWrapperFor" ->unwrap(self,args,method.name=="isWrapperFor")
        method.name=="getStatement" && owner!=null ->owner
        else ->returned(call(target,method,args),owner,null,method.returnType)
    }} as ResultSet
    fun metadata(target:DatabaseMetaData):DatabaseMetaData=Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(DatabaseMetaData::class.java)) {self,method,args ->when {
        method.declaringClass==Any::class.java ->identity(self,method,args)
        method.name=="getConnection" ->proxy
        method.name=="unwrap" || method.name=="isWrapperFor" ->unwrap(self,args,method.name=="isWrapperFor")
        else ->returned(call(target,method,args),null,null,method.returnType)
    }} as DatabaseMetaData
    fun array(target:java.sql.Array,owner:Statement?):java.sql.Array=Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(java.sql.Array::class.java)) {self,method,args ->
        if(method.declaringClass==Any::class.java) identity(self,method,args) else returned(call(target,method,args),owner,null,method.returnType)
    } as java.sql.Array
    returned={result,owner,sql,type ->when {
        result==null ->null
        type==ResultSetMetaData::class.java || type==ParameterMetaData::class.java ->typedMetadata(result,type,owner)
        result is Connection ->proxy
        result is Statement ->statement(result,sql)
        result is ResultSet ->resultSet(result,owner)
        result is DatabaseMetaData ->metadata(result)
        result is java.sql.Array ->array(result,owner)
        else ->result
    }}
    proxy=Proxy.newProxyInstance(CashSqlTransactionBoundary::class.java.classLoader,arrayOf(Connection::class.java,CashSqlTransactionBoundary::class.java)) {self,method,args ->when {
        method.declaringClass==CashSqlTransactionBoundary::class.java ->when(method.name) {
            "cashMarkLedgerChanged" ->state.markLedgerChanged(args!!.single() as String)
            "cashTransactionStamp" ->CashSqlTransactionStamp(state,state.epoch.get())
            "cashBeforeSavepoint" ->{@Suppress("UNCHECKED_CAST") val listener=args!!.single() as ()->Unit;state.beforeSavepoint(listener);Unit}
            else ->{@Suppress("UNCHECKED_CAST") val listener=args!!.single() as ()->Unit;state.onSavepointRollback(listener);Unit}
        }
        method.declaringClass==Any::class.java ->identity(self,method,args)
        method.name=="unwrap" || method.name=="isWrapperFor" ->unwrap(self,args,method.name=="isWrapperFor")
        else ->{
            val end=method.name=="commit" || method.name=="rollback" && args.isNullOrEmpty()
            val close=method.name in setOf("close","abort")
            val mode=method.name=="setAutoCommit" && args?.firstOrNull()!=connection.autoCommit
            val savepointKey=if(method.name in setOf("releaseSavepoint","rollback") && !args.isNullOrEmpty()) jdbcSavepointKey(args.single() as Savepoint) else null
            var success=false
            if(method.name=="setSavepoint") state.beforeSavepoint()
            try {val result=call(connection,method,args);success=true
                when {
                    method.name=="setSavepoint" ->state.savepoint(jdbcSavepointKey(result as Savepoint))
                    method.name=="releaseSavepoint" ->state.releaseSavepoint(checkNotNull(savepointKey))
                    method.name=="rollback" && !args.isNullOrEmpty() ->state.savepointRollback(checkNotNull(savepointKey))
                }
                returned(result,null,args?.firstOrNull() as? String,method.returnType)}
            finally {when {close ->state.close();end ->state.driverEnd(success);mode ->state.modeChanged(success)}}
        }
    }} as Connection
    return proxy
}
