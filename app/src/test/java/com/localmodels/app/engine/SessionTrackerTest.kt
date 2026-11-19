package com.localmodels.app.engine

import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    @Test fun aThenNewBThenARestoresOnlyOwnContext() {
        val tracker = SessionTracker()
        val kv = mutableListOf<String>()
        val a = listOf("private A question", "private A answer")
        tracker.ensure("A") { kv.clear(); kv.addAll(a) }
        tracker.ensure("B") { kv.clear() }
        assertTrue(kv.isEmpty())
        kv += "private B"
        tracker.ensure("A") { kv.clear(); kv.addAll(a) }
        assertEquals(a, kv)
        assertFalse(kv.contains("private B"))
    }

    @Test fun continuingSameChatKeepsWarmContext() {
        val tracker = SessionTracker()
        var restores = 0
        assertTrue(tracker.ensure("A") { restores++ })
        assertFalse(tracker.ensure("A") { restores++ })
        assertEquals(1, restores)
        tracker.invalidate()
        assertTrue(tracker.ensure("A") { restores++ })
        assertEquals(2, restores)
    }

    @Test fun failedRestoreCannotReusePreviousCachedSession() {
        val tracker = SessionTracker()
        tracker.ensure("A") {}
        assertThrows(IllegalStateException::class.java) { tracker.ensure("B") { error("failed restore") } }
        assertTrue(tracker.ensure("A") {})
    }

    @Test fun lanAndModelChangesDoNotCarryChatContext() {
        val tracker = SessionTracker()
        val kv = mutableListOf("private")
        tracker.ensure("chat:A:model:one") {}
        tracker.ensure("lan") { kv.clear() }
        assertTrue(kv.isEmpty())
        tracker.ensure("chat:A:model:two") { kv.clear(); kv += "own saved history" }
        assertEquals(listOf("own saved history"), kv)
    }
}
