package com.localmodels.app.data

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class LanClient(val id: String, val name: String, val address: String, val lastSeen: Long, val busy: Boolean)
class LanSession(val token: String, val id: String, val name: String, val address: String, var lastSeen: Long) {
    val busy = AtomicBoolean(false)
    @Volatile var revoked = false
    @Volatile var control = RequestControl()
    var history: List<ChatTurn> = emptyList()
    @Volatile var messages: List<ChatTurn> = emptyList()
    var modelId: String? = null
    var revision = 0L
}

class LanSessions(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = linkedMapOf<String, LanSession>()
    @Synchronized fun create(name: String, address: String): LanSession {
        expire()
        check(entries.size < 32) { "достигнут лимит 32 сессий; отключите старые на телефоне" }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return LanSession(token, UUID.randomUUID().toString(), name.take(80), address, now()).also { entries[token] = it }
    }
    @Synchronized fun find(token: String?): LanSession? {
        expire()
        return entries[token]?.takeUnless { it.revoked }?.also { it.lastSeen = now() }
    }
    @Synchronized fun revoke(id: String): LanSession? {
        val session = entries.values.firstOrNull { it.id == id } ?: return null
        entries.remove(session.token)
        session.revoked = true
        session.control.cancel()
        session.history = emptyList()
        session.messages = emptyList()
        return session
    }
    @Synchronized fun clients(): List<LanClient> {
        expire()
        return entries.values.map { LanClient(it.id, it.name, it.address, it.lastSeen, it.busy.get()) }
    }
    @Synchronized fun clear() { entries.values.toList().forEach { revoke(it.id) } }
    private fun expire() {
        entries.values.filter { now() - it.lastSeen > 7L * 24 * 60 * 60 * 1000 && !it.busy.get() }.toList().forEach { revoke(it.id) }
    }
}
