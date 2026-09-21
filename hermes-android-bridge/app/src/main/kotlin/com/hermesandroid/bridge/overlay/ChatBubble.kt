package com.hermesandroid.bridge.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * FlipsiBridge Chat-Bubble (Phase 3, Gemini-Stil).
 *
 * Kleine orange Bubble rechts oben, immer über allen Apps (wenn Overlay erlaubt).
 * Antippen öffnet den Chat. Ersetzt den reinen Status-Punkt des Originals.
 */
object ChatBubble {

    private var bubble: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (bubble != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 48
        }

        val tv = TextView(context).apply {
            text = " ✦ "
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#ED7931"))
            alpha = 0.85f
            setOnClickListener {
                val i = Intent(context, com.hermesandroid.bridge.chat.ChatActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
            setPadding(14, 6, 14, 6)
        }
        try {
            wm.addView(tv, params)
            bubble = tv
        } catch (_: Exception) {
            // Overlay-Permission fehlt — still auslassen
        }
    }

    fun hide(context: Context) {
        bubble?.let {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
            bubble = null
        }
    }
}