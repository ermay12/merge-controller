package com.ermay12.mergecontroller

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import com.ermay12.mergecontroller.MergeServiceClient.ShizukuState
import kotlin.math.abs

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private enum class Step { TODO, DONE, PROBLEM }

    /** One Joy-Con as Android sees it. */
    private enum class JoyCon {
        MISSING,

        /** Paired, but the kernel has no hid-nintendo driver, so it's not a gamepad merger.c can use. */
        UNSUPPORTED,
        CONNECTED,
        MERGED;

        val present get() = this == CONNECTED || this == MERGED
    }

    private enum class ShizukuAction { GET, OPEN, ALLOW }

    private lateinit var client: MergeServiceClient
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var content: View
    private lateinit var step1Badge: TextView
    private lateinit var step1Title: TextView
    private lateinit var step1Body: View
    private lateinit var step1Text: TextView
    private lateinit var step1Note: TextView
    private lateinit var shizukuButton: Button
    private lateinit var shizukuGuide: Button
    private lateinit var step2Badge: TextView
    private lateinit var step2Title: TextView
    private lateinit var step2Body: View
    private lateinit var leftDot: View
    private lateinit var leftStatus: TextView
    private lateinit var rightDot: View
    private lateinit var rightStatus: TextView
    private lateinit var joyConUnsupported: View
    private lateinit var joyConHelp: View
    private lateinit var mergeDot: View
    private lateinit var mergeTitle: TextView
    private lateinit var mergeProgress: ProgressBar
    private lateinit var mergeBody: TextView
    private lateinit var mergeNote: View
    private lateinit var mergeButton: Button
    private lateinit var errorBox: View
    private lateinit var errorDetail: TextView
    private lateinit var orderTip: View
    private lateinit var orderText: TextView
    private lateinit var reconnectButton: Button
    private lateinit var reconnectProgress: ProgressBar
    private lateinit var nintendoLayout: Switch
    private lateinit var rumble: Switch
    private lateinit var xboxIdentity: Switch
    private lateinit var testRumbleButton: Button
    private lateinit var testRumbleHint: TextView
    private lateinit var inputDot: View
    private lateinit var inputSource: TextView
    private lateinit var inputButtons: TextView
    private lateinit var inputAxes: TextView
    private lateinit var advancedHeader: View
    private lateinit var advancedChevron: ImageView
    private lateinit var advancedBody: View
    private lateinit var diagShizuku: TextView
    private lateinit var diagPaths: TextView
    private lateinit var orderStatus: TextView
    private lateinit var deviceList: TextView

    private var shizukuInstalled = false
    private var shizukuAction: ShizukuAction? = null
    private var lastInputDeviceId = -1
    private val heldKeys = sortedSetOf<Int>()
    private val axes = FloatArray(AXES.size)
    private val loggedAxes = FloatArray(AXES.size)

    private val statusPoller = object : Runnable {
        override fun run() {
            // Until Shizuku is ready, also notice it being installed, started or allowed
            // (possibly in split screen, without this screen pausing).
            if (client.shizukuState == ShizukuState.READY) {
                client.refresh()
            } else {
                shizukuInstalled = isShizukuInstalled()
                client.recheck()
            }
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        step1Badge = findViewById(R.id.step1_badge)
        step1Title = findViewById(R.id.step1_title)
        step1Body = findViewById(R.id.step1_body)
        step1Text = findViewById(R.id.step1_text)
        step1Note = findViewById(R.id.step1_note)
        shizukuButton = findViewById(R.id.shizuku_action)
        shizukuGuide = findViewById(R.id.shizuku_guide)
        step2Badge = findViewById(R.id.step2_badge)
        step2Title = findViewById(R.id.step2_title)
        step2Body = findViewById(R.id.step2_body)
        leftDot = findViewById(R.id.left_dot)
        leftStatus = findViewById(R.id.left_status)
        rightDot = findViewById(R.id.right_dot)
        rightStatus = findViewById(R.id.right_status)
        joyConUnsupported = findViewById(R.id.joycon_unsupported)
        joyConHelp = findViewById(R.id.joycon_help)
        mergeDot = findViewById(R.id.merge_dot)
        mergeTitle = findViewById(R.id.merge_title)
        mergeProgress = findViewById(R.id.merge_progress)
        mergeBody = findViewById(R.id.merge_body)
        mergeNote = findViewById(R.id.merge_note)
        mergeButton = findViewById(R.id.merge_button)
        errorBox = findViewById(R.id.error_box)
        errorDetail = findViewById(R.id.error_detail)
        orderTip = findViewById(R.id.order_tip)
        orderText = findViewById(R.id.order_text)
        reconnectButton = findViewById(R.id.reconnect_joycons)
        reconnectProgress = findViewById(R.id.reconnect_progress)
        nintendoLayout = findViewById(R.id.nintendo_layout)
        rumble = findViewById(R.id.rumble)
        xboxIdentity = findViewById(R.id.xbox_identity)
        testRumbleButton = findViewById(R.id.test_rumble)
        testRumbleHint = findViewById(R.id.test_rumble_hint)
        inputDot = findViewById(R.id.input_dot)
        inputSource = findViewById(R.id.input_source)
        inputButtons = findViewById(R.id.input_buttons)
        inputAxes = findViewById(R.id.input_axes)
        advancedHeader = findViewById(R.id.advanced_header)
        advancedChevron = findViewById(R.id.advanced_chevron)
        advancedBody = findViewById(R.id.advanced_body)
        diagShizuku = findViewById(R.id.diag_shizuku)
        diagPaths = findViewById(R.id.diag_paths)
        orderStatus = findViewById(R.id.order_status)
        deviceList = findViewById(R.id.device_list)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        inputManager = getSystemService(InputManager::class.java)
        client = MergeServiceClient(this, ::mergeFlags, ::render)

        xboxIdentity.setLabel(R.string.xbox_identity, R.string.xbox_identity_hint)
        nintendoLayout.setLabel(R.string.nintendo_layout, R.string.nintendo_layout_hint)
        rumble.setLabel(R.string.rumble, R.string.rumble_hint)
        bindSetting(xboxIdentity, PREF_XBOX_IDENTITY, default = true)
        bindSetting(nintendoLayout, PREF_NINTENDO_LAYOUT, default = false)
        bindSetting(rumble, PREF_RUMBLE, default = true)

        findViewById<Button>(R.id.help_button).setOnClickListener { openUrl(README_URL) }
        shizukuButton.setOnClickListener { onShizukuAction() }
        shizukuGuide.setOnClickListener { openUrl(SHIZUKU_GUIDE_URL) }
        findViewById<Button>(R.id.bluetooth_button).setOnClickListener {
            open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        mergeButton.setOnClickListener {
            if (client.status?.running == true) client.stop() else client.start(mergeFlags())
        }
        findViewById<Button>(R.id.report_button).setOnClickListener { openUrl(ISSUES_URL) }
        reconnectButton.setOnClickListener { client.reconnectJoyCons() }
        testRumbleButton.setOnClickListener { testRumble() }
        advancedHeader.setOnClickListener {
            prefs.edit().putBoolean(PREF_ADVANCED_EXPANDED, !advancedExpanded()).apply()
            renderAdvancedToggle()
            render()
        }
        findViewById<Button>(R.id.footer).apply {
            text = getString(R.string.footer, BuildConfig.VERSION_NAME)
            setOnClickListener { openUrl(REPO_URL) }
        }
        renderAdvancedToggle()
        applyContentWidth()

        client.register()
        render()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, handler)
        shizukuInstalled = isShizukuInstalled()
        client.recheck()
        handler.post(statusPoller)
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacks(statusPoller)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client.release(endIdleService = isFinishing)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyContentWidth()
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
                if (BuildConfig.DEBUG) Log.i(TAG, "key ${device.name}: ${keyName(event.keyCode)} DOWN")
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(event.keyCode)
                if (BuildConfig.DEBUG) Log.i(TAG, "key ${device.name}: ${keyName(event.keyCode)} UP")
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
        if (BuildConfig.DEBUG && AXES.indices.any { abs(axes[it] - loggedAxes[it]) >= AXIS_LOG_STEP }) {
            axes.copyInto(loggedAxes)
            val values = AXES.indices.joinToString(" ") {
                "${MotionEvent.axisToString(AXES[it]).removePrefix("AXIS_")}=%.2f".format(axes[it])
            }
            Log.i(TAG, "motion ${event.device?.name}: $values")
        }
        renderInput(event.device)
        return true
    }

    private fun mergeFlags(): Int {
        var flags = 0
        if (nintendoLayout.isChecked) flags = flags or MergeStatus.FLAG_NINTENDO_LAYOUT
        if (rumble.isChecked) flags = flags or MergeStatus.FLAG_RUMBLE
        if (xboxIdentity.isChecked) flags = flags or MergeStatus.FLAG_XBOX_IDENTITY
        return flags
    }

    private fun bindSetting(switch: Switch, key: String, default: Boolean) {
        switch.isChecked = prefs.getBoolean(key, default)
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            client.setFlags(mergeFlags())
            render()
        }
    }

    private fun onShizukuAction() {
        when (shizukuAction) {
            ShizukuAction.GET -> openUrl(SHIZUKU_PLAY_URL)
            ShizukuAction.OPEN -> packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(::open)
            ShizukuAction.ALLOW -> client.requestPermission()
            null -> Unit
        }
    }

    /** Vibrates the merged controller through Android's API: each motor in turn, then both. */
    private fun testRumble() {
        val device = gameControllers().firstOrNull { MergeStatus.isMergedController(it) } ?: return
        val manager = device.vibratorManager
        val pulse = VibrationEffect.createOneShot(TEST_PULSE_MS, 255)
        val steps = manager.vibratorIds.map { id ->
            CombinedVibration.startParallel().addVibrator(id, pulse).combine()
        } + CombinedVibration.createParallel(pulse)
        steps.forEachIndexed { i, vibration ->
            handler.postDelayed({ manager.vibrate(vibration) }, i * TEST_STEP_MS)
        }
        Log.i(TAG, "rumble test on ${device.name}: vibrators ${manager.vibratorIds.toList()}")
    }

    private fun noteInputDevice(deviceId: Int) {
        if (deviceId == lastInputDeviceId) return
        lastInputDeviceId = deviceId
        heldKeys.clear()
        axes.fill(0f)
    }

    private fun render() {
        val status = client.status
        val running = status?.running == true
        val devices = inputManager.inputDeviceIds.asList().mapNotNull { inputManager.getInputDevice(it) }
        val controllers = devices.filter { it.isGameController() }
        val merged = controllers.firstOrNull { MergeStatus.isMergedController(it) }
        val left = joyConState(status?.left, MergeStatus.LEFT_JOYCON_NAME, LEFT_JOYCON_PRODUCT_ID, devices)
        val right = joyConState(status?.right, MergeStatus.RIGHT_JOYCON_NAME, RIGHT_JOYCON_PRODUCT_ID, devices)

        renderShizukuStep()
        renderJoyConStep(left, right)
        renderMerge(status, left, right)
        renderOrder(running, merged, controllers)

        testRumbleButton.isEnabled = running && merged != null && rumble.isChecked
        testRumbleHint.setTextIfChanged(
            getString(
                when {
                    !running || merged == null -> R.string.test_rumble_needs_merge
                    !rumble.isChecked -> R.string.test_rumble_needs_rumble
                    else -> R.string.test_rumble_hint
                },
            ),
        )

        if (advancedExpanded()) renderAdvanced(status, controllers)
    }

    private fun renderShizukuStep() {
        val state = client.shizukuState
        if (state == ShizukuState.READY && !prefs.getBoolean(PREF_SHIZUKU_WAS_READY, false)) {
            prefs.edit().putBoolean(PREF_SHIZUKU_WAS_READY, true).apply()
        }
        val wasReady = prefs.getBoolean(PREF_SHIZUKU_WAS_READY, false)
        val (title, body, action) = when {
            state == ShizukuState.READY -> Triple(R.string.step1_done_title, null, null)
            state == ShizukuState.PERMISSION_NEEDED ->
                Triple(R.string.step1_allow_title, R.string.step1_allow_body, ShizukuAction.ALLOW)
            // Without the Shizuku app (e.g. with Sui) there's nothing to open.
            state == ShizukuState.PERMISSION_DENIED ->
                Triple(R.string.step1_denied_title, R.string.step1_denied_body, ShizukuAction.OPEN.takeIf { shizukuInstalled })
            !shizukuInstalled -> Triple(R.string.step1_install_title, R.string.step1_install_body, ShizukuAction.GET)
            wasReady -> Triple(R.string.step1_restart_title, R.string.step1_restart_body, ShizukuAction.OPEN)
            else -> Triple(R.string.step1_start_title, R.string.step1_start_body, ShizukuAction.OPEN)
        }
        val step = when (state) {
            ShizukuState.READY -> Step.DONE
            ShizukuState.PERMISSION_DENIED -> Step.PROBLEM
            else -> Step.TODO
        }
        renderStep(step1Badge, step1Title, R.string.step_number_1, step, title)
        step1Body.visibleIf(step != Step.DONE)
        body?.let { step1Text.setTextIfChanged(getString(it)) }
        step1Note.visibleIf(title == R.string.step1_start_title)
        shizukuGuide.visibleIf(state == ShizukuState.NOT_RUNNING)
        shizukuAction = action
        shizukuButton.visibleIf(action != null)
        if (action != null) {
            shizukuButton.setTextIfChanged(
                getString(
                    when (action) {
                        ShizukuAction.GET -> R.string.get_shizuku
                        ShizukuAction.OPEN -> R.string.open_shizuku
                        ShizukuAction.ALLOW -> R.string.allow_access
                    },
                ),
            )
        }
    }

    private fun renderJoyConStep(left: JoyCon, right: JoyCon) {
        val unsupported = left == JoyCon.UNSUPPORTED || right == JoyCon.UNSUPPORTED
        val step = when {
            left.present && right.present -> Step.DONE
            unsupported -> Step.PROBLEM
            else -> Step.TODO
        }
        val title = when {
            step == Step.DONE -> R.string.step2_done_title
            unsupported -> R.string.joycon_unsupported_title
            right.present -> R.string.step2_title_left
            left.present -> R.string.step2_title_right
            else -> R.string.step2_title_both
        }
        renderStep(step2Badge, step2Title, R.string.step_number_2, step, title)
        step2Body.visibleIf(step != Step.DONE)
        renderJoyConRow(leftDot, leftStatus, left)
        renderJoyConRow(rightDot, rightStatus, right)
        joyConUnsupported.visibleIf(unsupported)
        joyConHelp.visibleIf(!unsupported)
    }

    private fun renderJoyConRow(dot: View, label: TextView, joyCon: JoyCon) {
        dot.backgroundTintList = getColorStateList(
            when (joyCon) {
                JoyCon.MISSING -> R.color.status_idle
                JoyCon.UNSUPPORTED -> R.color.status_error
                JoyCon.CONNECTED, JoyCon.MERGED -> R.color.status_ok
            },
        )
        label.setTextIfChanged(
            getString(
                when (joyCon) {
                    JoyCon.MISSING -> R.string.joycon_missing
                    JoyCon.UNSUPPORTED -> R.string.joycon_unsupported_status
                    JoyCon.CONNECTED -> R.string.joycon_connected
                    JoyCon.MERGED -> R.string.joycon_merged
                },
            ),
        )
    }

    private fun renderMerge(status: MergeStatus?, left: JoyCon, right: JoyCon) {
        val running = status?.running == true
        val ready = client.shizukuState == ShizukuState.READY
        val error = client.error ?: status?.error?.ifEmpty { null }
        val (dot, title, body) = when {
            client.starting -> Triple(R.color.status_wait, R.string.merge_starting, getString(R.string.merge_starting_body))
            status != null && running && status.left != null && status.right != null ->
                Triple(R.color.status_ok, R.string.merge_on, getString(R.string.merge_on_body, status.virtualName))
            status != null && running && status.left != null ->
                Triple(R.color.status_wait, R.string.merge_waiting_right, getString(R.string.merge_waiting_one_body))
            status != null && running && status.right != null ->
                Triple(R.color.status_wait, R.string.merge_waiting_left, getString(R.string.merge_waiting_one_body))
            running -> Triple(R.color.status_wait, R.string.merge_waiting, getString(R.string.merge_waiting_body))
            !ready -> Triple(R.color.status_idle, R.string.merge_off, getString(R.string.merge_off_blocked))
            left.present && right.present ->
                Triple(R.color.status_idle, R.string.merge_off, getString(R.string.merge_off_ready))
            else -> Triple(R.color.status_idle, R.string.merge_off, getString(R.string.merge_off_ready_partial))
        }
        mergeDot.backgroundTintList = getColorStateList(if (error != null && !running) R.color.status_error else dot)
        mergeTitle.setTextIfChanged(getString(title))
        mergeProgress.visibleIf(client.starting)
        mergeBody.setTextIfChanged(body)
        mergeNote.visibleIf(title == R.string.merge_on)
        mergeButton.setTextIfChanged(getString(if (running) R.string.stop_merging else R.string.start_merging))
        mergeButton.isEnabled = ready && !client.starting
        mergeButton.setTonal(running)
        errorBox.visibleIf(error != null)
        errorDetail.setTextIfChanged(error.orEmpty())
    }

    /**
     * Some games give player 1 to the gamepad with the lowest device ID, which
     * the Joy-Cons have if they connected before the virtual controller existed.
     */
    private fun renderOrder(running: Boolean, merged: InputDevice?, controllers: List<InputDevice>) {
        val listedFirst = merged != null && controllers.none { it.name in JOYCON_NAMES && it.id < merged.id }
        orderTip.visibleIf(client.reconnecting || (running && merged != null && !listedFirst))
        orderText.setTextIfChanged(getString(if (client.reconnecting) R.string.reconnecting else R.string.order_tip_body))
        reconnectButton.visibleIf(!client.reconnecting)
        reconnectProgress.visibleIf(client.reconnecting)
        orderStatus.visibleIf(running && merged != null)
        orderStatus.setTextIfChanged(getString(if (listedFirst) R.string.order_first else R.string.order_after))
    }

    private fun renderAdvanced(status: MergeStatus?, controllers: List<InputDevice>) {
        val access = when {
            client.shizukuState != ShizukuState.READY -> R.string.diag_shizuku_none
            client.shizukuUid == 0 -> R.string.diag_shizuku_root
            else -> R.string.diag_shizuku_shell
        }
        diagShizuku.setTextIfChanged(getString(R.string.diag_shizuku, getString(access)))

        val notMerged = getString(R.string.diag_not_merged)
        val virtual = if (status?.running == true) {
            "${status.virtualName} (${status.virtualPath})"
        } else {
            getString(R.string.diag_not_running)
        }
        diagPaths.setTextIfChanged(
            listOf(
                getString(R.string.diag_left, status?.left?.path ?: notMerged),
                getString(R.string.diag_right, status?.right?.path ?: notMerged),
                getString(R.string.diag_virtual, virtual),
            ).joinToString("\n"),
        )

        val hiddenNames = setOfNotNull(status?.left?.name, status?.right?.name)
        val list = SpannableStringBuilder()
        for (device in controllers) {
            if (list.isNotEmpty()) list.append('\n')
            list.append(device.name)
            val tag = when {
                MergeStatus.isMergedController(device) -> R.string.device_tag_virtual
                device.name in hiddenNames -> R.string.device_tag_hidden
                else -> continue
            }
            val start = list.append('\n').length
            list.append(getString(tag))
            list.setSpan(TextAppearanceSpan(this, R.style.SummaryAppearance), start, list.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        deviceList.setTextIfChanged(list.ifEmpty { getString(R.string.devices_none) })
    }

    private fun renderInput(device: InputDevice?) {
        val name = device?.name ?: "?"
        val (dot, source) = when {
            device != null && MergeStatus.isMergedController(device) ->
                R.color.status_ok to getString(R.string.input_from_merged, name)
            client.status?.running == true -> R.color.status_error to getString(R.string.input_from_unmerged, name)
            else -> R.color.status_idle to getString(R.string.input_from, name)
        }
        inputDot.visibility = View.VISIBLE
        inputDot.backgroundTintList = getColorStateList(dot)
        inputSource.setTextIfChanged(source)
        inputButtons.visibility = View.VISIBLE
        inputButtons.setTextIfChanged(
            if (heldKeys.isEmpty()) {
                getString(R.string.input_held_none)
            } else {
                getString(R.string.input_held, heldKeys.joinToString("  ") { keyName(it) })
            },
        )
        inputAxes.visibility = View.VISIBLE
        inputAxes.setTextIfChanged(getString(R.string.input_axes, *axes.toTypedArray()))
    }

    private fun renderStep(badge: TextView, title: TextView, numberRes: Int, step: Step, titleRes: Int) {
        badge.setTextIfChanged(if (step == Step.TODO) getString(numberRes) else "")
        val background = when (step) {
            Step.TODO -> R.drawable.badge_todo
            Step.DONE -> R.drawable.badge_done
            Step.PROBLEM -> R.drawable.badge_problem
        }
        if (badge.tag != background) {
            badge.tag = background
            badge.setBackgroundResource(background)
        }
        title.setTextIfChanged(getString(titleRes))
        title.stateDescription = getString(
            when (step) {
                Step.TODO -> R.string.a11y_step_todo
                Step.DONE -> R.string.a11y_step_done
                Step.PROBLEM -> R.string.a11y_step_problem
            },
        )
    }

    private fun advancedExpanded() = prefs.getBoolean(PREF_ADVANCED_EXPANDED, false)

    private fun renderAdvancedToggle() {
        val expanded = advancedExpanded()
        advancedBody.visibleIf(expanded)
        advancedChevron.rotation = if (expanded) 180f else 0f
        advancedHeader.stateDescription = getString(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    }

    /** Keeps lines readable on tablets and in landscape. */
    private fun applyContentWidth() {
        val width = if (resources.configuration.screenWidthDp > WIDE_SCREEN_DP) {
            (MAX_CONTENT_DP * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (content.layoutParams.width != width) content.layoutParams = content.layoutParams.apply { this.width = width }
    }

    private fun joyConState(source: MergeStatus.Source?, name: String, productId: Int, devices: List<InputDevice>) =
        when {
            source != null -> JoyCon.MERGED
            devices.any { it.name == name } -> JoyCon.CONNECTED
            devices.any { it.vendorId == NINTENDO_VENDOR_ID && it.productId == productId } -> JoyCon.UNSUPPORTED
            else -> JoyCon.MISSING
        }

    private fun isShizukuInstalled() =
        runCatching { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    private fun gameControllers(): List<InputDevice> =
        inputManager.inputDeviceIds.asList()
            .mapNotNull { inputManager.getInputDevice(it) }
            .filter { it.isGameController() }

    private fun InputDevice.isGameController() =
        supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK)

    private fun keyName(keyCode: Int) =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_")

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nothing can open $intent", e)
        }
    }

    private fun openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** Title on the first line, summary below, so the whole block toggles the switch and is read together. */
    private fun Switch.setLabel(titleRes: Int, summaryRes: Int) {
        val label = SpannableStringBuilder(getString(titleRes)).append('\n')
        val start = label.length
        label.append(getString(summaryRes))
        label.setSpan(TextAppearanceSpan(context, R.style.SummaryAppearance), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = label
    }

    private fun Button.setTonal(tonal: Boolean) {
        if (tag == tonal) return
        tag = tonal
        setBackgroundResource(if (tonal) R.drawable.btn_tonal else R.drawable.btn_primary)
        setTextColor(context.getColorStateList(if (tonal) R.color.btn_tonal_text else R.color.btn_primary_text))
    }

    /** Render runs every STATUS_POLL_MS; unchanged text mustn't make TalkBack re-announce live regions. */
    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private fun View.visibleIf(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "MergeController"
        private const val PREFS_NAME = "settings"
        private const val PREF_NINTENDO_LAYOUT = "nintendo_layout"
        private const val PREF_RUMBLE = "rumble"
        private const val PREF_XBOX_IDENTITY = "xbox_identity"
        private const val PREF_SHIZUKU_WAS_READY = "shizuku_was_ready"
        private const val PREF_ADVANCED_EXPANDED = "advanced_expanded"
        private const val TEST_PULSE_MS = 400L
        private const val TEST_STEP_MS = 700L
        private const val STATUS_POLL_MS = 500L
        private const val AXIS_LOG_STEP = 0.25f
        private const val WIDE_SCREEN_DP = 640
        private const val MAX_CONTENT_DP = 600

        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PLAY_URL = "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"
        private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/guide/setup/"
        private const val REPO_URL = "https://github.com/ermay12/merge-controller"
        private const val README_URL = "$REPO_URL#readme"
        private const val ISSUES_URL = "$REPO_URL/issues"

        // Joy-Cons as the Bluetooth HID layer reports them, with or without hid-nintendo.
        private const val NINTENDO_VENDOR_ID = 0x057e
        private const val LEFT_JOYCON_PRODUCT_ID = 0x2006
        private const val RIGHT_JOYCON_PRODUCT_ID = 0x2007
        private val JOYCON_NAMES = setOf(MergeStatus.LEFT_JOYCON_NAME, MergeStatus.RIGHT_JOYCON_NAME)

        // Order must match the placeholders in R.string.input_axes.
        private val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )
    }
}
