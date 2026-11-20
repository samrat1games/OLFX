package org.olfx.launcher

import android.content.Context
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

import java.util.concurrent.Executors

/** Material 3 launcher: Games / Favorites / Hand Tracking / Settings. */
class MainActivity : AppCompatActivity() {
    // Monado is part of this app now.
    private val monadoPkg get() = packageName

    private var all: List<AppEntry> = emptyList()
    private var tab = TAB_GAMES
    private var query = ""
    private lateinit var prefs: Prefs
    private val adapter = Adapter()
    private lateinit var settingsPage: SettingsPage
    private val scanWorker = Executors.newSingleThreadExecutor()
    private var scanGeneration = 0

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var gamesPage: View
    private lateinit var handsPage: View
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var permStatus: TextView
    private lateinit var permButton: MaterialButton

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        C.init(this)
        prefs = Prefs(this)
        tab = b?.getInt(STATE_TAB)?.takeIf { it == TAB_GAMES || it == TAB_FAVORITE || it == TAB_SETTINGS } ?: TAB_GAMES
        query = b?.getString(STATE_QUERY).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            fitsSystemWindows = true
        }
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        gamesPage = buildGamesPage()
        handsPage = buildHandsPage()
        settingsPage = SettingsPage(this, prefs, handsPage)
        content.addView(gamesPage, FrameLayout.LayoutParams(-1, -1))
        content.addView(settingsPage.view, FrameLayout.LayoutParams(-1, -1))

        fab = ExtendedFloatingActionButton(this).apply {
            setText(R.string.add_apk)
            icon = getDrawable(R.drawable.ic_add)
            setOnClickListener { pickApk() }
        }
        content.addView(fab, FrameLayout.LayoutParams(-2, -2).also {
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.setMargins(0, 0, dp(16), dp(16))
        })

        val nav = BottomNavigationView(this).apply {
            menu.add(0, TAB_GAMES, 0, R.string.games).setIcon(R.drawable.ic_games)
            menu.add(0, TAB_FAVORITE, 1, R.string.favorites).setIcon(R.drawable.ic_favorite)
            menu.add(0, TAB_SETTINGS, 3, R.string.settings).setIcon(R.drawable.ic_settings)
            setOnItemSelectedListener { selectTab(it.itemId); true }
            selectedItemId = tab
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        selectTab(tab)
        val permissions = getSharedPreferences("olfx_first_launch", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            !permissions.getBoolean("camera_asked", false)) {
            permissions.edit().putBoolean("camera_asked", true).apply()
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 810)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, tab)
        outState.putString(STATE_QUERY, query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scanGeneration++
        scanWorker.shutdownNow()
        if (::settingsPage.isInitialized) settingsPage.destroy()
        super.onDestroy()
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
        page.addView(head)

        val search = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setBoxCornerRadii(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat())
        }
        val edit = TextInputEditText(search.context).apply {
            setSingleLine()
            setText(query)
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
        col.addView(headerTitle(false).apply { text = getString(R.string.hand_tracking); setPadding(dp(8), 0, 0, dp(16)) })

        fun gap() = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }

        col.addView(card(C.SEC_CONTAINER) {
            addView(switchRow(getString(R.string.hand_tracking), getString(R.string.hands_camera_description), C.ON_SEC_CONTAINER, C.ON_SEC_CONTAINER, prefs.handEnabled) {
                prefs.handEnabled = it
                if (it) HandRuntime.startIfEnabled(this@MainActivity)
            })
        }, gap())

        col.addView(card(C.SURFACE) {
            addView(label(getString(R.string.camera_access), 18f, C.TEXT, true))
            permStatus = label("", 14f, C.MUTED)
            addView(permStatus, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
            permButton = MaterialButton(this@MainActivity).apply {
                text = getString(R.string.allow_camera)
                setOnClickListener { requestCamera() }
            }
            addView(permButton, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(12) })
        }, gap())

        col.addView(card(C.SURFACE) {
            addView(switchRow(getString(R.string.swap_hands), getString(R.string.swap_hands_description), C.MUTED, C.TEXT, prefs.handSwap) { prefs.handSwap = it })
            addView(switchRow(getString(R.string.onnx_hands), getString(R.string.onnx_hands_description), C.MUTED, C.TEXT, prefs.handOnnx) { prefs.handOnnx = it })
        }, gap())

        col.addView(card(C.SURFACE) {
            addView(label(getString(R.string.hand_test_hint), 14f, C.MUTED))
            addView(MaterialButton(this@MainActivity).apply {
                text = getString(R.string.test_hand_tracking)
                setOnClickListener { openHandTest() }
            }, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(12) })
        }, gap())

        return col
    }

    private fun refreshHandStatus() {
        val installed = try { packageManager.getPackageInfo(monadoPkg, 0); true } catch (e: Exception) { false }
        if (!installed) {
            permStatus.setText(R.string.runtime_missing)
            permButton.visibility = View.GONE
            return
        }
        val granted = packageManager.checkPermission(android.Manifest.permission.CAMERA, monadoPkg) == PackageManager.PERMISSION_GRANTED
        permStatus.setText(if (granted) R.string.camera_allowed else R.string.camera_denied)
        permButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openHandTest() {
        try {
            startActivity(Intent().setClassName(packageName, "org.freedesktop.monado.ipc.HandTestActivity"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.hand_test_unavailable), android.widget.Toast.LENGTH_LONG).show()
        }
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
        gamesPage.visibility = if (t == TAB_GAMES || t == TAB_FAVORITE) View.VISIBLE else View.GONE
        settingsPage.view.visibility = if (t == TAB_SETTINGS) View.VISIBLE else View.GONE
        if (t == TAB_GAMES || t == TAB_FAVORITE) fab.show() else fab.hide()
        title.setText(if (t == TAB_FAVORITE) R.string.favorites else R.string.games)
        if (t == TAB_SETTINGS) refreshHandStatus()
        if (t == TAB_SETTINGS) settingsPage.refresh()
        refilter()
    }

    // ---- "+" : pick an APK -> patch it -> install (uninstalling a differently signed original only if needed) ----

    private val reqPick = 31
    private val reqUninstall = 32
    private var readyApk: java.io.File? = null
    private var readyPkg: String? = null
    private var awaitingInstallPermission = false

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
            else android.widget.Toast.makeText(this, getString(R.string.original_not_removed, apk?.name ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun isInstalled(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun onApkPicked(uri: android.net.Uri) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.apk).setMessage(R.string.reading_file).setCancelable(false).show()
        Thread {
            try {
                val tmp = java.io.File(cacheDir, "picked.apk")
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                val info = packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
                    ?: throw ApkPatcher.PatchException(getString(R.string.invalid_apk))
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

    private fun runPatch(src: java.io.File, pkg: String, minSdk: Int, mode: String) {
        // Imported APKs are always optimized once before install; no "install unchanged" path remains.
        if (!OptimizationGate.tryAcquire(pkg)) {
            src.delete()
            fail(IllegalStateException(getString(R.string.patch_already_running)))
            return
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.patch_apk).setMessage(R.string.preparing).setCancelable(false).show()
        Thread {
            try {
                val out = ApkPatcher.patchFile(this, src, pkg, minSdk, mode) { m -> runOnUiThread { dlg.setMessage(m) } }
                src.delete()
                runOnUiThread { dlg.dismiss(); afterPatched(out, pkg) }
            } catch (e: Exception) {
                src.delete()
                runOnUiThread { dlg.dismiss(); fail(e) }
            } finally {
                OptimizationGate.release(pkg)
            }
        }.start()
    }

    /** The patched APK is already built and verified at this point; only now is the original (if any) touched. */
    private fun afterPatched(apk: java.io.File, pkg: String) {
        readyApk = apk; readyPkg = pkg
        if (!isInstalled(pkg)) { install(apk); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.patch_ready)
            .setMessage(getString(R.string.replace_patch_message, apk.name))
            .setPositiveButton(R.string.replace_install) { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).putExtra(Intent.EXTRA_RETURN_RESULT, true), reqUninstall)
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun install(apk: java.io.File) {
        if (!canInstallPackages()) {
            awaitingInstallPermission = true
            readyApk = apk
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.allow_install_resume)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                    } catch (e: Exception) { fail(e) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try { Installer.install(this, apk) } catch (e: Exception) { fail(e) }
    }

    private fun fail(e: Exception) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(e.message ?: e.toString()).setPositiveButton(R.string.ok, null).show()
    }

    override fun onPause() {
        SceneHost.detach()
        super.onPause()
    }

    override fun onResume() {
        window.decorView.post { SceneHost.attach(this) }
        super.onResume()
        if (awaitingInstallPermission && canInstallPackages()) {
            awaitingInstallPermission = false
            readyApk?.let { install(it) }
        }
        if (tab == TAB_SETTINGS) refreshHandStatus()
        if (tab == TAB_SETTINGS) settingsPage.refresh()
        val generation = ++scanGeneration
        scanWorker.execute {
            val apps = AppScanner.scan(this)
            runOnUiThread {
                if (generation == scanGeneration && !isDestroyed) {
                    all = apps
                    refilter()
                }
            }
        }
    }

    private fun refilter() {
        val favs = prefs.favs()
        val list = all.filter {
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) &&
                (tab != TAB_FAVORITE || it.pkg in favs)
        }.sortedWith(compareByDescending<AppEntry> { it.pkg in favs }.thenBy { it.label.lowercase() })
        subtitle.text = resources.getQuantityString(if (tab == TAB_FAVORITE) R.plurals.favorites_count else R.plurals.apps_count, list.size, list.size)
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
        const val TAB_SETTINGS = 3
        const val STATE_TAB = "tab"
        const val STATE_QUERY = "query"
    }
}
