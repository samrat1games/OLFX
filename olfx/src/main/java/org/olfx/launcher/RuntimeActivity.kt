package org.olfx.launcher

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shows installed OpenXR runtimes and lets the user choose the global default served by the broker. */
class RuntimeActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout
    private lateinit var prefs: Prefs

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        prefs = Prefs(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = "OpenXR runtimes"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TEXT)
        })
        col.addView(TextView(this).apply {
            text = "OLFX is the OpenXR runtime broker on this device. Games ask OLFX which runtime to load: " +
                "Meta Quest games get the OculusXR runtime, Pico games get the Pico runtime, everything else " +
                "gets the default below. Per-game overrides are in each game's page."
            textSize = 14f; setTextColor(C.MUTED); setPadding(0, dp(8), 0, dp(16))
        })
        val rts = Runtimes.list(this)
        val cur = prefs.defaultRuntime
        fun option(id: String, title: String, sub: String) {
            val on = cur == id
            col.addView(TextView(this).apply {
                text = (if (on) "● " else "○ ") + title + "\n" + sub
                textSize = 16f; setTextColor(C.TEXT)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = round(if (on) C.ACCENT else C.SURFACE, dp(14).toFloat())
                setOnClickListener { prefs.defaultRuntime = id; render() }
            }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        }
        option("auto", "Auto", "Monado, with platform routing for Quest / Pico games")
        rts.forEach { option(it.pkg, it.label, "${it.kind.title} · ${it.pkg}") }
        if (rts.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "\nNo OpenXR runtime found. Install the Monado runtime APK (built from this repo: " +
                    "src/xrt/targets/openxr_android) and, for Meta Quest / Pico games, the vendor runtime."
                textSize = 14f; setTextColor(C.MUTED)
            })
        }
    }
}
