package org.olfx.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CHANNEL = "olfx_overlay"

private fun Context.ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "OLFX", NotificationManager.IMPORTANCE_LOW))
    }
}

private fun Context.notif(text: String, stopAction: String? = null, cls: Class<*>? = null): Notification {
    ensureChannel()
    val b = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("OLFX")
        .setContentText(text)
        .setOngoing(true)
    if (stopAction != null && cls != null) {
        val pi = PendingIntent.getService(this, 0, Intent(this, cls).setAction(stopAction), PendingIntent.FLAG_IMMUTABLE)
        b.addAction(Notification.Action.Builder(null, "Стоп", pi).build())
    }
    return b.build()
}

private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
}

/** Shared recording state so the menu can show Start/Stop. */
object RecState {
    @Volatile var recording = false
    var listener: (() -> Unit)? = null
    fun set(v: Boolean) { recording = v; Handler(Looper.getMainLooper()).post { listener?.invoke() } }
}

/** Overlay with a collapsible menu on the left edge of the screen. Works on top of games. */
class MenuService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var panel: LinearLayout
    private lateinit var recBtn: TextView

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, notif("Меню записи активно"))
        if (root == null) buildMenu()
        return START_NOT_STICKY
    }

    private fun buildMenu() {
        wm = getSystemService(WindowManager::class.java)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = round(0xEE161A23.toInt(), dp(16).toFloat())
            visibility = View.GONE
        }
        recBtn = menuButton("⏺  Запись экрана") { toggleRecording() }
        panel.addView(recBtn)
        panel.addView(menuButton("ПОКА ЧТО") { Toast.makeText(this, "Пока что — скоро", Toast.LENGTH_SHORT).show() })
        panel.addView(menuButton("Закрыть меню") { stopSelf() })
        r.addView(panel)

        val handle = TextView(this).apply {
            text = "‹›"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = round(0xCC7C5CFF.toInt(), dp(10).toFloat())
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        r.addView(handle, LinearLayout.LayoutParams(dp(26), dp(84)).also { it.leftMargin = dp(2) })

        val p = overlayParams(-2, -2, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        wm.addView(r, p)
        root = r
        RecState.listener = { updateRecButton() }
        updateRecButton()
    }

    private fun menuButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.setMargins(0, dp(3), 0, dp(3)) }
        background = round(0xFF232838.toInt(), dp(10).toFloat())
    }

    private fun updateRecButton() {
        recBtn.text = if (RecState.recording) "⏹  Стоп запись" else "⏺  Запись экрана"
    }

    private fun toggleRecording() {
        panel.visibility = View.GONE // keep the menu out of the recorded frame
        if (RecState.recording) {
            startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
        } else {
            startActivity(Intent(this, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        RecState.listener = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }
}

/** Transparent activity that asks for screen-capture consent and hands the result to [RecordService]. */
class ProjectionRequestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_START)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
            )
        }
        finish()
    }
}

/** Records the screen to Movies/OLFX and draws the moving "OLFX - Open Free" watermark (it ends up in the video). */
class RecordService : Service() {
    companion object {
        const val ACTION_START = "org.olfx.launcher.REC_START"
        const val ACTION_STOP = "org.olfx.launcher.REC_STOP"
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var wm: WindowManager? = null
    private var wmLayer: FrameLayout? = null
    private var wmText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var leg = 0
    private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_START -> start(i)
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(i: Intent) {
        if (running) return
        startForeground(
            3,
            notif("Идёт запись экрана", ACTION_STOP, RecordService::class.java),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        try {
            val code = i.getIntExtra("code", 0)
            val data = i.getParcelableExtra<Intent>("data") ?: throw IllegalStateException("no projection data")
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() { override fun onStop() { handler.post { stop() } } }, handler)
            projection = proj

            val wmgr = getSystemService(WindowManager::class.java)
            wm = wmgr
            val bounds = wmgr.maximumWindowMetrics.bounds
            val scale = minOf(1f, 1920f / maxOf(bounds.width(), bounds.height()))
            val w = (bounds.width() * scale).toInt() / 2 * 2
            val h = (bounds.height() * scale).toInt() / 2 * 2
            val dpi = resources.displayMetrics.densityDpi

            val name = "OLFX_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OLFX")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("MediaStore insert failed")
            uri = u
            pfd = contentResolver.openFileDescriptor(u, "w")

            val rec = MediaRecorder(this)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)
            rec.setOutputFile(pfd!!.fileDescriptor)
            rec.prepare()
            recorder = rec

            display = proj.createVirtualDisplay("olfx-rec", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null)
            rec.start()
            running = true
            RecState.set(true)
            showWatermark()
        } catch (e: Exception) {
            Toast.makeText(this, "Запись не началась: ${e.message}", Toast.LENGTH_LONG).show()
            cleanup(false)
        }
    }

    private fun stop() {
        if (!running) { cleanup(false); return }
        cleanup(true)
    }

    private fun cleanup(save: Boolean) {
        running = false
        handler.removeCallbacksAndMessages(null)
        wmLayer?.let { runCatching { wm?.removeView(it) } }
        wmLayer = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { pfd?.close() }
        pfd = null
        uri?.let { u ->
            if (save) {
                contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                Toast.makeText(this, "Запись сохранена в Movies/OLFX", Toast.LENGTH_LONG).show()
            } else {
                runCatching { contentResolver.delete(u, null, null) }
            }
        }
        uri = null
        RecState.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Watermark path: slides left→right, jumps to the right edge, slides down, jumps again, and so on. */
    private data class Leg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val ms: Long, val kind: Int)

    private val legs = listOf(
        Leg(0f, 0.05f, 1f, 0.05f, 3200, 0), // along the top: left -> right
        Leg(1f, 0.25f, 1f, 0.92f, 2400, 1), // jump to the right edge, go down
        Leg(0f, 0.90f, 1f, 0.90f, 2800, 2), // jump to bottom-left, go right
        Leg(0f, 0.12f, 0f, 0.80f, 2200, 0), // jump to left edge, go down
        Leg(0.15f, 0.50f, 0.85f, 0.30f, 2600, 2), // diagonal
        Leg(1f, 0.08f, 0f, 0.08f, 3000, 1), // jump top-right, go left
    )

    private fun showWatermark() {
        val m = wm ?: return
        val layer = FrameLayout(this)
        val tv = TextView(this).apply {
            text = "OLFX - Open Free"
            setTextColor(Color.WHITE)
            alpha = 0.78f
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        layer.addView(tv, FrameLayout.LayoutParams(-2, -2))
        m.addView(layer, overlayParams(-1, -1, false))
        wmLayer = layer
        wmText = tv
        layer.post { nextLeg() }
    }

    private fun nextLeg() {
        val layer = wmLayer ?: return
        val tv = wmText ?: return
        if (!running) return
        val l = legs[leg % legs.size]
        leg++
        val maxX = (layer.width - tv.width).coerceAtLeast(0).toFloat()
        val maxY = (layer.height - tv.height).coerceAtLeast(0).toFloat()
        tv.x = l.x0 * maxX
        tv.y = l.y0 * maxY // instant jump to the start of the leg
        val interp = when (l.kind) {
            1 -> AccelerateInterpolator(1.4f)
            2 -> AccelerateDecelerateInterpolator()
            else -> LinearInterpolator()
        }
        tv.animate().x(l.x1 * maxX).y(l.y1 * maxY).setDuration(l.ms).setInterpolator(interp)
            .withEndAction { if (running) nextLeg() }.start()
    }

    override fun onDestroy() {
        if (running) cleanup(true)
        super.onDestroy()
    }
}
