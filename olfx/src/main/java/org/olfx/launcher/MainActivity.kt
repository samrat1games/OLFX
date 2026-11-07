package org.olfx.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.freedesktop.monado.openxr_runtime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Material 3 launcher: bottom bar with Games / Favorite / Hand Tracking. */
class MainActivity : AppCompatActivity() {
    // Monado is part of this app now.
    private val monadoPkg get() = packageName

    private var all: List<AppEntry> = emptyList()
    private var tab = TAB_GAMES
    private var query = ""
    private lateinit var prefs: Prefs
    private val adapter = Adapter()

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var gamesPage: View
    private lateinit var handsPage: View
    private lateinit var storePage: View
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var permStatus: TextView
    private lateinit var permButton: MaterialButton

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            fitsSystemWindows = true
        }
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        gamesPage = buildGamesPage()
        handsPage = buildHandsPage().also { it.visibility = View.GONE }
        content.addView(gamesPage, FrameLayout.LayoutParams(-1, -1))
        content.addView(handsPage, FrameLayout.LayoutParams(-1, -1))
        storePage = buildStorePage().also { it.visibility = View.GONE }
        content.addView(storePage, FrameLayout.LayoutParams(-1, -1))

        fab = ExtendedFloatingActionButton(this).apply {
            text = "Add APK"
            icon = getDrawable(R.drawable.ic_add)
            setOnClickListener { pickApk() }
        }
        content.addView(fab, FrameLayout.LayoutParams(-2, -2).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(16), dp(16))
        })

        val nav = BottomNavigationView(this).apply {
            menu.add(0, TAB_GAMES, 0, "Games").setIcon(R.drawable.ic_games)
            menu.add(0, TAB_FAVORITE, 1, "Favorite").setIcon(R.drawable.ic_favorite)
            menu.add(0, TAB_HANDS, 2, "Hand Tracking").setIcon(R.drawable.ic_hand)
            menu.add(0, TAB_STORE, 3, "Store").setIcon(R.drawable.ic_store)
            setOnItemSelectedListener { selectTab(it.itemId); true }
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        selectTab(TAB_GAMES)
    }

    // ---- pages ----------------------------------------------------------------------------------------------------

    private fun headerTitle(sub: Boolean): TextView = TextView(this).apply {
        setTextColor(if (sub) C.MUTED else C.TEXT)
        textSize = if (sub) 15f else 34f
        if (!sub) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun buildGamesPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(24), dp(24), dp(12), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = headerTitle(false)
        subtitle = headerTitle(true)
        titles.addView(title)
        titles.addView(subtitle)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        fun textButton(t: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = t
            setOnClickListener { onClick() }
        }
        head.addView(textButton("Запись") { toggleRecordMenu() })
        page.addView(head)

        val search = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Search"
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setBoxCornerRadii(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat())
        }
        val edit = TextInputEditText(search.context).apply {
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); refilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            })
        }
        search.addView(edit)
        page.addView(search, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(16), dp(8), dp(16), dp(8)) })

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(96))
        }
        page.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun card(bg: Int, build: LinearLayout.() -> Unit): MaterialCardView {
        val c = MaterialCardView(this).apply {
            setCardBackgroundColor(bg)
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            build()
        }
        c.addView(inner, ViewGroup.LayoutParams(-1, -2))
        return c
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun switchRow(titleText: String, sub: String, color: Int, onColor: Int, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(titleText, 18f, onColor, true))
            col.addView(label(sub, 14f, color))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialSwitch(this@MainActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    private fun buildHandsPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        col.addView(headerTitle(false).apply { text = "Hand Tracking"; setPadding(dp(8), 0, 0, 0) })
        col.addView(headerTitle(true).apply { text = "Use your own hands in VR games"; setPadding(dp(8), 0, 0, dp(16)) })

        fun gap() = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }

        col.addView(card(C.SEC_CONTAINER) {
            addView(switchRow("Hand tracking", "Rear camera, no controllers needed", C.ON_SEC_CONTAINER, C.ON_SEC_CONTAINER, prefs.handEnabled) { prefs.handEnabled = it })
        }, gap())

        col.addView(card(C.SURFACE) {
            addView(label("Camera access for Monado", 18f, C.TEXT, true))
            permStatus = label("", 14f, C.MUTED)
            addView(permStatus, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
            permButton = MaterialButton(this@MainActivity).apply {
                text = "Allow camera"
                setOnClickListener { requestCamera() }
            }
            addView(permButton, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(12) })
        }, gap())

        col.addView(card(C.SURFACE) {
            addView(switchRow("Swap left and right", "Turn on if your left hand shows up as the right one", C.MUTED, C.TEXT, prefs.handSwap) { prefs.handSwap = it })
        }, gap())

        col.addView(card(C.TER_CONTAINER) {
            addView(label("Tips", 18f, C.ON_TER_CONTAINER, true))
            addView(label(
                "Put the phone in the headset and hold your hands in front of the rear camera, 30 to 60 cm away, in good light. " +
                    "An open palm is picked up fastest. Restart the game after changing a setting.",
                14f, C.ON_TER_CONTAINER,
            ), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
        }, gap())

        return ScrollView(this).apply { addView(col) }
    }

    /** Android XR titles (Google Play only): find them, then Add APK patches them for this phone. */
    private fun buildStorePage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(96))
        }
        col.addView(headerTitle(false).apply { text = "Store"; setPadding(dp(8), 0, 0, 0) })
        col.addView(headerTitle(true).apply { text = "Android XR games"; setPadding(dp(8), 0, 0, dp(16)) })
        fun gap() = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }

        col.addView(card(C.TER_CONTAINER) {
            addView(label("How it works", 18f, C.ON_TER_CONTAINER, true))
            addView(label(
                "These games are sold in Google Play for Android XR headsets. Find one, get its APK (for example with " +
                    "Aurora Store), then press Add APK: OLFX patches it for your phone and installs it.",
                14f, C.ON_TER_CONTAINER,
            ), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
        }, gap())

        val titles = listOf("Inside [JOB]", "Enigmo", "Arizona Sunshine Remake", "Arizona Sunshine 2", "Vacation Simulator", "Oh My Galaxy!", "Real VR Fishing")
        for (t in titles) {
            col.addView(card(C.SURFACE) {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(t, 18f, C.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Google Play"
                    setOnClickListener {
                        val q = android.net.Uri.encode(t)
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/search?q=$q&c=apps")))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@MainActivity, "No app to open the link", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }, gap())
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun refreshHandStatus() {
        val installed = try { packageManager.getPackageInfo(monadoPkg, 0); true } catch (e: Exception) { false }
        if (!installed) {
            permStatus.text = "Monado is not installed"
            permButton.visibility = View.GONE
            return
        }
        val granted = packageManager.checkPermission(android.Manifest.permission.CAMERA, monadoPkg) == PackageManager.PERMISSION_GRANTED
        permStatus.text = if (granted) "Allowed. Hands work in games that ask for hand tracking." else "Not allowed yet: games will not see your hands."
        permButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestCamera() {
        try {
            startActivity(Intent().setClassName(monadoPkg, "org.freedesktop.monado.ipc.CameraPermissionActivity"))
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$monadoPkg")))
        }
    }

    private fun selectTab(t: Int) {
        tab = t
        val hands = t == TAB_HANDS
        val store = t == TAB_STORE
        gamesPage.visibility = if (hands || store) View.GONE else View.VISIBLE
        handsPage.visibility = if (hands) View.VISIBLE else View.GONE
        storePage.visibility = if (store) View.VISIBLE else View.GONE
        if (hands) fab.hide() else fab.show()
        title.text = if (t == TAB_FAVORITE) "Favorite" else "Games"
        if (hands) refreshHandStatus()
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
                runOnUiThread { dlg.dismiss(); runPatch(tmp, pkg, minSdk, Profiles.OPT) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); fail(e) }
            }
        }.start()
    }

    private fun chooseMode(src: java.io.File, pkg: String, label: String, minSdk: Int) {
        val modes = listOf(Profiles.OPT, Profiles.IMMERSIVE)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("APK: $label")
            .setItems(arrayOf(
                "Патч: оптимизация — меньше памяти и нагрузки",
                "Патч: нормальное погружение — ближе к оригиналу",
                "Установить как есть, без патча",
            )) { _, i ->
                if (i < 2) runPatch(src, pkg, minSdk, modes[i])
                else {
                    if (isInstalled(pkg)) {
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Уже установлено")
                            .setMessage("$label уже стоит. Для обновления подойдёт тот же ключ подписи, иначе установка откажет.")
                            .setPositiveButton("Установить") { _, _ -> install(src) }
                            .setNegativeButton("Отмена") { _, _ -> src.delete() }.show()
                    } else install(src)
                }
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
        if (!packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(this, "Разрешите OLFX установку приложений и повторите выбор APK", android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            return
        }
        try { Installer.install(this, apk) } catch (e: Exception) { fail(e) }
    }

    private fun fail(e: Exception) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Не удалось").setMessage(e.message ?: e.toString()).setPositiveButton("Ок", null).show()
    }

    override fun onResume() {
        super.onResume()
        if (tab == TAB_HANDS) refreshHandStatus()
        Thread {
            val apps = AppScanner.scan(this)
            runOnUiThread { all = apps; refilter() }
        }.start()
    }

    private fun refilter() {
        val favs = prefs.favs()
        val list = all.filter {
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) &&
                (tab != TAB_FAVORITE || it.pkg in favs)
        }.sortedWith(compareByDescending<AppEntry> { it.pkg in favs }.thenBy { it.label.lowercase() })
        subtitle.text = "${list.size} " + if (tab == TAB_FAVORITE) "favorites" else "apps"
        adapter.submit(list)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<AppEntry> = emptyList()
        fun submit(l: List<AppEntry>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val card = MaterialCardView(p.context).apply {
                setCardBackgroundColor(C.SURFACE)
                radius = dp(28).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                layoutParams = RecyclerView.LayoutParams(-1, -2).also { it.setMargins(0, dp(5), 0, dp(5)) }
            }
            val row = LinearLayout(p.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            row.addView(ShapeableImageView(p.context).apply {
                id = 1
                shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(16).toFloat()).build()
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            val col = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(p.context).apply {
                id = 2; textSize = 18f; setTextColor(C.TEXT); setSingleLine()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(LinearLayout(p.context).apply { id = 3 })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(16) })
            row.addView(TextView(p.context).apply { id = 4; textSize = 22f; setTextColor(C.ACCENT) })
            card.addView(row)
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val e = items[i]
            val v = h.itemView
            v.findViewById<android.widget.ImageView>(1).setImageDrawable(e.icon)
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

    private companion object {
        const val TAB_GAMES = 0
        const val TAB_FAVORITE = 1
        const val TAB_HANDS = 2
        const val TAB_STORE = 3
    }
}
