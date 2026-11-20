// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import java.io.File

/**
 * Pico 4 / Neo 3: shows the launcher as a floating window inside the CC0 living-room scene (olfx_scene.cpp).
 * The scene exists only while a launcher activity is resumed: starting a game pauses the activity, which ends the
 * OLFX OpenXR session, so the game gets the headset's runtime to itself ("scene mode" belongs to the game).
 */
object SceneHost {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null
    @Volatile private var root: View? = null
    @Volatile private var texture: SurfaceTexture? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var downTime = 0L
    private var loaded = false

    private val draw = object : Runnable {
        override fun run() {
            val v = root ?: return
            val s = surface
            if (s != null && s.isValid && v.width > 0 && v.height > 0) {
                try {
                    val c = s.lockHardwareCanvas()
                    c.drawColor(0xFF1B1B1F.toInt())
                    v.draw(c)
                    s.unlockCanvasAndPost(c)
                } catch (_: Throwable) {
                }
            }
            main.postDelayed(this, 33)
        }
    }

    @JvmStatic external fun nativeRun(activity: Activity, assets: android.content.res.AssetManager, loaderPath: String)
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeRunGo(activity: Activity, surface: Surface, assets: android.content.res.AssetManager)
    @JvmStatic external fun nativeStopGo()
    private var goView: android.view.SurfaceView? = null

    fun supported(): Boolean = Headset.current == HeadsetKind.PICO_4 || Headset.current == HeadsetKind.PICO_NEO3 || Headset.current == HeadsetKind.OCULUS_GO

    fun attach(activity: Activity) {
        if (!supported()) return
        synchronized(this) {
            detachLocked()
            val view = activity.findViewById<View>(android.R.id.content) ?: return
            if (Headset.current == HeadsetKind.OCULUS_GO) { attachGo(activity, view); return }
            val loader = extractLoader(activity) ?: return
            if (!loaded) {
                try { System.loadLibrary("olfx_scene"); loaded = true } catch (t: Throwable) { return }
            }
            root = view
            main.post(draw)
            val t = Thread({ nativeRun(activity, activity.assets, loader.absolutePath) }, "olfx-scene")
            thread = t
            t.start()
        }
    }

    /** Oculus Go: VrApi needs a window surface; a 1 px SurfaceView provides it while the content view is drawn into the panel. */
    private fun attachGo(activity: Activity, view: View) {
        if (!loaded) {
            try { System.loadLibrary("olfx_go"); loaded = true } catch (t: Throwable) { return }
        }
        root = view
        val sv = android.view.SurfaceView(activity)
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                val t = Thread({ nativeRunGo(activity, h.surface, activity.assets) }, "olfx-scene-go")
                thread = t
                t.start()
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) { synchronized(this@SceneHost) { stopGoThread() } }
        })
        goView = sv
        activity.addContentView(sv, android.widget.FrameLayout.LayoutParams(1, 1))
        main.post(draw)
    }

    private fun stopGoThread() {
        if (loaded) { try { nativeStopGo() } catch (_: UnsatisfiedLinkError) { } }
        thread?.let { try { it.join(2000) } catch (_: InterruptedException) { } }
        thread = null
    }

    fun detach() = synchronized(this) { detachLocked() }

    private fun detachLocked() {
        main.removeCallbacks(draw)
        root = null
        goView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        goView = null
        if (loaded) {
            try { nativeStop() } catch (_: UnsatisfiedLinkError) { }
            try { nativeStopGo() } catch (_: UnsatisfiedLinkError) { }
        }
        thread?.let { try { it.join(2000) } catch (_: InterruptedException) { } }
        thread = null
        try { surface?.release(); texture?.release() } catch (_: Throwable) { }
        surface = null; texture = null
    }

    private fun extractLoader(a: Activity): File? = try {
        val f = File(a.filesDir, "libopenxr_loader_scene.so")
        if (!f.isFile) a.assets.open("khronos/libopenxr_loader_arm64.so").use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    } catch (_: Throwable) { null }

    // ---- called from the native render thread (GL context current) ----
    @JvmStatic fun panelCreate(texId: Int) {
        val v = root
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(maxOf(1, v?.width ?: 1280), maxOf(1, v?.height ?: 720))
        texture = st
        surface = Surface(st)
    }

    @JvmStatic fun panelUpdate(out: FloatArray) {
        val st = texture
        try { st?.updateTexImage(); st?.getTransformMatrix(out) } catch (_: Throwable) { }
    }

    /** action: 0 down, 1 move, 2 up, 3 cancel; u,v in 0..1 over the panel (v down). */
    @JvmStatic fun onPointer(u: Float, v: Float, action: Int) {
        val view = root ?: return
        main.post {
            val now = SystemClock.uptimeMillis()
            if (action == 0) downTime = now
            val a = when (action) { 0 -> MotionEvent.ACTION_DOWN; 1 -> MotionEvent.ACTION_MOVE; 2 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_CANCEL }
            val e = MotionEvent.obtain(downTime, now, a, u * view.width, v * view.height, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
    }
}
