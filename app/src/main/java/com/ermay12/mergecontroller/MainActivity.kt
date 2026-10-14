package com.ermay12.mergecontroller

import android.app.Activity
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.ermay12.mergecontroller.MergeServiceClient.ShizukuState
import kotlin.math.abs

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var client: MergeServiceClient
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private lateinit var leftStatus: TextView
    private lateinit var rightStatus: TextView
    private lateinit var mergeStatus: TextView
    private lateinit var mergeButton: Button
    private lateinit var errorText: TextView
    private lateinit var nintendoLayout: Switch
    private lateinit var inputSource: TextView
    private lateinit var inputButtons: TextView
    private lateinit var inputAxes: TextView
    private lateinit var deviceList: TextView

    private var lastInputDeviceId = -1
    private val heldKeys = sortedSetOf<Int>()
    private val axes = FloatArray(AXES.size)
    private val loggedAxes = FloatArray(AXES.size)

    private val statusPoller = object : Runnable {
        override fun run() {
            client.refresh()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shizukuStatus = findViewById(R.id.shizuku_status)
        shizukuButton = findViewById(R.id.shizuku_button)
        leftStatus = findViewById(R.id.left_status)
        rightStatus = findViewById(R.id.right_status)
        mergeStatus = findViewById(R.id.merge_status)
        mergeButton = findViewById(R.id.merge_button)
        errorText = findViewById(R.id.error_text)
        nintendoLayout = findViewById(R.id.nintendo_layout)
        inputSource = findViewById(R.id.input_source)
        inputButtons = findViewById(R.id.input_buttons)
        inputAxes = findViewById(R.id.input_axes)
        deviceList = findViewById(R.id.device_list)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        inputManager = getSystemService(InputManager::class.java)
        client = MergeServiceClient(this, ::render)

        nintendoLayout.isChecked = prefs.getBoolean(PREF_NINTENDO_LAYOUT, false)
        nintendoLayout.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_NINTENDO_LAYOUT, checked).apply()
            client.setFlags(mergeFlags())
        }
        shizukuButton.setOnClickListener { client.requestPermission() }
        mergeButton.setOnClickListener {
            if (client.status?.running == true) client.stop() else client.start(mergeFlags())
        }

        client.register()
        render()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, handler)
        handler.post(statusPoller)
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacks(statusPoller)
        super.onPause()
    }

    override fun onDestroy() {
        client.unregister()
        super.onDestroy()
    }

    override fun onInputDeviceAdded(deviceId: Int) = render()

    override fun onInputDeviceRemoved(deviceId: Int) = render()

    override fun onInputDeviceChanged(deviceId: Int) = render()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        if (device == null || !device.isGameController()) return super.dispatchKeyEvent(event)

        noteInputDevice(event.deviceId)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                heldKeys.add(event.keyCode)
                Log.i(TAG, "key ${device.name}: ${keyName(event.keyCode)} DOWN")
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(event.keyCode)
                Log.i(TAG, "key ${device.name}: ${keyName(event.keyCode)} UP")
            }
        }
        renderInput(device)
        // Consumed so B doesn't act as Back while testing.
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        noteInputDevice(event.deviceId)
        AXES.forEachIndexed { i, axis -> axes[i] = event.getAxisValue(axis) }
        if (AXES.indices.any { abs(axes[it] - loggedAxes[it]) >= AXIS_LOG_STEP }) {
            axes.copyInto(loggedAxes)
            val values = AXES.indices.joinToString(" ") {
                "${MotionEvent.axisToString(AXES[it]).removePrefix("AXIS_")}=%.2f".format(axes[it])
            }
            Log.i(TAG, "motion ${event.device?.name}: $values")
        }
        renderInput(event.device)
        return true
    }

    private fun mergeFlags() = if (nintendoLayout.isChecked) MergeStatus.FLAG_NINTENDO_LAYOUT else 0

    private fun noteInputDevice(deviceId: Int) {
        if (deviceId == lastInputDeviceId) return
        lastInputDeviceId = deviceId
        heldKeys.clear()
        axes.fill(0f)
    }

    private fun render() {
        val status = client.status
        val running = status?.running == true
        val controllers = gameControllers()

        shizukuButton.visibility = View.GONE
        when (client.shizukuState) {
            ShizukuState.NOT_RUNNING -> shizukuStatus.setText(R.string.shizuku_not_running)
            ShizukuState.PERMISSION_DENIED -> shizukuStatus.setText(R.string.shizuku_permission_denied)
            ShizukuState.PERMISSION_NEEDED -> {
                shizukuStatus.setText(R.string.shizuku_permission_needed)
                shizukuButton.visibility = View.VISIBLE
            }
            ShizukuState.READY -> shizukuStatus.text =
                getString(R.string.shizuku_ready, if (client.shizukuUid == 0) "root" else "shell")
        }

        renderJoyCon(leftStatus, R.string.joycon_left, status?.left, MergeStatus.LEFT_JOYCON_NAME, controllers)
        renderJoyCon(rightStatus, R.string.joycon_right, status?.right, MergeStatus.RIGHT_JOYCON_NAME, controllers)

        mergeStatus.text = when {
            client.starting -> getString(R.string.merge_starting)
            status != null && running && (status.left != null || status.right != null) ->
                getString(R.string.merge_running, MergeStatus.VIRTUAL_DEVICE_NAME, status.virtualPath)
            running -> getString(R.string.merge_waiting, MergeStatus.VIRTUAL_DEVICE_NAME)
            else -> getString(R.string.merge_stopped)
        }
        mergeButton.setText(if (running) R.string.stop_merging else R.string.start_merging)
        mergeButton.isEnabled = client.shizukuState == ShizukuState.READY && !client.starting

        val error = client.error ?: status?.error?.ifEmpty { null }
        errorText.text = error
        errorText.visibility = if (error == null) View.GONE else View.VISIBLE

        val hiddenNames = setOfNotNull(status?.left?.name, status?.right?.name)
        deviceList.text = if (controllers.isEmpty()) {
            getString(R.string.devices_none)
        } else {
            controllers.joinToString("\n") { device ->
                val tag = when (device.name) {
                    MergeStatus.VIRTUAL_DEVICE_NAME -> getString(R.string.device_tag_virtual)
                    in hiddenNames -> getString(R.string.device_tag_hidden)
                    else -> ""
                }
                "• ${device.name}$tag"
            }
        }
    }

    private fun renderJoyCon(
        view: TextView,
        labelRes: Int,
        source: MergeStatus.Source?,
        deviceName: String,
        controllers: List<InputDevice>,
    ) {
        val label = getString(labelRes)
        view.text = when {
            source != null -> getString(R.string.joycon_merged, label, source.path)
            controllers.any { it.name == deviceName } -> getString(R.string.joycon_connected, label)
            else -> getString(R.string.joycon_missing, label)
        }
    }

    private fun renderInput(device: InputDevice?) {
        inputSource.text = getString(R.string.input_from, device?.name ?: "?")
        inputButtons.text = if (heldKeys.isEmpty()) {
            getString(R.string.input_held_none)
        } else {
            getString(R.string.input_held, heldKeys.joinToString("  ") { keyName(it) })
        }
        inputAxes.text = getString(R.string.input_axes, *axes.toTypedArray())
    }

    private fun gameControllers(): List<InputDevice> =
        inputManager.inputDeviceIds.asList()
            .mapNotNull { inputManager.getInputDevice(it) }
            .filter { it.isGameController() }

    private fun InputDevice.isGameController() =
        supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK)

    private fun keyName(keyCode: Int) = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    companion object {
        private const val TAG = "MergeController"
        private const val PREFS_NAME = "settings"
        private const val PREF_NINTENDO_LAYOUT = "nintendo_layout"
        private const val STATUS_POLL_MS = 500L
        private const val AXIS_LOG_STEP = 0.25f

        // Order must match the placeholders in R.string.input_axes.
        private val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )
    }
}
