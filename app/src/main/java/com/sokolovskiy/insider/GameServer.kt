package com.sokolovskiy.insider

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Local HTTP + WebSocket server. The host's WebView and every player's browser load
 * the same page from here and keep one WebSocket open; the server pushes per-player
 * state snapshots (each player only sees what their role allows).
 */
class GameServer(private val context: Context) {

    val hostToken: String = UUID.randomUUID().toString().replace("-", "")
    var port = 0; private set

    private val lock = Any()
    private val game = Game(
        WordBank.fromJson(context.assets.open("web/words.json").bufferedReader().use { it.readText() })
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: EmbeddedServer<*, *>? = null

    private class Conn(val isHost: Boolean) {
        val outbox = Channel<String>(Channel.CONFLATED)
        @Volatile var playerId: String? = null
    }

    private val conns = ConcurrentHashMap.newKeySet<Conn>()

    fun start() {
        if (server != null) return
        port = NetUtil.freePort(8080)
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = 10.seconds
                timeout = 30.seconds
            }
            routing {
                get("/") { serveAsset(call, "index.html") }
                get("/host") { serveAsset(call, "index.html") }
                get("/static/{file}") { serveAsset(call, call.parameters["file"] ?: "") }
                get("/qr.svg") {
                    val text = call.request.queryParameters["t"] ?: ""
                    call.respondText(NetUtil.qrSvg(text), ContentType.parse("image/svg+xml"))
                }
                webSocket("/ws") {
                    val isHost = call.request.queryParameters["host"] == hostToken
                    val conn = Conn(isHost)
                    conns += conn
                    val sender = launch {
                        for (msg in conn.outbox) send(Frame.Text(msg))
                    }
                    sendState(conn)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handle(conn, frame.readText())
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "ws closed: ${t.message}")
                    } finally {
                        conns -= conn
                        sender.cancel()
                        conn.playerId?.let { pid ->
                            synchronized(lock) {
                                if (conns.none { it.playerId == pid }) game.players[pid]?.connected = false
                            }
                        }
                        broadcast()
                    }
                }
            }
        }.also { it.start(wait = false) }

        // Timers live on the server: advance phases whose time ran out.
        scope.launch {
            while (isActive) {
                delay(250)
                val changed = synchronized(lock) { game.tick(System.currentTimeMillis()) }
                if (changed) broadcast()
            }
        }
        Log.i(TAG, "Server on port $port")
    }

    fun stop() {
        scope.cancel()
        server?.stop(500, 1000)
        server = null
    }

    private suspend fun serveAsset(call: ApplicationCall, name: String) {
        if (name.isEmpty() || name.contains("..") || name.contains('/')) {
            call.respondText("Not found", status = HttpStatusCode.NotFound); return
        }
        val bytes = try {
            context.assets.open("web/$name").use { it.readBytes() }
        } catch (_: Exception) {
            call.respondText("Not found", status = HttpStatusCode.NotFound); return
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
            "js" -> ContentType.Application.JavaScript.withParameter("charset", "utf-8")
            "json" -> ContentType.Application.Json
            "svg" -> ContentType.parse("image/svg+xml")
            else -> ContentType.Application.OctetStream
        }
        call.respondBytes(bytes, type)
    }

    // ---------- messages ----------

    private fun handle(conn: Conn, text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val t = m.optString("t")
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val pid = conn.playerId
            val isMaster = pid != null && pid == game.masterId
            // The Master runs the round from their own phone; the host can always step in.
            val canRun = conn.isHost || isMaster
            when (t) {
                "join" -> {
                    val p = game.join(m.optString("name"), m.optString("pid").ifEmpty { null })
                    p.connected = true
                    conn.playerId = p.id
                    conn.outbox.trySend(JSONObject().put("t", "welcome").put("pid", p.id).toString())
                }
                "resume" -> {
                    val id = m.optString("pid")
                    val p = game.players[id]
                    if (p != null) {
                        p.connected = true
                        conn.playerId = id
                    } else {
                        conn.outbox.trySend(JSONObject().put("t", "unknown").toString())
                    }
                }
                "leave" -> pid?.let { game.remove(it); conn.playerId = null }
                "ready" -> pid?.let { game.markReady(it) }
                "vote1" -> pid?.let { game.castVote1(it, m.optBoolean("yes")) }
                "vote2" -> pid?.let { game.castVote2(it, m.optString("target")) }

                "swapWord" -> if (canRun) game.swapWord()
                "begin" -> if (canRun) game.beginQuestions(now)
                "guessed" -> if (canRun) game.guessed(m.optString("pid"), now)
                "endDiscussion" -> if (canRun) game.endDiscussion()
            }
            if (conn.isHost) when (t) {
                "start", "next" -> game.startRound()
                "seconds" -> game.questionSeconds = m.optInt("seconds", 300)
                "closeVote1" -> game.closeVote1()
                "closeVote2" -> game.closeVote2()
                "abort" -> game.abortRound()
                "newGame" -> game.newGame()
                "kick" -> game.remove(m.optString("pid"))
            }
        }
        broadcast()
    }

    // ---------- state ----------

    fun broadcast() {
        for (c in conns) sendState(c)
    }

    private fun sendState(c: Conn) {
        val json = synchronized(lock) {
            val o = game.stateJson(c.playerId, c.isHost, System.currentTimeMillis())
            o.put("t", "state")
            if (c.isHost) {
                val addrs = NetUtil.lanAddresses()
                o.put("urls", JSONArray(addrs.map {
                    JSONObject().put("url", "http://${it.ip}:$port/").put("hotspot", it.isHotspot)
                }))
            }
            o.toString()
        }
        c.outbox.trySend(json)
    }

    companion object {
        private const val TAG = "InsiderServer"
    }
}
