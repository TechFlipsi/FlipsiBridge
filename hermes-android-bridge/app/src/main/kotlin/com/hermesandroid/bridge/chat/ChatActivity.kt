package com.hermesandroid.bridge.chat

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient

/**
 * FlipsiBridge Chat — direkter Draht zum J.A.R.V.I.S.-Agent über das Hermes-Gateway.
 *
 * Einstieg: Gateway-URL + Passwort (in App-Settings gespeichert), Login läuft
 * automatisch beim ersten Senden. Danach Session pro Frage (stateless).
 */
class ChatActivity : android.app.Activity() {

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var sendBtn: TextView
    private var chatSocket: ChatSocket? = null

    private var gatewayUrl: String = ""
    private var chatUser: String = ""
    private var chatPass: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("flipsibridge_chat", MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "") ?: ""
        chatUser = prefs.getString("chat_user", "fabian") ?: "fabian"
        chatPass = prefs.getString("chat_pass", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111318.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Kopf
        val header = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 20f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(header)

        val settings = TextView(this).apply {
            text = "⚙ Gateway"
            textSize = 13f
            setTextColor(0xFFED7931.toInt())
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { showSettings() }
        }
        root.addView(settings)

        // Nachrichtenbereich
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = ScrollView(this).apply {
            addView(messages)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Statuszeile
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        status = TextView(this).apply {
            text = if (gatewayUrl.isBlank()) "Erst Gateway einrichten" else "Bereit"
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow)

        // Eingabezeile
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Nachricht an J.A.R.V.I.S.…"
            textSize = 15f
            setTextColor(0xFFE8EAED.toInt())
            setHintTextColor(0xFF5F6368.toInt())
            setBackgroundColor(0xFF1B1E24.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 22f
            setTextColor(0xFFED7931.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { send() }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        setContentView(root)

        if (gatewayUrl.isBlank()) showSettings()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble(text: String, isMe: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (isMe) 0xFFE8EAED.toInt() else 0xFFB8E6C8.toInt())
            setBackgroundResource(
                if (isMe) com.hermesandroid.bridge.R.drawable.bg_chip
                else com.hermesandroid.bridge.R.drawable.bg_input)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = if (isMe) 0 else dp(48)
            marginStart = if (isMe) dp(48) else 0
            topMargin = dp(6)
            gravity = if (isMe) android.view.Gravity.END else android.view.Gravity.START
        }
        messages.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || gatewayUrl.isBlank()) return
        input.setText("")
        addBubble(text, true)

        if (chatPass.isBlank()) { showSettings(); return }

        status.text = "Verbinde…"
        val loginErr = HermesChatClient.login(this, chatUser, chatPass)
        if (loginErr != null) {
            status.text = loginErr
            if (loginErr.startsWith("Login")) showSettings()
            return
        }
        val wsUrl = HermesChatClient.wsUrl()
        if (wsUrl == null) {
            status.text = "Kein Ticket erhalten — Gateway erreichbar?"
            return
        }

        var bubble: TextView? = null
        val sb = StringBuilder()
        chatSocket = ChatSocket().also { cs ->
            cs.send(com.hermesandroid.bridge.security.MtlsSupport.decorate(OkHttpClient()), wsUrl, text,
                object : ChatSocket.Listener {
                    override fun onConnected(sessionId: String) { status.text = "denkt nach…" }
                    override fun onDelta(t: String) {
                        sb.append(t)
                        mainThread {
                            (bubble ?: addBubble("", false).also { bubble = it }).text = sb.toString()
                            status.text = "schreibt…"
                        }
                    }
                    override fun onComplete(full: String) {
                        mainThread {
                            status.text = "Bereit"
                            (bubble ?: addBubble(full, false)).text = full
                        }
                    }
                    override fun onError(msg: String) { mainThread { status.text = msg } }
                    override fun onClosed() { mainThread { if (status.text == "schreibt…") status.text = "Bereit" } }
                })
        }
    }

    private fun mainThread(f: () -> Unit) = runOnUiThread(f)

    private fun showSettings() {
        val etUrl = EditText(this).apply { hint = "Gateway-URL (http://ip:9119)"; setText(gatewayUrl) }
        val etUser = EditText(this).apply { hint = "Benutzer"; setText(chatUser) }
        val etPass = EditText(this).apply {
            hint = "Passwort"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(chatPass)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        wrap.addView(etUrl); wrap.addView(etUser); wrap.addView(etPass)

        android.app.AlertDialog.Builder(this)
            .setTitle("Gateway einrichten")
            .setView(wrap)
            .setPositiveButton("Speichern") { _, _ ->
                getSharedPreferences("flipsibridge_chat", MODE_PRIVATE).edit()
                    .putString("gateway_url", etUrl.text.toString().trim())
                    .putString("chat_user", etUser.text.toString().trim())
                    .putString("chat_pass", etPass.text.toString())
                    .apply()
                recreate()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun onDestroy() {
        chatSocket?.close()
        super.onDestroy()
    }
}