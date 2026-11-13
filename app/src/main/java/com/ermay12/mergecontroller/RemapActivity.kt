package com.ermay12.mergecontroller

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * User-defined button overrides. Buttons are captured by the raw evdev code
 * the phone's kernel reports (which varies between kernels), never by their
 * printed label or position.
 */
class RemapActivity : Activity() {

    private class Entry(val side: Int, val src: Int, var dst: Int)

    private class Output(val code: Int, val labelRes: Int)

    private lateinit var prefs: SharedPreferences
    private lateinit var client: MergeServiceClient
    private val handler = Handler(Looper.getMainLooper())
    private val entries = mutableListOf<Entry>()

    private lateinit var content: View
    private lateinit var mergeDot: View
    private lateinit var mergeStatus: TextView
    private lateinit var note: TextView
    private lateinit var applyButton: Button
    private lateinit var list: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var addButton: Button
    private lateinit var captureHint: TextView
    private lateinit var restoreButton: Button

    private var capturing = false
    private var captureDeadline = 0L

    private val statusPoller = object : Runnable {
        override fun run() {
            if (client.shizukuState == MergeServiceClient.ShizukuState.READY) {
                client.refresh()
            } else {
                client.recheck()
            }
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val capturePoller = object : Runnable {
        override fun run() {
            if (!capturing) return
            val press = client.takeRawPress()
            if (press != 0L) {
                stopCapture()
                onCaptured(((press shr 16) and 0xFFFF).toInt(), (press and 0xFFFF).toInt())
                return
            }
            if (System.currentTimeMillis() >= captureDeadline) {
                stopCapture()
                captureHint.setText(R.string.remap_timeout)
                return
            }
            handler.postDelayed(this, CAPTURE_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remap)
        content = findViewById(R.id.content)
        mergeDot = findViewById(R.id.remap_merge_dot)
        mergeStatus = findViewById(R.id.remap_merge_status)
        note = findViewById(R.id.remap_note)
        applyButton = findViewById(R.id.remap_apply)
        list = findViewById(R.id.remap_list)
        emptyText = findViewById(R.id.remap_empty)
        addButton = findViewById(R.id.remap_add)
        captureHint = findViewById(R.id.remap_capture_hint)
        restoreButton = findViewById(R.id.remap_restore)

        prefs = getSharedPreferences(MergeSettings.PREFS_NAME, MODE_PRIVATE)
        val stored = MergeSettings.loadRemap(prefs)
        for (i in 0..stored.size - 3 step 3) {
            entries.add(Entry(stored[i], stored[i + 1], stored[i + 2]))
        }
        client = MergeServiceClient(this, { MergeSettings.flags(prefs) }, { MergeSettings.loadRemap(prefs) }, ::render)

        findViewById<Button>(R.id.remap_done).setOnClickListener { finish() }
        addButton.setOnClickListener { startCapture() }
        applyButton.setOnClickListener {
            client.start(MergeSettings.flags(prefs))
            captureHint.setText(R.string.remap_applied)
        }
        restoreButton.setOnClickListener {
            entries.clear()
            save()
            rebuildList()
            if (client.status?.running == true) client.start(MergeSettings.flags(prefs))
            captureHint.setText(R.string.remap_restored)
        }

        rebuildList()
        applyContentWidth()
        client.register()
        render()
    }

    override fun onResume() {
        super.onResume()
        client.recheck()
        handler.post(statusPoller)
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoller)
        stopCapture()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // The main screen keeps its own connection; never end the service here.
        client.release(endIdleService = false)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyContentWidth()
    }

    // Consumed so controller buttons can't act on the UI: Android treats
    // BUTTON_B as Back, which would leave this screen mid-capture. Detection
    // reads raw codes from the engine, not these events.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        if (device == null || !device.isGameController()) return super.dispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return super.dispatchGenericMotionEvent(event)
        return true
    }

    private fun render() {
        val running = client.status?.running == true
        mergeDot.backgroundTintList = getColorStateList(if (running) R.color.status_ok else R.color.status_idle)
        mergeStatus.setText(if (running) R.string.merge_on else R.string.merge_off)
        note.setText(if (running) R.string.remap_note_running else R.string.remap_note_stopped)
        applyButton.visibility = if (running) View.VISIBLE else View.GONE
        addButton.isEnabled = running && !capturing
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        restoreButton.isEnabled = entries.isNotEmpty()
    }

    private fun rebuildList() {
        list.removeAllViews()
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.item_remap, list, false)
            row.findViewById<TextView>(R.id.entry_label).text = getString(
                R.string.remap_entry_label,
                getString(if (entry.side == SIDE_LEFT) R.string.remap_side_left else R.string.remap_side_right),
                CODE_NAMES[entry.src] ?: "code ${entry.src}",
                entry.src,
            )
            val spinner = row.findViewById<Spinner>(R.id.entry_output)
            val options = buildList {
                add(getString(R.string.remap_output_default))
                OUTPUTS.forEach { add(getString(it.labelRes)) }
                add(getString(R.string.remap_output_unmap))
            }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.setSelection(selectionFor(entry.dst), false)
            // Attached after layout, so the initial selection doesn't fire it.
            spinner.post {
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        entry.dst = dstForSelection(position)
                        save()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            row.findViewById<Button>(R.id.entry_remove).setOnClickListener {
                entries.remove(entry)
                save()
                rebuildList()
                render()
            }
            list.addView(row)
        }
        render()
    }

    private fun selectionFor(dst: Int): Int = when (dst) {
        DST_DEFAULT -> 0
        DST_UNMAP -> OUTPUTS.size + 1
        else -> 1 + OUTPUTS.indexOfFirst { it.code == dst }.coerceAtLeast(0)
    }

    private fun dstForSelection(position: Int): Int = when (position) {
        0 -> DST_DEFAULT
        OUTPUTS.size + 1 -> DST_UNMAP
        else -> OUTPUTS[position - 1].code
    }

    private fun save() {
        val flat = entries.filter { it.dst != DST_DEFAULT }.flatMap { listOf(it.side, it.src, it.dst) }
        MergeSettings.saveRemap(prefs, flat.toIntArray())
    }

    private fun startCapture() {
        if (client.status?.running != true || capturing) return
        while (client.takeRawPress() != 0L) {}  // drop presses from before this session
        capturing = true
        captureDeadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS
        captureHint.setText(R.string.remap_press)
        render()
        handler.post(capturePoller)
    }

    private fun stopCapture() {
        if (!capturing) return
        capturing = false
        handler.removeCallbacks(capturePoller)
        render()
    }

    private fun onCaptured(side: Int, code: Int) {
        when {
            !remappable(side, code) -> captureHint.setText(R.string.remap_not_remappable)
            entries.any { it.side == side && it.src == code } -> captureHint.setText(R.string.remap_duplicate)
            else -> {
                entries.add(Entry(side, code, DST_DEFAULT))
                rebuildList()
                captureHint.setText(R.string.remap_choose)
            }
        }
    }

    /** Mirrors remap_src_allowed() in merger.c: these are handled outside the keymap. */
    private fun remappable(side: Int, code: Int): Boolean = when {
        side == SIDE_LEFT && (code == BTN_Z || code == BTN_TL2 || code in BTN_DPAD_UP..BTN_DPAD_RIGHT) -> false
        side == SIDE_RIGHT && code == BTN_TR2 -> false
        else -> true
    }

    private fun InputDevice.isGameController() =
        supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK)

    /** Keeps lines readable on tablets and in landscape, like the main screen. */
    private fun applyContentWidth() {
        val width = if (resources.configuration.screenWidthDp > WIDE_SCREEN_DP) {
            (MAX_CONTENT_DP * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (content.layoutParams.width != width) content.layoutParams = content.layoutParams.apply { this.width = width }
    }

    private companion object {
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1
        const val DST_DEFAULT = -2  // no override; the row is not persisted
        const val DST_UNMAP = -1
        const val STATUS_POLL_MS = 500L
        const val CAPTURE_POLL_MS = 100L
        const val CAPTURE_TIMEOUT_MS = 5000L
        const val WIDE_SCREEN_DP = 640
        const val MAX_CONTENT_DP = 600

        // evdev codes, from linux/input-event-codes.h
        const val BTN_Z = 309
        const val BTN_TL2 = 312
        const val BTN_TR2 = 313
        const val BTN_DPAD_UP = 544
        const val BTN_DPAD_RIGHT = 547

        /** The only codes both identities advertise and report; see merger.c. */
        val OUTPUTS = listOf(
            Output(304, R.string.remap_out_a),
            Output(305, R.string.remap_out_b),
            Output(306, R.string.remap_out_c),
            Output(307, R.string.remap_out_x),
            Output(308, R.string.remap_out_y),
            Output(310, R.string.remap_out_l),
            Output(311, R.string.remap_out_r),
            Output(314, R.string.remap_out_select),
            Output(315, R.string.remap_out_start),
            Output(316, R.string.remap_out_home),
            Output(317, R.string.remap_out_l3),
            Output(318, R.string.remap_out_r3),
        )

        val CODE_NAMES = mapOf(
            304 to "BTN_SOUTH", 305 to "BTN_EAST", 306 to "BTN_C", 307 to "BTN_NORTH", 308 to "BTN_WEST",
            309 to "BTN_Z", 310 to "BTN_TL", 311 to "BTN_TR", 312 to "BTN_TL2", 313 to "BTN_TR2",
            314 to "BTN_SELECT", 315 to "BTN_START", 316 to "BTN_MODE", 317 to "BTN_THUMBL", 318 to "BTN_THUMBR",
            544 to "BTN_DPAD_UP", 545 to "BTN_DPAD_DOWN", 546 to "BTN_DPAD_LEFT", 547 to "BTN_DPAD_RIGHT",
        )
    }
}