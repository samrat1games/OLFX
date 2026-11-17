package org.olfx.launcher

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel

/** One game: Play, Add to favourites, App info. Nothing else. */
@Suppress("DEPRECATION")
class DetailActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var entry: AppEntry
    private lateinit var favBtn: MaterialButton
    private val reqUninstall = 77
    private var pendingApk: java.io.File? = null

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
            setPadding(dp(24), dp(32), dp(24), dp(28))
        }
        col.addView(ShapeableImageView(this).apply {
            setImageDrawable(e.icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(40).toFloat()).build()
        }, LinearLayout.LayoutParams(dp(160), dp(160)))

        col.addView(TextView(this).apply {
            text = e.label
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(C.TEXT)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(20) })

        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            badgesFor(e).forEach { addView(it, LinearLayout.LayoutParams(-2, -2).also { lp -> lp.setMargins(dp(4), dp(10), dp(4), 0) }) }
        }, LinearLayout.LayoutParams(-1, -2))

        fun action(text: String, filled: Boolean, onClick: () -> Unit): MaterialButton =
            MaterialButton(this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                textSize = 17f
                setOnClickListener { onClick() }
            }

        val playBtn = action("Play", true) { play() }
        playBtn.setOnLongClickListener { runApkPatch(); true } // long press: patch again (updates the VR patch)
        col.addView(playBtn,
            LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(32) })
        favBtn = action("", false) { prefs.toggleFav(e.pkg); updateButtons() }
        col.addView(favBtn, LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(12) })
        col.addView(action("App Info", false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        }, LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(12) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            fitsSystemWindows = true
            addView(col)
        })
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        if (::entry.isInitialized && !isPackagePresent()) finish()
    }

    private fun isPackagePresent() = try { packageManager.getPackageInfo(entry.pkg, 0); true } catch (e: Exception) { false }

    private fun updateButtons() {
        favBtn.text = if (prefs.isFav(entry.pkg)) "★ Remove from favourites" else "☆ Add to favourites"
    }

    private fun isPatched() = ApkPatch.marker(this, entry.pkg) != null

    private fun play() {
        val useOculus = entry.quest && Runtimes.resolve(this, entry.pkg)?.kind == Kind.OCULUS
        if (!isPatched() && !useOculus && (entry.quest || entry.pico || entry.openXr)) {
            AlertDialog.Builder(this)
                .setTitle("Нужен патч для VR")
                .setMessage("Без патча игра не увидит VR: будет обычный 2D-экран или вылет. Пропатчить (режим «Оптимизация») " +
                    "и переустановить? Сохранения внутри игры пропадут.")
                .setPositiveButton("Пропатчить") { _, _ -> runApkPatch() }
                .setNeutralButton("Всё равно запустить") { _, _ -> launchGame() }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            launchGame()
        }
    }

    /** Always the optimisation profile: no questions. */
    private fun runApkPatch() {
        val dlg = AlertDialog.Builder(this).setTitle("Патч для VR").setMessage("Подготовка…").setCancelable(false).show()
        Thread {
            try {
                val f = ApkPatcher.patch(this, entry.pkg, Profiles.OPT) { msg -> runOnUiThread { dlg.setMessage(msg) } }
                runOnUiThread { dlg.dismiss(); confirmInstall(f) }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(this).setTitle("Не удалось").setMessage(e.message ?: e.toString()).setPositiveButton("Ок", null).show()
                }
            }
        }.start()
    }

    private fun confirmInstall(apk: java.io.File) {
        pendingApk = apk
        AlertDialog.Builder(this)
            .setTitle("Патч готов")
            .setMessage("Подпись изменилась, поэтому оригинал нужно удалить: сохранения внутри игры и её OBB-файлы будут стёрты. " +
                "Удалить оригинал и установить патч?")
            .setPositiveButton("Да, установить") { _, _ ->
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
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqUninstall && resultCode == RESULT_OK) {
            pendingApk?.let {
                if (!packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(this, "Разрешите OLFX установку приложений и повторите", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    return
                }
                try { Installer.install(this, it) } catch (e: Exception) {
                    Toast.makeText(this, "Установка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchGame() {
        prefs.currentScale = Profiles.scale(ApkPatch.marker(this, entry.pkg) ?: Profiles.NONE)
        val i = AppScanner.playIntent(this, entry)
        if (i == null) { Toast.makeText(this, "No launchable activity", Toast.LENGTH_SHORT).show(); return }
        try { startActivity(i) } catch (ex: Exception) {
            Toast.makeText(this, "Launch failed: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }
}
