package com.hermesandroid.bridge.chat

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FlipsiBridge ↔ Hermes-Gateway-Chat-Client.
 *
 * Verbindet sich mit dem Dashboard-Backend (serve) über:
 *   1. POST /auth/password-login        → Session-Cookie (HttpOnly)
 *   2. POST /api/auth/ws-ticket         → 30s-Einmal-Ticket
 *   3. WS  /api/ws?ticket=...           → JSON-RPC-Chat (prompt.submit + events)
 *
 * Alle drei Schritte laufen über den mTLS-dekorierten OkHttp-Client.
 * Der Session-Cookie bleibt im App-Prozess (kein Persistieren des Passworts).
 */
object HermesChatClient {

    private const val TAG = "HermesChat"

    var gatewayUrl: String? = null      // z.B. "http://jarvis.lan:9119"
    private var password: String? = null
    private var cookies: MutableMap<String, String> = mutableMapOf()

    fun configure(url: String, password: String) {
        gatewayUrl = url.trimEnd('/')
        this.password = password
        cookies.clear()
    }

    private fun baseHttp(): OkHttpClient {
        return com.hermesandroid.bridge.security.MtlsSupport.decorate(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build())
    }

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun storeCookies(headers: okhttp3.Headers) {
        for (h in headers.values("set-cookie")) {
            val first = h.substringBefore(";")
            val idx = first.indexOf('=')
            if (idx > 0) {
                cookies[first.substring(0, idx).trim()] = first.substring(idx + 1).trim()
            }
        }
    }

    /** Step 1: Session einloggen. Gibt null zurück bei Erfolg, sonst Fehlermeldung. */
    fun login(context: Context, user: String, pass: String): String? {
        val url = gatewayUrl ?: return "Keine Gateway-URL konfiguriert"
        val client = com.hermesandroid.bridge.security.MtlsSupport.decorate(OkHttpClient())
        val body = JSONObject().put("provider", "basic").put("username", user).put("password", pass)
        val req = Request.Builder()
            .url("$url/auth/password-login")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                storeCookies(resp.headers)
                if (!resp.isSuccessful) return "Login fehlgeschlagen (HTTP ${resp.code})"
                val json = JSONObject(resp.body?.string() ?: "{}")
                return if (json.optBoolean("ok", false)) null else "Login verweigert"
            }
        } catch (e: Exception) {
            return "Verbindungsfehler: ${e.message}"
        }
    }

    /** Step 2: WS-Ticket holen (30s gültig). */
    fun fetchTicket(): String? {
        val url = gatewayUrl ?: return null
        val client = com.hermesandroid.bridge.security.MtlsSupport.decorate(OkHttpClient())
        val req = Request.Builder()
            .url("$url/api/auth/ws-ticket")
            .header("Cookie", cookieHeader())
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("ticket", null)
            }
        } catch (_: Exception) { null }
    }

    fun toMediaType(mime: String) = mime.toMediaType()

    /** Step 3: WS-URL mit Ticket bauen. */
    fun wsUrl(): String? {
        val url = gatewayUrl ?: return null
        val ticket = fetchTicket() ?: return null
        val wsBase = url.replace("https://", "wss://").replace("http://", "ws://")
        return "$wsBase/api/ws?ticket=$ticket"
    }

    private fun okhttp3.MediaType.Companion.toMediaType(m: String) =
        okhttp3.MediaType.Companion.run { "application/json; charset=utf-8".toMediaTypeOrNull()!! }
}