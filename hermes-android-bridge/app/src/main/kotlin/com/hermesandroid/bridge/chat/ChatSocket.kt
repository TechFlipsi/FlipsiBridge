package com.hermesandroid.bridge.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * FlipsiBridge Chat-Session über den Gateway-WebSocket (JSON-RPC).
 *
 * Protokoll (live verifiziert gegen Hermes 0.21.3):
 *   → {"jsonrpc":"2.0","id":n,"method":"session.create","params":{...}}
 *   → {"jsonrpc":"2.0","id":n,"method":"prompt.submit","params":{"session_id":..,"text":..}}
 *   ← events: message.delta / message.complete / session.info / ...
 *
 * Callbacks laufen auf dem Main-Thread (Handler), damit die UI direkt binden kann.
 */
class ChatSocket {

    interface Listener {
        fun onConnected(sessionId: String)
        fun onDelta(text: String)
        fun onComplete(fullText: String)
        fun onError(message: String)
        fun onClosed()
    }

    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var nextId = 100
    private val pendingTitles = mutableMapOf<Int, String>()

    fun close() {
        ws?.close(1000, "client closing")
        ws = null
    }

    fun send(
        client: OkHttpClient,
        wsUrl: String,
        text: String,
        listener: Listener
    ): Boolean {
        close()
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            private var gotReady = false
            private val sb = StringBuilder()

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS open")
                // Session erstellen
                webSocket.send(JSONObject().apply {
                    put("jsonrpc", "2.0"); put("id", 1)
                    put("method", "session.create")
                    put("params", JSONObject().put("title", text.take(60)))
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    val method = m.optString("method", "")
                    val id = m.optInt("id", -1)
                    when {
                        // session.create-Ergebnis
                        id == 1 && m.has("result") -> {
                            val sid = m.getJSONObject("result").optString("session_id")
                            sessionId = sid
                            // Prompt abschicken
                            webSocket.send(JSONObject().apply {
                                put("jsonrpc", "2.0"); put("id", 2)
                                put("method", "prompt.submit")
                                put("params", JSONObject()
                                    .put("session_id", sid)
                                    .put("text", text))
                            }.toString())
                        }
                        id == 1 && m.has("error") -> {
                            main.post { listener.onError(m.getJSONObject("error").optString("message")) }
                        }
                        // Events
                        method == "event" -> {
                            val params = m.getJSONObject("params")
                            val type = params.optString("type")
                            val payload = params.optJSONObject("payload") ?: JSONObject()
                            when (type) {
                                "gateway.ready" -> { gotReady = true }
                                "message.delta" -> {
                                    val t = payload.optString("text")
                                    if (t.isNotEmpty()) main.post { listener.onDelta(t) }
                                }
                                "message.complete" -> {
                                    val full = payload.optString("text")
                                    main.post { listener.onComplete(full) }
                                    // Chat-Socket nach Antwort schließen (stateless pro Frage)
                                    webSocket.close(1000, "done")
                                }
                                "session.usage" -> { /* Metering, ignorieren */ }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { listener.onError("Verbindung fehlgeschlagen: ${t.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { listener.onClosed() }
            }
        })
        return true
    }

    companion object {
        private const val TAG = "HermesChat"
    }
}