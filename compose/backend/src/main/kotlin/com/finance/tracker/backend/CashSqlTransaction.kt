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
    private var autoCommit=connection.autoCommit
    private var explicit=false
    private var physical=false
    private var closed=false
    private val savepointRollbacks=mutableListOf<()->Unit>()
    private val beforeSavepoints=mutableListOf<()->Unit>()
    private fun clearListeners() {savepointRollbacks.clear();beforeSavepoints.clear()}
    fun beforeSavepoint(listener:()->Unit) {beforeSavepoints+=listener}
    fun beforeSavepoint() {try {beforeSavepoints.toList().forEach {it()}} catch(failure:Throwable) {sqlEnd();throw failure}}
    fun onSavepointRollback(listener:()->Unit) {savepointRollbacks+=listener}
    fun savepointRollback() {try {savepointRollbacks.toList().forEach {it()}} catch(failure:Throwable) {sqlEnd();throw failure}}
    fun isActive():Boolean=!closed && (if(sqlite) physical else !autoCommit || explicit) && !connection.isClosed
    fun begin() {explicit=true;physical=true;epoch.incrementAndGet();clearListeners()}
    fun sqlEnd() {explicit=false;physical=false;epoch.incrementAndGet();clearListeners()}
    fun driverEnd(success:Boolean) {sqlEnd();if(sqlite && success) physical=!autoCommit}
    fun modeChanged(success:Boolean) {epoch.incrementAndGet();clearListeners();explicit=false;physical=false;try {autoCommit=connection.autoCommit;if(sqlite && success) physical=!autoCommit} catch(_:SQLException) {closed=true}}
    fun close() {closed=true;sqlEnd()}
}
internal interface CashSqlTransactionBoundary {
    fun cashTransactionStamp():CashSqlTransactionStamp
    fun cashOnSavepointRollback(listener:()->Unit)
    fun cashBeforeSavepoint(listener:()->Unit)
}
private enum class CashSqlControl { BEGIN, END, SAVEPOINT, SAVEPOINT_BEGIN, SAVEPOINT_ROLLBACK }
private val cashSqlControlWords=setOf("BEGIN","START","COMMIT","END","ABORT","ROLLBACK","SAVEPOINT","RELEASE")
private val cashSqlDollarQuote=Regex("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$")
private fun cashSqlControls(sql:String,sqlite:Boolean):List<CashSqlControl> {
    val commands=mutableListOf<List<String>>();val words=mutableListOf<String>();var index=0;val multiple=';' in sql
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
            ch in "'\"" || sqlite && ch in "`[" ->{
                if(words.isEmpty()) words+="__quoted__"
                val end=if(ch=='[') ']' else ch
                val escapes=!sqlite && ch=='\'' && index>0 && sql[index-1] in "eE" && (index<2 || !sql[index-2].isLetterOrDigit())
                index++
                while(index<sql.length) {
                    if(escapes && sql[index]=='\\') index=minOf(sql.length,index+2)
                    else if(sql[index]==end) {index++;if(index<sql.length && sql[index]==end) index++ else break}
                    else index++
                }
            }
            !sqlite && ch=='$' ->{
                val tag=cashSqlDollarQuote.find(sql,index)?.takeIf {it.range.first==index}?.value
                if(tag==null) {if(words.isEmpty()) words+="__other__";index++}
                else {if(words.isEmpty()) words+="__quoted__";val close=sql.indexOf(tag,index+tag.length);index=if(close<0) sql.length else close+tag.length}
            }
            ch.isLetter() || ch=='_' || ch.code>=128 ->{
                val start=index++;while(index<sql.length && (sql[index].isLetterOrDigit() || sql[index] in "_$" || sql[index].code>=128)) index++
                val word=sql.substring(start,index).uppercase(java.util.Locale.ROOT)
                if(words.firstOrNull()=="CREATE" && word in setOf("TRIGGER","FUNCTION","PROCEDURE")) compoundCreate=true
                if(compoundCreate) when {word=="BEGIN" ->compoundDepth++;compoundDepth>0 && word=="CASE" ->compoundDepth++;compoundDepth>0 && word=="END" ->compoundDepth--}
                if(words.size<4) words+=word
                if(!multiple && words.size==1 && words[0] !in cashSqlControlWords) return emptyList()
            }
            else ->{if(words.isEmpty()) words+="__other__";index++}
        }
    }
    commands+=words.toList()
    return commands.mapNotNull {tokens ->when(tokens.firstOrNull()) {
        "BEGIN" ->CashSqlControl.BEGIN
        "START" ->if(tokens.getOrNull(1)=="TRANSACTION") CashSqlControl.BEGIN else null
        "COMMIT","END","ABORT" ->CashSqlControl.END
        "ROLLBACK" ->{val next=if(tokens.getOrNull(1) in setOf("WORK","TRANSACTION")) 2 else 1;if(tokens.getOrNull(next)=="TO") CashSqlControl.SAVEPOINT_ROLLBACK else CashSqlControl.END}
        "SAVEPOINT" ->CashSqlControl.SAVEPOINT_BEGIN
        "RELEASE" ->CashSqlControl.SAVEPOINT
        else ->null
    }}
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
    fun controls(actions:List<CashSqlControl>) {for(action in actions) when(action) {CashSqlControl.BEGIN ->state.begin();CashSqlControl.END ->state.sqlEnd();CashSqlControl.SAVEPOINT,CashSqlControl.SAVEPOINT_BEGIN ->Unit;CashSqlControl.SAVEPOINT_ROLLBACK ->state.savepointRollback()}}
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
                if(CashSqlControl.SAVEPOINT_BEGIN in actions && actions.none {it in setOf(CashSqlControl.BEGIN,CashSqlControl.END)}) state.beforeSavepoint()
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
            var success=false
            if(method.name=="setSavepoint") state.beforeSavepoint()
            try {val result=call(connection,method,args);success=true;if(method.name=="rollback" && !args.isNullOrEmpty()) state.savepointRollback();returned(result,null,args?.firstOrNull() as? String,method.returnType)}
            finally {when {close ->state.close();end ->state.driverEnd(success);mode ->state.modeChanged(success)}}
        }
    }} as Connection
    return proxy
}
