package com.localmodels.app.engine

/** Marks a session active only after its isolated context has been restored successfully. */
class SessionTracker {
    private var active: String? = null
    fun invalidate() { active = null }
    fun ensure(key: String, restore: () -> Unit): Boolean {
        if (active == key) return false
        active = null
        restore()
        active = key
        return true
    }
}
