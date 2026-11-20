package com.localmodels.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localmodels.app.engine.LocalEngine
import com.localmodels.app.engine.TokenSink
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live state of the optional LAN host, read by Compose. */
object LanHost {
    var clients by mutableStateOf(emptyList<LanClient>())
    internal var disconnectClient: ((String) -> Unit)? = null
    fun disconnect(id: String) { disconnectClient?.invoke(id) }
    var running by mutableStateOf(false)
    var address by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
}

/**
 * Tiny HTTP server: password-protected web chat + JSON API.
 * It uses the same llama.cpp engine as the app (one model, one history).
 */
class LanServer(
    private val app: Context,
    private val password: String,
    private val port: Int
) {
    private val prefs = Prefs(app)
    private val store = ModelStore(app)
    private val sessions = LanSessions()
    @Volatile private var activeSessionId: String? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private fun publishClients() { val list = sessions.clients(); ui.post { if (running.get()) LanHost.clients = list } }
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null

    fun start() {
        LanHost.error = null
        val ss = ServerSocket(port)
        socket = ss
        running.set(true)
        LanHost.disconnectClient = { id ->
            sessions.revoke(id)
            if (activeSessionId == id) LocalEngine.stop()
            publishClients()
        }
        LanHost.address = "http://${localIp()}:$port"
        LanHost.running = true
        pool.execute {
            try {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        pool.execute { serve(client) }
                    } catch (_: Exception) {
                        if (running.get()) Thread.sleep(120)
                    }
                }
            } catch (e: Exception) {
                running.set(false)
                runCatching { ss.close() }
                LanHost.error = e.message ?: "не удалось запустить сервер"
                LanHost.running = false
                LanHost.address = ""
            }
        }
    }

    fun stop() {
        running.set(false)
        sessions.clear()
        if (activeSessionId != null) LocalEngine.stop()
        LanHost.disconnectClient = null
        LanHost.clients = emptyList()
        runCatching { socket?.close() }
        socket = null
        LanHost.running = false
        LanHost.address = ""
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = 120_000
            try {
                val req = (readRequest(c.getInputStream()) ?: return).copy(peer = c.inetAddress.hostAddress.orEmpty())
                val out = c.getOutputStream()
                route(req, out)
                out.flush()
            } catch (_: Exception) {
                // client went away
            }
        }
    }

    private fun route(req: Request, out: OutputStream) {
        val path = req.path
        val session = session(req)
        publishClients()
        when {
            req.method == "GET" && path == "/" -> {
                if (session != null) {
                    respond(out, 200, "text/html; charset=utf-8", panelHtml())
                } else {
                    respond(out, 200, "text/html; charset=utf-8", loginHtml())
                }
            }
            req.method == "POST" && path == "/login" -> {
                val form = String(req.body, Charsets.UTF_8)
                val given = form.split("&").firstOrNull { it.startsWith("password=") }
                    ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
                    ?: runCatching { JSONObject(form).optString("password") }.getOrDefault("")
                if (given == password) {
                    session?.let { sessions.revoke(it.id) }
                    val loggedIn = runCatching { sessions.create(req.headers["user-agent"].orEmpty().take(80).ifBlank { "браузер" }, req.peer) }
                        .getOrElse { respond(out, 409, "application/json", jsonError(it.message ?: "лимит сессий")); return }
                    publishClients()
                    val json = req.headers["content-type"].orEmpty().contains("application/json")
                    respond(
                        out, if (json) 200 else 302, if (json) "application/json" else "text/plain; charset=utf-8",
                        if (json) JSONObject().put("session", loggedIn.token).toString() else "",
                        listOf("Location: /", "Set-Cookie: lirium=${loggedIn.token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800")
                    )
                } else {
                    respond(out, 401, "text/html; charset=utf-8", loginHtml("неверный пароль"))
                }
            }
            req.method == "GET" && path == "/logout" -> {
                session?.let { sessions.revoke(it.id); if (activeSessionId == it.id) LocalEngine.stop() }
                publishClients()
                respond(
                    out, 302, "text/plain; charset=utf-8", "",
                    listOf("Location: /", "Set-Cookie: lirium=; Path=/; Max-Age=0")
                )
            }
            session == null -> {
                respond(out, 401, "application/json; charset=utf-8", jsonError("нужен пароль"))
            }
            req.method == "GET" && path == "/api/state" -> {
                respond(out, 200, "application/json; charset=utf-8", stateJson(session).toString())
            }
            req.method == "POST" && path == "/api/load" -> {
                val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
                val id = body?.optString("id").orEmpty()
                val model = loadCatalog().firstOrNull { it.id == id }
                if (model == null) {
                    respond(out, 404, "application/json; charset=utf-8", jsonError("модель не найдена"))
                } else if (!store.isInstalled(model)) {
                    respond(out, 409, "application/json; charset=utf-8", jsonError("модель не скачана"))
                } else {
                    if (!session.busy.compareAndSet(false, true)) { respond(out, 409, "application/json", jsonError("сначала остановите ответ")); return }
                    try {
                    session.modelId = model.id
                    session.history = emptyList()
                    session.messages = emptyList()
                    session.revision++
                    val rc = LocalEngine.exclusive {
                        if (session.revoked) 1 else LocalEngine.load(app, store, model)
                    }
                    if (rc == 0) {
                        respond(out, 200, "application/json; charset=utf-8", stateJson(session).toString())
                    } else {
                        respond(out, 500, "application/json; charset=utf-8", jsonError("не удалось загрузить"))
                    }
                    } finally { session.busy.set(false); publishClients() }
                }
            }
            req.method == "POST" && path == "/api/reset" -> {
                if (!session.busy.compareAndSet(false, true)) { respond(out, 409, "application/json", jsonError("сначала остановите ответ")); return }
                try {
                    session.history = emptyList()
                    session.messages = emptyList()
                    session.revision++
                } finally { session.busy.set(false) }
                respond(out, 200, "application/json; charset=utf-8", "{\"ok\":true}")
            }
            req.method == "POST" && path == "/api/stop" -> {
                session.control.cancel()
                if (activeSessionId == session.id) LocalEngine.stop()
                respond(out, 200, "application/json", "{\"ok\":true}")
            }
            req.method == "POST" && path == "/api/chat" -> {
                val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
                val text = body?.optString("text").orEmpty().trim()
                val modelId = body?.optString("model")?.takeIf { it.isNotBlank() }
                if (text.isEmpty()) {
                    respond(out, 400, "application/json; charset=utf-8", jsonError("пустое сообщение"))
                    return
                }
                if (modelId == null && LocalEngine.loadedModelId == null) {
                    respond(out, 409, "application/json; charset=utf-8", jsonError("модель не загружена"))
                    return
                }
                val stream = req.query["stream"] == "1" || req.headers["accept"].orEmpty().contains("text/event-stream")
                if (stream) {
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                            "Cache-Control: no-cache\r\nConnection: close\r\n" +
                            "X-Accel-Buffering: no\r\n\r\n").toByteArray()
                    )
                    out.flush()
                    val full = try { runChat(session, text, modelId) { piece ->
                        val data = JSONObject().put("token", piece).toString()
                        synchronized(out) {
                            out.write("data: $data\n\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    } } catch (e: Exception) {
                        val data = jsonError(e.message ?: "ошибка модели")
                        out.write("data: $data\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        return
                    }
                    synchronized(out) {
                        out.write("data: ${JSONObject().put("done", true).put("text", full)}\n\n".toByteArray(Charsets.UTF_8))
                        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } else {
                    val full = runChat(session, text, modelId) { }
                    respond(
                        out, 200, "application/json; charset=utf-8",
                        JSONObject().put("text", full).toString()
                    )
                }
            }
            else -> respond(out, 404, "application/json; charset=utf-8", jsonError("нет такого пути"))
        }
    }

    private fun runChat(session: LanSession, userText: String, modelId: String?, onToken: (String) -> Unit): String {
        check(session.busy.compareAndSet(false, true)) { "в этой сессии уже выполняется запрос" }
        val control = RequestControl()
        session.control = control
        publishClients()
        try {
            check(!session.revoked) { "сессия отключена на телефоне" }
            val webPrompt = runBlocking { Web.preparePrompt(userText, prefs.internetEnabled, control = control) }
            return LocalEngine.exclusive {
                control.checkActive()
                check(!session.revoked) { "сессия отключена на телефоне" }
                activeSessionId = session.id
                try {
                    val chosen = modelId ?: session.modelId ?: LocalEngine.loadedModelId
                    val model = loadCatalog().firstOrNull { it.id == chosen } ?: error("выберите модель")
                    check(store.isInstalled(model)) { "модель не скачана" }
                    check(LocalEngine.load(app, store, model) == 0) { "не удалось загрузить модель" }
                    if (session.modelId != model.id) { session.history = emptyList(); session.messages = emptyList(); session.modelId = model.id }
                    val system = SystemPrompt.build(prefs.internetEnabled)
                    val history = session.history.toMutableList()
                    LocalEngine.useConversation("lan/${session.id}/${session.revision++}", history, system, cancelled = { control.cancelled || session.revoked })
                    var prompt = webPrompt.text
                    var finalAnswer = ""
                    for (round in 0 until 3) {
                        control.checkActive()
                        val sb = StringBuilder()
                        LocalEngine.chat(prompt, emptyArray(), IntArray(0), IntArray(0),
                            512, 0.7f, 0.95f, 40, 1.1f, system,
                            object : TokenSink {
                                override fun onToken(text: String) {
                                    control.checkActive()
                                    sb.append(text); onToken(text)
                                }
                            }, cancelled = { control.cancelled || session.revoked })
                        control.checkActive()
                        val answer = sb.toString()
                        history += ChatTurn("user", prompt)
                        history += ChatTurn("assistant", answer)
                        finalAnswer = Web.stripFetchLines(answer)
                        if (!prefs.internetEnabled || round >= 2) break
                        prompt = runBlocking { Web.followUp(answer, webPrompt, control = control) } ?: break
                    }
                    control.checkActive()
                    check(!session.revoked) { "сессия отключена" }
                    val corrected = Web.correctedAnswer(finalAnswer, webPrompt)
                    session.history = ChatHistory.bounded(history)
                    session.messages = (session.messages + ChatTurn("user", userText) + ChatTurn("assistant", corrected)).takeLast(100)
                    corrected
                } finally { activeSessionId = null }
            }
        } finally { session.busy.set(false); publishClients() }
    }

    private fun loadCatalog(): List<CatalogModel> =
        Catalog.load(app, prefs.customCatalogJson)

    private fun stateJson(session: LanSession): JSONObject {
        val installed = store.installedModels(loadCatalog())
        val arr = JSONArray()
        for (m in installed) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("vision", m.vision)
                    .put("bytes", m.bytes)
                    .put("loaded", m.id == session.modelId)
            )
        }
        return JSONObject()
            .put("app", "LiriumAI")
            .put("models", arr)
            .put("loaded", session.modelId ?: JSONObject.NULL)
            .put("running", LanHost.running)
            .put("messages", JSONArray(session.messages.map { JSONObject().put("role", it.role).put("text", it.text) }))
    }

    private fun session(req: Request): LanSession? {
        val token = req.headers["cookie"].orEmpty().split(';').map { it.trim() }
            .firstOrNull { it.startsWith("lirium=") }?.substringAfter('=')
            ?: req.headers["x-session"]
        return sessions.find(token)
    }

    private fun jsonError(message: String): String =
        JSONObject().put("error", message).toString()

    private fun respond(
        out: OutputStream,
        code: Int,
        type: String,
        body: String,
        extra: List<String> = emptyList()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 $code ${status(code)}\r\n")
        head.append("Content-Type: $type\r\n")
        head.append("Content-Length: ${bytes.size}\r\n")
        head.append("Connection: close\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Password\r\n")
        for (line in extra) head.append(line).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun status(code: Int): String = when (code) {
        200 -> "OK"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val peer: String = ""
    )

    private fun readRequest(input: InputStream): Request? {
        val buf = BufferedInputStream(input)
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 64 * 1024) {
            val b = buf.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val text = head.toString("ISO-8859-1")
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0]
        val target = first[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, String>()
        target.substringAfter('?', "").split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            query[k] = v
        }
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceIn(0, 2_000_000))
        var read = 0
        while (read < body.size) {
            val n = buf.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        return Request(method, path, query, headers, body)
    }

    private fun localIp(): String {
        val wifiAddress = runCatching {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            manager.allNetworks.firstNotNullOfOrNull { network ->
                if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    manager.getLinkProperties(network)?.linkAddresses
                        ?.map { it.address }?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                } else null
            }
        }.getOrNull()
        if (wifiAddress != null) return wifiAddress
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    private fun loginHtml(error: String? = null): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LiriumAI</title>
<style>
body{margin:0;background:#0a0a0a;color:#f0f0f0;font-family:Montserrat,system-ui,sans-serif;
display:flex;align-items:center;justify-content:center;min-height:100vh}
form{width:260px;display:flex;flex-direction:column;gap:14px}
h1{font-size:24px;margin:0 0 10px;font-weight:700}
input,button{background:#111;border:1px solid #232323;color:#f0f0f0;padding:12px;border-radius:12px;
font-family:inherit;font-size:15px}
button{background:#f0f0f0;color:#000;font-weight:700;border:0}
p{color:#8c8c8c;font-size:13px;margin:0}
</style></head><body>
<form method="post" action="/login">
<h1>LiriumAI</h1>
<p>локальные модели по wifi</p>
<input type="password" name="password" placeholder="пароль" autofocus>
<button type="submit">войти</button>
${if (error != null) "<p style=\"color:#f0f0f0\">$error</p>" else ""}
</form></body></html>
""".trimIndent()

    private fun panelHtml(): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LiriumAI</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0a0a0a;color:#f0f0f0;font-family:Montserrat,system-ui,sans-serif;
height:100vh;display:flex;flex-direction:column}
header{display:flex;align-items:center;gap:10px;padding:12px 16px;border-bottom:1px solid #232323}
header b{font-size:16px}
select{background:#111;color:#f0f0f0;border:1px solid #232323;border-radius:10px;padding:7px;
font-family:inherit;max-width:220px}
#log{flex:1;overflow:auto;padding:16px;display:flex;flex-direction:column;gap:14px}
.msg{max-width:86%;padding:10px 14px;border-radius:16px;white-space:pre-wrap;line-height:1.45;
font-size:14px}
.user{align-self:flex-end;background:#191919}
.bot{align-self:flex-start;background:#111;border:1px solid #232323}
footer{border-top:1px solid #232323;padding:10px;display:flex;gap:8px}
textarea{flex:1;resize:none;height:46px;background:#111;border:1px solid #232323;border-radius:14px;
color:#f0f0f0;padding:12px;font-family:inherit;font-size:14px}
button{background:#f0f0f0;border:0;color:#000;border-radius:14px;padding:0 18px;font-weight:700;
font-family:inherit;cursor:pointer}
button:disabled{background:#232323;color:#5a5a5a}
a{color:#8c8c8c;font-size:12px;margin-left:auto;text-decoration:none}
</style></head><body>
<header><b>LiriumAI</b>
<select id="model"></select>
<button id="reset">новый чат</button><button id="stop">стоп</button><a href="/logout">выйти</a></header>
<div id="log"></div>
<footer><textarea id="text" placeholder="сообщение"></textarea>
<button id="send">отправить</button></footer>
<script>
var log=document.getElementById('log'),sel=document.getElementById('model');
var text=document.getElementById('text'),send=document.getElementById('send'),busy=false,loaded=null;
function add(cls,content){var d=document.createElement('div');d.className='msg '+cls;
d.textContent=content;log.appendChild(d);log.scrollTop=log.scrollHeight;return d}
document.getElementById('stop').onclick=function(){fetch('/api/stop',{method:'POST'})};
document.getElementById('reset').onclick=async function(){if(busy)return;var r=await fetch('/api/reset',{method:'POST'});if(r.ok)log.textContent=''};
async function loadState(){var r=await fetch('/api/state');if(!r.ok){if(r.status===401){send.disabled=true;sel.disabled=true;document.getElementById('text').disabled=true;log.textContent='Сессия отключена. Обновите страницу и войдите заново.'}return;}
var s=await r.json();loaded=s.loaded;var have=Array.from(sel.options).map(function(o){return o.value}).join();
var want=s.models.map(function(m){return m.id}).join();
if(have!==want){sel.innerHTML='';
s.models.forEach(function(m){var o=document.createElement('option');o.value=m.id;
o.textContent=m.name+(m.vision?' / фото':'');sel.appendChild(o)})}
if(!busy&&loaded)sel.value=loaded;
if(!busy&&!log.children.length&&s.messages)s.messages.forEach(function(m){add(m.role==='user'?'user':'bot',m.text)})}
async function choose(){if(!sel.value||busy)return;busy=true;send.disabled=true;sel.disabled=true;
try{var r=await fetch('/api/load',{method:'POST',headers:{'Content-Type':'application/json'},
body:JSON.stringify({id:sel.value})});var s=await r.json();if(!r.ok)throw Error(s.error||('HTTP '+r.status));
loaded=s.loaded;log.textContent=''}catch(e){add('bot',e.message)}
finally{busy=false;send.disabled=false;sel.disabled=false}}
sel.onchange=choose;
async function sendMsg(){if(busy)return;var t=text.value.trim();if(!t)return;
if(!sel.value){add('bot','сначала выберите модель');return}
if(loaded!==sel.value){await choose();if(loaded!==sel.value)return}
text.value='';add('user',t);var bubble=add('bot','');busy=true;send.disabled=true;sel.disabled=true;
try{var r=await fetch('/api/chat?stream=1',{method:'POST',
headers:{'Content-Type':'application/json'},body:JSON.stringify({text:t,model:sel.value})});
if(!r.ok){var e=await r.json().catch(function(){return{}});
bubble.textContent=e.error||('HTTP '+r.status);return}
var reader=r.body.getReader(),dec=new TextDecoder(),buf='';
while(true){var p=await reader.read();if(p.done)break;buf+=dec.decode(p.value,{stream:true});
var lines=buf.split('\n');buf=lines.pop();
for(var i=0;i<lines.length;i++){var line=lines[i].trim();
if(!line.startsWith('data:'))continue;var data=line.slice(5).trim();
if(data==='[DONE]')continue;var o=JSON.parse(data);
if(o.token)bubble.textContent+=o.token;if(o.done)bubble.textContent=o.text;
if(o.error)throw Error(o.error);log.scrollTop=log.scrollHeight}}}
catch(e){bubble.textContent='ошибка: '+e.message}
finally{busy=false;send.disabled=false;sel.disabled=false}}
send.onclick=sendMsg;
text.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendMsg()}});
loadState();setInterval(loadState,4000);
</script></body></html>
""".trimIndent()

    companion object {
        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
