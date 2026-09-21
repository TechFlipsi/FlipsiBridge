package com.hermesandroid.bridge.security

import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.hermesandroid.bridge.R

/**
 * FlipsiBridge Capability-Schalter-UI.
 *
 * Baut dynamisch eine Zeile pro Capability in die Berechtigungen-Karte:
 *   - "screen", "interaction" etc. = was der Agent darf
 *   - Defaults sind ALLE AUS — Sir schaltet bewusst frei
 *   - Änderungen greifen sofort (Dispatcher fragt bei jedem Befehl ab)
 */
object CapabilitySwitches {

    fun bind(parent: LinearLayout, onChanged: () -> Unit) {
        val ctx = parent.context
        val prefs = ctx.getSharedPreferences("flipsibridge_caps", android.content.Context.MODE_PRIVATE)

        val title = TextView(ctx).apply {
            setText(R.string.caps_section_title)
            setTextAppearance(R.style.CardTitle)
            setPadding(0, dp(8), 0, dp(6))
        }
        parent.addView(title)

        val hint = TextView(ctx).apply {
            setText(R.string.caps_section_hint)
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        }
        parent.addView(hint)

        for ((cap, label) in CapabilityGate.userFacing) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tv = TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(ctx.getColor(R.color.text_primary))
            }
            col.addView(tv)
            row.addView(col)
            val sw = Switch(ctx)
            sw.isChecked = CapabilityGate.isEnabled(cap)
            sw.setOnCheckedChangeListener { _, checked ->
                CapabilityGate.setEnabled(cap, checked)
                onChanged()
            }
            row.addView(sw)
            parent.addView(row)
        }
    }

    private fun dp(v: Int) = (v * 8 * 0.5f).toInt() // ~ density-approx; UI ist statisch genug
}