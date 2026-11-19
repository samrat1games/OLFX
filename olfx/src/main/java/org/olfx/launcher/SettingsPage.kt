// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import org.freedesktop.monado.openxr_runtime.R

/** Settings page: language, integrated runtime/OculusXR, SBS, render scale and sensor status. */
class SettingsPage(private val activity: AppCompatActivity, private val prefs: Prefs, private val handSettings: View) {
    private lateinit var runtimeStatus: TextView
    private lateinit var runtimeSummary: TextView
    private lateinit var scaleStatus: TextView
    private lateinit var clickAccessibilityStatus: TextView
    private var oculusRuntime: RuntimeInfo? = null

    val view: View = build()

    private fun build(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(24), activity.dp(16), activity.dp(24))
        }
        column.addView(TextView(activity).apply {
            setText(R.string.settings)
            textSize = 32f
            setTextColor(C.TEXT)
            setPadding(activity.dp(8), 0, 0, activity.dp(16))
        })

        column.addView(handSettings)

        val languageSummary = TextView(activity).apply {
            text = languageName(prefs.language)
            textSize = 16f
            setTextColor(C.MUTED)
        }
        column.addView(card {
            addView(title(R.string.language))
            addView(languageSummary)
            addView(button(R.string.change_language, false) { chooseLanguage() })
        })

        column.addView(card {
            addView(title(R.string.runtime_title))
            runtimeStatus = body("")
            addView(runtimeStatus)
            runtimeSummary = body("")
            addView(runtimeSummary)
            addView(button(R.string.runtime_choose, true) { chooseRuntime() })
        })

        column.addView(card {
            addView(TextView(activity).apply { text = "Телефоны-контроллеры"; textSize = 18f; setTextColor(C.TEXT) })
            addView(MaterialButton(activity).apply { text = "Принимать контроллеры"; setOnClickListener { PhoneController.host(activity) } })
            addView(MaterialButton(activity).apply { text = "Использовать как контроллер"; setOnClickListener { PhoneController.sender(activity) } })
        })

        val accessibility = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(card {
            addView(title(R.string.click_mode_title))
            addView(body(activity.getString(R.string.click_mode_description)))
            val screen = RadioButton(activity).apply {
                id = View.generateViewId()
                setText(R.string.click_mode_screen)
                setTextColor(C.TEXT)
            }
            val pinch = RadioButton(activity).apply {
                id = View.generateViewId()
                setText(R.string.click_mode_pinch)
                setTextColor(C.TEXT)
            }
            val group = RadioGroup(activity).apply {
                orientation = RadioGroup.VERTICAL
                addView(screen)
                addView(pinch)
            }
            addView(group)
            clickAccessibilityStatus = body("")
            accessibility.addView(clickAccessibilityStatus)
            accessibility.addView(button(R.string.click_open_accessibility, false) { openAccessibility() })
            accessibility.visibility = if (prefs.clickMode == ClickMode.PINCH) View.VISIBLE else View.GONE
            addView(accessibility)
            group.check(if (prefs.clickMode == ClickMode.PINCH) pinch.id else screen.id)
            group.setOnCheckedChangeListener { _, id ->
                val mode = if (id == pinch.id) ClickMode.PINCH else ClickMode.SCREEN
                if (mode != prefs.clickMode) {
                    prefs.clickMode = mode
                    accessibility.visibility = if (mode == ClickMode.PINCH) View.VISIBLE else View.GONE
                    if (mode == ClickMode.PINCH) promptAccessibility()
                    refresh()
                }
            }
        })

        column.addView(card {
            addView(title(R.string.display_title))
            addView(switchRow(R.string.sbs_title, prefs.sbsEnabled) { prefs.sbsEnabled = it })
            addView(title(R.string.render_scale))
            scaleStatus = body("")
            addView(scaleStatus)
            addView(SeekBar(activity).apply {
                max = 100
                progress = prefs.currentScale - 50
                contentDescription = activity.getString(R.string.render_scale)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + 50
                        scaleStatus.text = activity.getString(R.string.render_scale_value, value)
                        if (fromUser) prefs.currentScale = value
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
            scaleStatus.text = activity.getString(R.string.render_scale_value, prefs.currentScale)
            addView(body(activity.getString(R.string.restart_required)))
            addView(button(R.string.restart_runtime, false) { confirmRestart() })
        })

        val hasGyro = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
        val hasAccel = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        column.addView(card {
            addView(title(R.string.gyroscope_title))
            addView(body(activity.getString(
                if (hasGyro && hasAccel) R.string.gyroscope_available else R.string.gyroscope_missing
            )))
            addView(body(activity.getString(R.string.tracking_limits)))
        })
        return ScrollView(activity).apply { addView(column) }
    }

    private fun card(build: LinearLayout.() -> Unit) = MaterialCardView(activity).apply {
        setCardBackgroundColor(C.SURFACE)
        radius = activity.dp(24).toFloat()
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(18), activity.dp(20), activity.dp(18))
            build()
        })
    }

    private fun title(res: Int) = TextView(activity).apply {
        setText(res)
        textSize = 19f
        setTextColor(C.TEXT)
        setPadding(0, activity.dp(4), 0, activity.dp(8))
    }

    private fun body(value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(C.MUTED)
        setPadding(0, 0, 0, activity.dp(8))
    }

    private fun button(res: Int, filled: Boolean, action: () -> Unit) =
        MaterialButton(activity, null, if (filled) {
            com.google.android.material.R.attr.materialButtonStyle
        } else {
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        }).apply {
            setText(res)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) }
            setOnClickListener { action() }
        }

    private fun switchRow(titleRes: Int, checked: Boolean, changed: (Boolean) -> Unit) =
        MaterialSwitch(activity).apply {
            setText(titleRes)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        }

    private fun languageName(tag: String) = activity.getString(when (AppLocale.normalize(tag)) {
        "pt-BR" -> R.string.language_pt_br
        "ru" -> R.string.language_ru
        "en" -> R.string.language_en
        else -> R.string.language_system
    })

    private fun chooseLanguage() {
        val tags = listOf(AppLocale.SYSTEM, "pt-BR", "ru", "en")
        AlertDialog.Builder(activity)
            .setTitle(R.string.language)
            .setSingleChoiceItems(tags.map { languageName(it) }.toTypedArray(), tags.indexOf(prefs.language)) { dialog, which ->
                prefs.language = tags[which]
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun refresh() {
        if (activity.isDestroyed) return
        val runtimes = Runtimes.list(activity)
        oculusRuntime = runtimes.firstOrNull { it.kind == Kind.OCULUS }
        runtimeStatus.text = oculusRuntime?.let {
            activity.getString(R.string.oculusxr_detected, it.label)
        } ?: activity.getString(R.string.oculusxr_missing)
        runtimeSummary.text = activity.getString(R.string.runtime_current, runtimeChoiceName(prefs.defaultRuntime))
        val enabled = pinchServiceEnabled()
        clickAccessibilityStatus.text = activity.getString(
            R.string.click_accessibility_status,
            activity.getString(if (enabled) R.string.click_accessibility_enabled else R.string.click_accessibility_disabled),
        )
    }

    /** Keeps the legacy standalone launcher module free of a hard dependency on the ipc module. */
    private fun pinchServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val component = "${activity.packageName}/org.freedesktop.monado.ipc.PinchClickService"
        return enabled.split(":").any { it.equals(component, ignoreCase = true) }
    }

    private fun openAccessibility() {
        try {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            message(R.string.click_accessibility_message)
        }
    }

    private fun promptAccessibility() {
        if (pinchServiceEnabled()) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.click_open_accessibility)
            .setMessage(R.string.click_accessibility_message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openAccessibility() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runtimeChoiceName(choice: String): String = activity.getString(when (RuntimeChoice.normalize(choice)) {
        RuntimeChoice.MONADO -> R.string.runtime_monado
        RuntimeChoice.OCULUSXR -> R.string.runtime_oculusxr
        RuntimeChoice.OPENXR_COMPAT -> R.string.runtime_openxr_compat
        RuntimeChoice.AUTO -> R.string.runtime_auto
        else -> R.string.runtime_package
    })

    private fun chooseRuntime() {
        val choices = listOf(RuntimeChoice.AUTO, RuntimeChoice.MONADO, RuntimeChoice.OCULUSXR)
        val labels = choices.map { choice ->
            val name = runtimeChoiceName(choice)
            if (choice == RuntimeChoice.OCULUSXR && oculusRuntime == null) {
                activity.getString(R.string.runtime_oculusxr_missing_label, name)
            } else name
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.runtime_choose)
            .setSingleChoiceItems(labels, choices.indexOf(RuntimeChoice.normalize(prefs.defaultRuntime))) { dialog, which ->
                val choice = choices[which]
                if (choice == RuntimeChoice.OCULUSXR && oculusRuntime == null) {
                    message(R.string.oculusxr_missing_message)
                } else {
                    prefs.defaultRuntime = choice
                    refresh()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestart() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.restart_runtime)
            .setMessage(R.string.restart_runtime_warning)
            .setPositiveButton(R.string.restart_runtime) { _, _ ->
                val stopped = try {
                    activity.stopService(Intent().setClassName(activity.packageName, "org.freedesktop.monado.ipc.MonadoService"))
                } catch (_: SecurityException) {
                    false
                }
                message(if (stopped) R.string.restart_requested else R.string.restart_not_running)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun message(res: Int) {
        if (!activity.isDestroyed) {
            AlertDialog.Builder(activity).setMessage(res).setPositiveButton(R.string.ok, null).show()
        }
    }

    fun destroy() {}
}
