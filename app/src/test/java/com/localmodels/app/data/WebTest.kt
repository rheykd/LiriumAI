package com.localmodels.app.data

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebTest {
    @Test fun commandsAndSources() {
        assertEquals("погода в Москве", Web.findSearchCommand("Проверю\nSEARCH погода в Москве\n"))
        assertEquals(listOf("https://example.com/a"), Web.findFetchCommands("FETCH https://example.com/a"))
        assertEquals("Ответ\n\nИсточник", Web.stripFetchLines("Ответ\nSEARCH новости\nИсточник").replace("\n\n\n", "\n\n"))
        assertNull(Web.findSearchCommand("обычный ответ"))
        assertTrue(Web.parseSearchHtml("<html>captcha</html>").isEmpty())
    }

    @Test fun fetchPageAndApiStream() = runBlocking {
        val server = ServerSocket(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker = thread(isDaemon = true) {
            repeat(2) {
                server.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var count = 0
                    while (count < length) {
                        val n = input.read(chars, count, length - count)
                        if (n < 0) break
                        count += n
                    }
                    requests += String(chars)
                    val page = requestLine.contains("/page")
                    val body = if (page) "<html><script>hidden</script><p>Visible &amp; useful</p></html>"
                        else "data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"}}]}\n\ndata: [DONE]\n\n"
                    val bytes = body.toByteArray()
                    val type = if (page) "text/html" else "text/event-stream"
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertEquals("Visible & useful", Web.fetchText("$base/page"))
            val pieces = StringBuilder()
            assertEquals("Привет", RemoteApi.stream(base, "", "test-model", listOf(RemoteTurn("user", "hi"))) { pieces.append(it) })
            assertEquals("Привет", pieces.toString())
            worker.join(5000)
            assertEquals("test-model", org.json.JSONObject(requests.last()).getString("model"))
        } finally { server.close() }
    }
}
