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
    private lateinit var patchBtn: TextView
    private val reqUninstall = 77

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
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
        patchBtn = button("", false) { pickPatch() }
        col.addView(patchBtn, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
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
        val apk = ApkPatch.marker(this, entry.pkg)
        patchBtn.text = if (apk != null) "Патч APK: ${Profiles.title(apk)}" else "Патч: ${Profiles.title(prefs.profile(entry.pkg))}"
    }

    /** APK patch marker wins; otherwise the profile chosen in OLFX. */
    private fun effectiveProfile(): String {
        val m = ApkPatch.marker(this, entry.pkg)
        return if (m == Profiles.OPT || m == Profiles.IMMERSIVE) m else prefs.profile(entry.pkg)
    }

    private fun play() {
        if (effectiveProfile() == Profiles.NONE) {
            AlertDialog.Builder(this)
                .setTitle("Игра не пропатчена")
                .setMessage("Без патча игра может сильно грузить память и лагать. Точно хотите запустить?")
                .setPositiveButton("Да") { _, _ -> launchGame() }
                .setNegativeButton("Нет", null)
                .show()
        } else {
            launchGame()
        }
    }

    private fun chooseApkMode() {
        val modes = listOf(Profiles.OPT, Profiles.IMMERSIVE)
        AlertDialog.Builder(this)
            .setTitle("Модифицировать APK: ${entry.label}")
            .setItems(arrayOf("Оптимизация — меньше памяти и нагрузки", "Нормальное погружение — ближе к оригиналу")) { _, i ->
                runApkPatch(modes[i])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runApkPatch(mode: String) {
        val dlg = AlertDialog.Builder(this).setTitle("Патч APK").setMessage("Подготовка…").setCancelable(false).show()
        Thread {
            try {
                val f = ApkPatcher.patch(this, entry.pkg, mode) { msg -> runOnUiThread { dlg.setMessage(msg) } }
                runOnUiThread { dlg.dismiss(); confirmInstall(f, mode) }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(this).setTitle("Не удалось").setMessage(e.message ?: e.toString()).setPositiveButton("Ок", null).show()
                }
            }
        }.start()
    }

    private var pendingApk: java.io.File? = null

    private fun confirmInstall(apk: java.io.File, mode: String) {
        pendingApk = apk
        AlertDialog.Builder(this)
            .setTitle("APK готов (${Profiles.title(mode)})")
            .setMessage(
                "Файл: ${apk.absolutePath}\n\nПодпись изменилась, поэтому оригинал нужно удалить: " +
                    "сохранения внутри игры и её OBB-файлы будут стёрты. Удалить оригинал и установить патч?"
            )
            .setPositiveButton("Да, установить") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.pkg}")).putExtra(Intent.EXTRA_RETURN_RESULT, true),
                    reqUninstall,
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqUninstall && resultCode == RESULT_OK) {
            pendingApk?.let {
                try { Installer.install(this, it) } catch (e: Exception) {
                    Toast.makeText(this, "Установка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pickPatch() {
        val ids = listOf(Profiles.OPT, Profiles.IMMERSIVE, Profiles.NONE)
        val labels = arrayOf(
            "Оптимизация — меньше памяти, плавная голова (${Profiles.scale(Profiles.OPT)}%)",
            "Нормальное погружение — баланс (${Profiles.scale(Profiles.IMMERSIVE)}%)",
            "Снять патч",
        )
        AlertDialog.Builder(this)
            .setTitle("Патч для ${entry.label}")
            .setSingleChoiceItems(labels, ids.indexOf(prefs.profile(entry.pkg)).coerceAtLeast(0)) { d, i ->
                prefs.setProfile(entry.pkg, ids[i]); updateButtons(); d.dismiss()
            }
            .show()
    }

    private fun launchGame() {
        prefs.currentScale = Profiles.scale(effectiveProfile())
        if (Runtimes.list(this).isEmpty()) {
            Toast.makeText(this, "No OpenXR runtime installed (Monado / OculusXR / Pico) — the game may not start in VR", Toast.LENGTH_LONG).show()
        }
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
