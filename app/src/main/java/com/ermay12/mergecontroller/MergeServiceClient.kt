package com.ermay12.mergecontroller

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/** Tracks Shizuku's state and talks to [MergeUserService]. All callbacks run on the main thread. */
class MergeServiceClient(context: Context, private val onChanged: () -> Unit) {

    enum class ShizukuState { NOT_RUNNING, PERMISSION_NEEDED, PERMISSION_DENIED, READY }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var shizukuState = ShizukuState.NOT_RUNNING
        private set
    var shizukuUid = -1
        private set
    var status: MergeStatus? = null
        private set
    var error: String? = null
        private set
    var starting = false
        private set

    private var service: IMergeService? = null
    private var pendingStartFlags: Int? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(appContext, MergeUserService::class.java))
        .daemon(true)
        .processNameSuffix("merger")
        .debuggable(BuildConfig.DEBUG)
        .version(serviceVersion())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mainHandler.post { onConnected(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainHandler.post {
                service = null
                status = null
                starting = false
                onChanged()
            }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { updateShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        status = null
        updateShizukuState()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST_CODE) updateShizukuState()
    }

    fun register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived, mainHandler)
        Shizuku.addBinderDeadListener(binderDead, mainHandler)
        Shizuku.addRequestPermissionResultListener(permissionResult, mainHandler)
    }

    fun unregister() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (service != null && Shizuku.pingBinder()) {
            // Detach only; the daemon keeps merging without the app.
            runCatching { Shizuku.unbindUserService(args, connection, false) }
        }
        service = null
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

    fun start(flags: Int) {
        error = null
        val s = service
        if (s != null) {
            startOn(s, flags)
            return
        }
        pendingStartFlags = flags
        starting = true
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            starting = false
            pendingStartFlags = null
            error = it.message
        }
        onChanged()
    }

    fun stop() {
        service?.let { s -> runCatching { s.stop() } }
        // Removing the service also ends its shell process.
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        status = null
        onChanged()
    }

    fun setFlags(flags: Int) {
        service?.let { s -> runCatching { s.setFlags(flags) } }
    }

    fun refresh() {
        val s = service ?: return
        runCatching { MergeStatus.parse(s.status()) }
            .onSuccess { status = it }
            .onFailure { error = it.message }
        onChanged()
    }

    private fun updateShizukuState() {
        shizukuState = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PERMISSION_DENIED
            else -> ShizukuState.PERMISSION_NEEDED
        }
        shizukuUid = if (shizukuState == ShizukuState.READY) Shizuku.getUid() else -1
        if (shizukuState == ShizukuState.READY && service == null) {
            // Reattach to a merge left running by an earlier session; never spawns a new process.
            runCatching { Shizuku.peekUserService(args, connection) }
        }
        onChanged()
    }

    private fun onConnected(binder: IBinder?) {
        if (binder == null || !binder.pingBinder()) return
        val s = IMergeService.Stub.asInterface(binder)
        service = s
        starting = false
        val flags = pendingStartFlags
        pendingStartFlags = null
        if (flags != null) startOn(s, flags) else refresh()
    }

    private fun startOn(s: IMergeService, flags: Int) {
        val result = runCatching { s.start(flags) }.getOrElse { e ->
            error = e.message
            onChanged()
            return
        }
        refresh()
        if (result != 0) {
            Log.w(TAG, "start failed ($result): ${status?.error}")
            error = status?.error?.ifEmpty { null } ?: "Start failed (error $result)"
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            service = null
            status = null
            onChanged()
        }
    }

    /** Changes on every install, so Shizuku replaces a daemon still running older code. */
    private fun serviceVersion(): Int {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return (info.lastUpdateTime / 1000).toInt()
    }

    companion object {
        private const val TAG = "MergeController"
        private const val PERMISSION_REQUEST_CODE = 1
    }
}
