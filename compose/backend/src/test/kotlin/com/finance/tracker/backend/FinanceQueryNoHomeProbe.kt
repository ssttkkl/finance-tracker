package com.finance.tracker.backend

import java.security.Permission
import kotlinx.serialization.json.JsonObject

@Suppress("DEPRECATION", "removal")
object FinanceQueryNoHomeProbe {
    @JvmStatic fun main(args: Array<String>) {
        System.setSecurityManager(object : SecurityManager() {
            override fun checkPermission(perm: Permission) = Unit
            override fun checkPropertyAccess(key: String) {
                if (key == "user.home") throw SecurityException("query initialization touched home")
            }
        })
        try {
            check(runCatching { System.getProperty("user.home") }.exceptionOrNull() is SecurityException)
            for (name in listOf("FinanceQueryApplication", "FinanceQueryAccount", "FinanceDecimal", "FinanceQueryService")) {
                check(Class.forName("com.finance.tracker.backend.$name") != null)
            }
            val app = FinanceQueryApplication(accounts = { emptyList() }, snapshot = { JsonObject(emptyMap()) })
            check(app.listAccounts().isNotEmpty()); check(app.report().isNotEmpty())
            check(FinanceDecimal.ZERO.fixed() == "0")
            print("NO_HOME_FINANCE_QUERY_OK")
        } finally { System.setSecurityManager(null) }
    }
}
