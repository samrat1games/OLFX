package org.olfx.launcher

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private var all: List<AppEntry> = emptyList()
    private var filter = "all"
    private var query = ""
    private lateinit var prefs: Prefs
    private val adapter = Adapter()
    private val chips = LinkedHashMap<String, TextView>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "OLFX"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C.TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "+"; textSize = 26f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = round(C.ACCENT, dp(20).toFloat())
            setOnClickListener { pickApk() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.rightMargin = dp(8) })
        header.addView(TextView(this).apply {
            text = "Запись"; textSize = 14f; setTextColor(C.ACCENT); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { toggleRecordMenu() }
        })
        header.addView(TextView(this).apply {
            text = "Runtimes"; textSize = 14f; setTextColor(C.ACCENT); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, RuntimeActivity::class.java)) }
        })
        root.addView(header)

        root.addView(EditText(this).apply {
            hint = "Search"; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 16f
            setSingleLine(); setPadding(dp(16), dp(10), dp(16), dp(10))
            background = round(C.SURFACE, dp(12).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); refilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            })
        }, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(16), dp(8), dp(16), dp(8)) })

        val chipRow = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        listOf("all" to "All", "fav" to "★ Favourites", "xr" to "OpenXR", "quest" to "Meta Quest", "pico" to "Pico")
            .forEach { (k, t) ->
                val tv = TextView(this).apply {
                    text = t; textSize = 14f; setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { filter = k; refilter() }
                }
                chips[k] = tv
                chipRow.addView(tv, LinearLayout.LayoutParams(-2, -2).also { it.setMargins(dp(4), 0, dp(4), 0) })
            }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refilter()
    }

    private fun toggleRecordMenu() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "Разрешите OLFX показываться поверх других приложений", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        startForegroundService(Intent(this, MenuService::class.java))
        android.widget.Toast.makeText(this, "Меню записи включено: ‹› слева на экране", android.widget.Toast.LENGTH_LONG).show()
    }

    // ---- "+" : pick an APK -> patch it -> install (uninstalling a differently signed original only if needed) ----

    private val reqPick = 31
    private val reqUninstall = 32
    private var readyApk: java.io.File? = null
    private var readyPkg: String? = null

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPick && resultCode == RESULT_OK) {
            data?.data?.let { onApkPicked(it) }
        } else if (requestCode == reqUninstall) {
            val apk = readyApk
            if (apk != null && readyPkg?.let { !isInstalled(it) } == true) install(apk)
            else android.widget.Toast.makeText(this, "Оригинал не удалён — установка отменена, APK сохранён: ${apk?.name}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun isInstalled(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun onApkPicked(uri: android.net.Uri) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("APK").setMessage("Чтение файла…").setCancelable(false).show()
        Thread {
            try {
                val tmp = java.io.File(cacheDir, "picked.apk")
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                val info = packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                    ?: throw ApkPatcher.PatchException("Это не APK (для .apks / .xapk / .apkm пока нет поддержки)")
                info.applicationInfo?.sourceDir = tmp.absolutePath
                val pkg = info.packageName
                val label = info.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg
                val minSdk = info.applicationInfo?.minSdkVersion ?: 21
                runOnUiThread { dlg.dismiss(); chooseMode(tmp, pkg, label, minSdk) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); fail(e) }
            }
        }.start()
    }

    private fun chooseMode(src: java.io.File, pkg: String, label: String, minSdk: Int) {
        val modes = listOf(Profiles.OPT, Profiles.IMMERSIVE)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Патч: $label")
            .setItems(arrayOf("Оптимизация — меньше памяти и нагрузки", "Нормальное погружение — ближе к оригиналу")) { _, i ->
                runPatch(src, pkg, minSdk, modes[i])
            }
            .setNegativeButton("Отмена") { _, _ -> src.delete() }
            .setOnCancelListener { src.delete() }
            .show()
    }

    private fun runPatch(src: java.io.File, pkg: String, minSdk: Int, mode: String) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Патч APK").setMessage("Подготовка…").setCancelable(false).show()
        Thread {
            try {
                val out = ApkPatcher.patchFile(this, src, pkg, minSdk, mode) { m -> runOnUiThread { dlg.setMessage(m) } }
                src.delete()
                runOnUiThread { dlg.dismiss(); afterPatched(out, pkg) }
            } catch (e: Exception) {
                src.delete()
                runOnUiThread { dlg.dismiss(); fail(e) }
            }
        }.start()
    }

    /** The patched APK is already built and verified at this point; only now is the original (if any) touched. */
    private fun afterPatched(apk: java.io.File, pkg: String) {
        readyApk = apk; readyPkg = pkg
        if (!isInstalled(pkg)) { install(apk); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Патч готов")
            .setMessage("Патч создан и лежит в ${apk.name}. Игра уже стоит с другой подписью, поэтому оригинал нужно удалить " +
                "(сохранения внутри игры и OBB-файлы пропадут). Удалить оригинал и установить патч?")
            .setPositiveButton("Да, заменить") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).putExtra(Intent.EXTRA_RETURN_RESULT, true), reqUninstall)
            }
            .setNegativeButton("Не сейчас", null)
            .show()
    }

    private fun install(apk: java.io.File) {
        try { Installer.install(this, apk) } catch (e: Exception) { fail(e) }
    }

    private fun fail(e: Exception) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Не удалось").setMessage(e.message ?: e.toString()).setPositiveButton("Ок", null).show()
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val apps = AppScanner.scan(this)
            runOnUiThread { all = apps; refilter() }
        }.start()
    }

    private fun refilter() {
        chips.forEach { (k, tv) ->
            val on = k == filter
            tv.setTextColor(if (on) C.TEXT else C.MUTED)
            tv.background = round(if (on) C.ACCENT else C.SURFACE, dp(18).toFloat())
        }
        val favs = prefs.favs()
        val list = all.filter {
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) &&
                when (filter) {
                    "fav" -> it.pkg in favs
                    "xr" -> it.openXr
                    "quest" -> it.quest
                    "pico" -> it.pico
                    else -> true
                }
        }.sortedWith(compareByDescending<AppEntry> { it.pkg in favs }.thenBy { it.label.lowercase() })
        adapter.submit(list)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<AppEntry> = emptyList()
        fun submit(l: List<AppEntry>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(p.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = round(C.SURFACE, dp(16).toFloat())
                layoutParams = RecyclerView.LayoutParams(-1, -2).also { it.setMargins(0, dp(5), 0, dp(5)) }
            }
            row.addView(ImageView(p.context).apply { id = 1 }, LinearLayout.LayoutParams(dp(56), dp(56)))
            val col = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(p.context).apply {
                id = 2; textSize = 18f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD; setSingleLine()
            })
            col.addView(LinearLayout(p.context).apply { id = 3 })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(14) })
            row.addView(TextView(p.context).apply { id = 4; textSize = 22f; setTextColor(0xFFFFC857.toInt()) })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val e = items[i]
            val v = h.itemView
            v.findViewById<ImageView>(1).setImageDrawable(e.icon)
            v.findViewById<TextView>(2).text = e.label
            v.findViewById<LinearLayout>(3).apply {
                removeAllViews()
                badgesFor(e).forEach {
                    addView(it, LinearLayout.LayoutParams(-2, -2).also { lp -> lp.topMargin = dp(4); lp.rightMargin = dp(6) })
                }
            }
            v.findViewById<TextView>(4).text = if (prefs.isFav(e.pkg)) "★" else ""
            v.setOnClickListener {
                startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("pkg", e.pkg))
            }
        }
    }
}
