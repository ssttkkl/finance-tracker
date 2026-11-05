package com.finance.tracker.backend

internal fun setAccountTestRole(database: Database, workspace: String, role: String) = database.write { connection ->
    connection.prepareStatement("UPDATE workspace_memberships SET role=? WHERE workspace_id=? AND user_id='admin'").use {
        it.setString(1,role);it.setString(2,workspace);check(it.executeUpdate()==1)
    }
}
