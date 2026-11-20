package org.olfx.launcher

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import org.freedesktop.monado.openxr_runtime.R

/** One game: Play, Add to favourites, App info. Nothing else. */
@Suppress("DEPRECATION")
class DetailActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var entry: AppEntry
    private lateinit var favBtn: MaterialButton
    private lateinit var runtimeBtn: MaterialButton
    private lateinit var runtimeStatus: TextView
    private val reqUninstall = 77
    private var pendingApk: java.io.File? = null
    private var awaitingInstallPermission = false
    private var patching = false
    private var launchAnimation: LaunchAnimation.Handle? = null
    private var pendingCameraLaunch = false
    private val reqCameraForLaunch = 78
    private lateinit var playBtn: MaterialButton

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

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

        playBtn = action(getString(R.string.play), true) { play() }
        playBtn.setOnLongClickListener { runApkPatch(); true } // long press: patch again (updates the VR patch)
        col.addView(playBtn,
            LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(32) })
        favBtn = action("", false) { prefs.toggleFav(e.pkg); updateButtons() }
        col.addView(favBtn, LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(12) })
        runtimeBtn = action(getString(R.string.game_runtime), false) { chooseGameRuntime() }
        col.addView(runtimeBtn, LinearLayout.LayoutParams(-1, dp(56)).also { it.topMargin = dp(12) })
        runtimeStatus = TextView(this).apply { textSize = 14f; setTextColor(C.MUTED); gravity = Gravity.CENTER }
        col.addView(runtimeStatus, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        col.addView(action(getString(R.string.app_info), false) {
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
        window.decorView.post { SceneHost.attach(this) }
        if (!::entry.isInitialized) return
        if (!isPackagePresent()) { finish(); return }
        if (::playBtn.isInitialized && launchAnimation == null) playBtn.isEnabled = true
        if (awaitingInstallPermission && packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = false
            pendingApk?.let { installPending(it) }
        }
    }

    override fun onPause() {
        launchAnimation?.cancel()
        launchAnimation = null
        SceneHost.detach()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (launchAnimation != null) {
            launchAnimation?.cancel()
            launchAnimation = null
            if (::playBtn.isInitialized) playBtn.isEnabled = true
        } else super.onBackPressed()
    }

    private fun isPackagePresent() = try { packageManager.getPackageInfo(entry.pkg, 0); true } catch (e: Exception) { false }

    private fun updateButtons() {
        favBtn.text = getString(if (prefs.isFav(entry.pkg)) R.string.remove_favorite else R.string.add_favorite)
        runtimeBtn.text = getString(R.string.game_runtime_choice, runtimeChoiceName(prefs.appRuntime(entry.pkg)))
        val resolved = Runtimes.resolve(this, entry.pkg)
        val suffix = if (RuntimeChoice.normalize(prefs.appRuntime(entry.pkg)) == RuntimeChoice.OPENXR_COMPAT)
            " · " + getString(R.string.runtime_openxr_compat_short) else ""
        runtimeStatus.text = getString(R.string.game_runtime_active, (resolved?.label ?: getString(R.string.runtime_none)) + suffix)
    }

    private fun runtimeChoiceName(choice: String): String = getString(when (RuntimeChoice.normalize(choice)) {
        RuntimeChoice.MONADO -> R.string.runtime_monado
        RuntimeChoice.OCULUSXR -> R.string.runtime_oculusxr
        RuntimeChoice.OPENXR_COMPAT -> R.string.runtime_openxr_compat
        RuntimeChoice.AUTO -> R.string.runtime_auto
        else -> R.string.runtime_package
    })

    private fun chooseGameRuntime() {
        val choices = listOf(RuntimeChoice.AUTO, RuntimeChoice.MONADO, RuntimeChoice.OCULUSXR, RuntimeChoice.OPENXR_COMPAT)
        val oculus = Runtimes.oculus(this)
        val labels = choices.map { choice ->
            val name = runtimeChoiceName(choice)
            if (choice == RuntimeChoice.OCULUSXR && oculus == null) getString(R.string.runtime_oculusxr_missing_label, name) else name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.game_runtime)
            .setSingleChoiceItems(labels, choices.indexOf(RuntimeChoice.normalize(prefs.appRuntime(entry.pkg)))) { dialog, which ->
                val choice = choices[which]
                if (choice == RuntimeChoice.OCULUSXR && oculus == null) {
                    AlertDialog.Builder(this).setMessage(R.string.oculusxr_missing_message).setPositiveButton(R.string.ok, null).show()
                } else {
                    prefs.setAppRuntime(entry.pkg, choice)
                    if (choice == RuntimeChoice.OPENXR_COMPAT) {
                        Toast.makeText(this, R.string.runtime_openxr_compat_warning, Toast.LENGTH_LONG).show()
                    }
                    updateButtons()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun play() {
        if (patching || launchAnimation != null) return
        val marker = ApkPatch.marker(this, entry.pkg)
        // Quest can start the original package through its system runtime with the store signature intact.
        if (marker == null && entry.quest && Runtimes.resolve(this, entry.pkg)?.kind == Kind.OCULUS) {
            launchGame()
            return
        }
        if (marker == null || !ApkPatch.matchesRuntime(this, entry.pkg, marker)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.optimized_install_title)
                .setMessage(getString(if (marker == null) R.string.optimized_install_message else R.string.runtime_repatch_message, entry.label))
                .setPositiveButton(R.string.optimize_and_install) { _, _ -> runApkPatch() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else launchGame()
    }

    /** Optimization profile, also available by long-pressing Play. */
    private fun runApkPatch() {
        if (patching) return
        if (!OptimizationGate.tryAcquire(entry.pkg)) {
            Toast.makeText(this, R.string.patch_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        patching = true
        val dlg = AlertDialog.Builder(this).setTitle(R.string.patch_apk).setMessage(R.string.preparing).setCancelable(false).show()
        Thread {
            try {
                val f = ApkPatcher.patch(this, entry.pkg, Profiles.OPT) { msg -> runOnUiThread { if (!isFinishing && !isDestroyed) dlg.setMessage(msg) } }
                runOnUiThread {
                    patching = false
                    dlg.dismiss()
                    if (isFinishing || isDestroyed) f.delete() else confirmInstall(f)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    patching = false
                    dlg.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(e.message ?: e.toString()).setPositiveButton(R.string.ok, null).show()
                }
            } finally {
                OptimizationGate.release(entry.pkg)
            }
        }.start()
    }

    private fun confirmInstall(apk: java.io.File) {
        pendingApk = apk
        // Repatches signed by this installation can update in place without deleting saves.
        @Suppress("DEPRECATION")
        val current = packageManager.getPackageInfo(entry.pkg, PackageManager.GET_SIGNATURES).signatures
        @Suppress("DEPRECATION")
        val prepared = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        if (!current.isNullOrEmpty() && !prepared.isNullOrEmpty() &&
            current.size == prepared.size && current.all { old -> prepared.any { it == old } }) {
            installPending(apk)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.patch_ready)
            .setMessage(R.string.replace_signature_warning)
            .setPositiveButton(R.string.replace_install) { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.pkg}")).putExtra(Intent.EXTRA_RETURN_RESULT, true),
                    reqUninstall,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqUninstall) {
            val apk = pendingApk
            if (resultCode == RESULT_OK && apk != null && !isPackagePresent()) installPending(apk)
            else Toast.makeText(this, getString(R.string.original_not_removed, apk?.name ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun installPending(apk: java.io.File) {
        if (!packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = true
            AlertDialog.Builder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.allow_install_resume)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.install_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try { Installer.install(this, apk) } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.install_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGame(cameraPrompted: Boolean = false) {
        if (launchAnimation != null || patching) return
        // Preflight before the transition: never animate a launch that has no activity to start.
        val i = AppScanner.playIntent(this, entry)
        if (i == null) { Toast.makeText(this, getString(R.string.no_launchable_activity), Toast.LENGTH_SHORT).show(); return }
        val runtime = Runtimes.resolve(this, entry.pkg)
        if (runtime == null) {
            Toast.makeText(this, getString(R.string.runtime_unavailable_launch), Toast.LENGTH_LONG).show()
            return
        }
        // Ask from the foreground before the game takes the camera. A background service cannot reliably
        // show the runtime permission dialog on Android 10+, which left hand tracking silently disabled.
        if (!cameraPrompted && runtime.pkg == packageName && prefs.handEnabled &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraLaunch = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), reqCameraForLaunch)
            return
        }
        val previousGame = prefs.currentGame
        // Record the game before prewarming so a newly started Monado process reads the right SBS/badge package.
        prefs.currentGame = entry.pkg
        if (runtime.pkg == packageName && prefs.handEnabled && !HandRuntime.startIfEnabled(this)) {
            Toast.makeText(this, getString(R.string.hand_runtime_prewarm_failed), Toast.LENGTH_LONG).show()
        }
        playBtn.isEnabled = false
        // Start the game immediately on Play; the animation is a transition, not an 850 ms gate that can
        // outlive the foreground activity token. A custom activity animation still shows while the game
        // window is coming up, and the in-app overlay remains visible until the game covers it.
        launchAnimation = LaunchAnimation.show(this, entry) {
            launchAnimation = null
            if (::playBtn.isInitialized) playBtn.isEnabled = true
        }
        try {
            val options = ActivityOptions.makeCustomAnimation(this, R.anim.olfx_game_enter, R.anim.olfx_launcher_exit)
            startActivity(i, options.toBundle())
        } catch (ex: SecurityException) {
            launchAnimation?.cancel(); launchAnimation = null
            playBtn.isEnabled = true
            prefs.currentGame = previousGame
            AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(getString(R.string.launch_permission_denied, entry.label))
                .setPositiveButton(R.string.app_info) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.pkg}")))
                }
                .setNegativeButton(R.string.ok, null)
                .show()
        } catch (ex: Exception) {
            launchAnimation?.cancel(); launchAnimation = null
            playBtn.isEnabled = true
            prefs.currentGame = previousGame
            Toast.makeText(this, getString(R.string.launch_failed, ex.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqCameraForLaunch || !pendingCameraLaunch) return
        pendingCameraLaunch = false
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.camera_needed_for_hands), Toast.LENGTH_LONG).show()
        }
        launchGame(cameraPrompted = true)
    }
}
