package org.olfx.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Date

@Suppress("DEPRECATION")
class DetailActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var entry: AppEntry
    private lateinit var favBtn: TextView
    private lateinit var rtBtn: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val pkg = intent.getStringExtra("pkg") ?: return finish()
        entry = AppScanner.entry(this, pkg, false) ?: return finish()
        val e = entry

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        // Icon: 40% of the screen height.
        col.addView(ImageView(this).apply {
            setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.4f).toInt()))

        col.addView(TextView(this).apply {
            text = e.label; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(C.TEXT); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(16) })
        col.addView(TextView(this).apply {
            text = e.pkg; textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(C.MUTED); gravity = Gravity.CENTER
            setOnClickListener { copy(e.pkg) }
        })
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            badgesFor(e).forEach { addView(it, LinearLayout.LayoutParams(-2, -2).also { lp -> lp.setMargins(dp(4), dp(10), dp(4), 0) }) }
        }, LinearLayout.LayoutParams(-1, -2))

        col.addView(button("Play", true) { play() }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(20) })
        col.addView(button("Activities", false) { showActivities() }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })

        // Extras
        favBtn = button("", false) { prefs.toggleFav(e.pkg); updateButtons() }
        rtBtn = button("", false) { pickRuntime() }
        col.addView(rtBtn, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        col.addView(favBtn, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })

        col.addView(infoCard(), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(20) })

        val row = LinearLayout(this)
        row.addView(button("App info", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(5) })
        row.addView(button("Uninstall", false) {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(5) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(14) })

        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        if (::entry.isInitialized && !isPackagePresent()) finish()
    }

    private fun isPackagePresent() = try { packageManager.getPackageInfo(entry.pkg, 0); true } catch (e: Exception) { false }

    private fun updateButtons() {
        favBtn.text = if (prefs.isFav(entry.pkg)) "★ Remove from favourites" else "☆ Add to favourites"
        val rt = prefs.appRuntime(entry.pkg)
        val name = if (rt == "auto") "Auto" else Runtimes.list(this).firstOrNull { it.pkg == rt }?.label ?: rt
        rtBtn.text = "OpenXR runtime: $name"
    }

    private fun play() {
        val i = AppScanner.playIntent(this, entry)
        if (i == null) { Toast.makeText(this, "No launchable activity", Toast.LENGTH_SHORT).show(); return }
        try { startActivity(i) } catch (ex: Exception) {
            Toast.makeText(this, "Launch failed: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showActivities() {
        val acts = try {
            packageManager.getPackageInfo(entry.pkg, PackageManager.GET_ACTIVITIES).activities?.toList() ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (acts.isEmpty()) { Toast.makeText(this, "No activities", Toast.LENGTH_SHORT).show(); return }
        val names = acts.map { (if (it.exported) "▶ " else "🔒 ") + it.name.removePrefix(entry.pkg) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Activities (${acts.size})")
            .setItems(names) { _, i ->
                val a = acts[i]
                if (!a.exported) { copy(a.name); Toast.makeText(this, "Not exported — name copied", Toast.LENGTH_SHORT).show(); return@setItems }
                try {
                    startActivity(Intent().setClassName(entry.pkg, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (ex: Exception) {
                    Toast.makeText(this, ex.message ?: "Failed", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickRuntime() {
        val rts = Runtimes.list(this)
        val ids = listOf("auto") + rts.map { it.pkg }
        val labels = (listOf("Auto (by app platform)") + rts.map { "${it.label} · ${it.kind.title}" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("OpenXR runtime for ${entry.label}")
            .setSingleChoiceItems(labels, ids.indexOf(prefs.appRuntime(entry.pkg)).coerceAtLeast(0)) { d, i ->
                prefs.setAppRuntime(entry.pkg, ids[i]); updateButtons(); d.dismiss()
            }
            .show()
    }

    private fun infoCard(): TextView {
        val pi = packageManager.getPackageInfo(entry.pkg, PackageManager.GET_PERMISSIONS)
        val ai = pi.applicationInfo
        val size = ai?.sourceDir?.let { Formatter.formatShortFileSize(this, File(it).length()) } ?: "?"
        val lines = listOf(
            "Version: ${pi.versionName ?: "?"} (${pi.longVersionCode})",
            "Target SDK: ${ai?.targetSdkVersion ?: "?"}",
            "APK size: $size",
            "Installed: ${DateFormat.getMediumDateFormat(this).format(Date(pi.firstInstallTime))}",
            "Permissions: ${pi.requestedPermissions?.size ?: 0}",
        )
        return TextView(this).apply {
            text = lines.joinToString("\n"); textSize = 14f; setTextColor(C.MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(C.SURFACE, dp(14).toFloat())
        }
    }

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("olfx", s))
    }
}
