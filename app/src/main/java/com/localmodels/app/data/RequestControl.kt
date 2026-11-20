package com.localmodels.app.data

import java.net.HttpURLConnection

/** One request owns its sockets; cancelling it never disconnects another chat. */
class RequestControl {
    @Volatile var cancelled = false
        private set
    private val connections = mutableSetOf<HttpURLConnection>()
    @Synchronized fun register(connection: HttpURLConnection) {
        checkActive()
        connections += connection
    }
    @Synchronized fun release(connection: HttpURLConnection) { connections -= connection }
    fun checkActive() { if (cancelled) throw IllegalStateException("stopped") }
    fun cancel() {
        val pending = synchronized(this) { cancelled = true; connections.toList().also { connections.clear() } }
        pending.forEach { connection -> Thread({ runCatching { connection.disconnect() } }, "lirium-cancel").apply { isDaemon = true }.start() }
    }
}
