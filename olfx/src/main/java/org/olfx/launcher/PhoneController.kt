package org.olfx.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Manual local-network pairing; host and controller must be on the same network. */
object PhoneController {
    private const val PORT = 39093
    fun host(activity: AppCompatActivity) {
        val file = activity.filesDir.resolve("phone_controller_pin")
        val ip = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "—"
        if (!file.exists()) file.writeText("%06d".format(Locale.US, SecureRandom().nextInt(1_000_000)))
        AlertDialog.Builder(activity).setTitle("Телефон как контроллер")
            .setMessage("Адрес этого телефона: $ip\nКод подключения: ${file.readText().trim()}\nВведите адрес и код на телефоне-контроллере.")
            .setPositiveButton("Готово", null)
            .setNegativeButton("Выключить приём") { _, _ -> file.delete() }.show()
    }
    fun sender(activity: AppCompatActivity) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val address = EditText(activity).apply { hint = "IP телефона с игрой"; setSingleLine() }
        val pin = EditText(activity).apply { hint = "Код подключения"; inputType = 2; setSingleLine() }
        val side = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Левая рука", "Правая рука")) }
        layout.addView(address); layout.addView(pin); layout.addView(side)
        AlertDialog.Builder(activity).setTitle("Подключить как контроллер").setView(layout)
            .setNegativeButton("Отмена", null).setPositiveButton("Подключить") { _, _ ->
                if (address.text.isNullOrBlank() || !pin.text.toString().matches(Regex("[0-9]{6}"))) {
                    Toast.makeText(activity, "Укажите адрес и код", Toast.LENGTH_LONG).show()
                } else run(activity, address.text.toString().trim(), pin.text.toString(), side.selectedItemPosition)
            }.show()
    }
    private fun run(activity: AppCompatActivity, host: String, pin: String, side: Int) {
        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) { Toast.makeText(activity, "Гироскоп недоступен", Toast.LENGTH_LONG).show(); return }
        val state = State()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                val q = FloatArray(4); SensorManager.getQuaternionFromVector(q, e.values)
                synchronized(state) {
                    val ref = state.ref ?: q.clone().also { state.ref = it }
                    val a = floatArrayOf(ref[0], -ref[1], -ref[2], -ref[3]); val b = q
                    state.q = floatArrayOf(a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2], a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1], a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0], a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3])
                }
            }
        }
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        fun key(label: String, set: (Boolean) -> Unit): Button = Button(activity).apply {
            text = label; setOnTouchListener { _, event -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { set(true); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { set(false); performClick(); true }
                else -> true
            } }
        }
        layout.addView(key("Курок") { synchronized(state) { state.trigger = it } })
        layout.addView(key("Захват") { synchronized(state) { state.grip = it } })
        layout.addView(Button(activity).apply { text = "Центрировать"; setOnClickListener { synchronized(state) { state.ref = null } } })
        val executor = Executors.newSingleThreadScheduledExecutor(); val socket = DatagramSocket()
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        executor.scheduleAtFixedRate({ try {
            val packet = synchronized(state) { "OLFX1 $pin $side ${state.q[0]} ${state.q[1]} ${state.q[2]} ${state.q[3]} ${if (state.trigger) 1 else 0} ${if (state.grip) 1 else 0}" }.toByteArray()
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), PORT))
        } catch (_: Exception) {} }, 0, 25, TimeUnit.MILLISECONDS)
        AlertDialog.Builder(activity).setTitle("Контроллер включён").setView(layout).setPositiveButton("Отключить", null)
            .setOnDismissListener { sm.unregisterListener(listener); executor.shutdownNow(); socket.close() }.show()
    }
    private class State { var ref: FloatArray? = null; var q = floatArrayOf(0f, 0f, 0f, 1f); var trigger = false; var grip = false }
}
