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
import kotlin.concurrent.thread

/** Tracks Shizuku's state and talks to [MergeUserService]. All callbacks run on the main thread. */
class MergeServiceClient(
    context: Context,
    private val currentFlags: () -> Int,
    private val onChanged: () -> Unit,
) {

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
    var reconnecting = false
        private set

    private var service: IMergeService? = null
    private var pendingStartFlags: Int? = null

    // Shizuku's client library reuses one connection object per service and
    // tears it down (dropping our ServiceConnection) if the removed service's
    // death is reported, which can happen late or not at all. A bind made before
    // that would be lost, so starts wait for the report or a timeout.
    private var removing = false
    private var startAfterRemoval: Int? = null
    private val finishRemoval = Runnable {
        removing = false
        startAfterRemoval?.let {
            startAfterRemoval = null
            start(it)
        }
        onChanged()
    }

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
            // Posted, so it runs after the library has finished discarding the old connection.
            mainHandler.post {
                service = null
                status = null
                starting = false
                if (removing) {
                    mainHandler.removeCallbacks(finishRemoval)
                    finishRemoval.run()
                } else {
                    onChanged()
                }
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

    /**
     * Detaches from the service. A merging service keeps running without the
     * app; an idle one is ended if [endIdleService].
     */
    fun release(endIdleService: Boolean) {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        mainHandler.removeCallbacks(finishRemoval)
        if (service != null && Shizuku.pingBinder()) {
            val remove = endIdleService && status?.running != true
            runCatching { Shizuku.unbindUserService(args, connection, remove) }
        }
        service = null
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

    fun start(flags: Int) {
        error = null
        if (removing) {
            startAfterRemoval = flags
            starting = true
            onChanged()
            return
        }
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

    /** Stops merging. The idle service process stays up for a quick restart until [release]. */
    fun stop() {
        val s = service ?: return
        runCatching { s.stop() }.onFailure { error = it.message }
        refresh()
    }

    /** See [IMergeService.reconnectJoyCons]. Runs off the main thread because it blocks. */
    fun reconnectJoyCons() {
        val s = service ?: return
        reconnecting = true
        error = null
        onChanged()
        thread(name = "reconnectJoyCons") {
            val ok = runCatching { s.reconnectJoyCons() }.getOrDefault(false)
            mainHandler.post {
                reconnecting = false
                if (!ok) error = appContext.getString(R.string.reconnect_failed)
                refresh()
            }
        }
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
        if (shizukuState == ShizukuState.READY && service == null && !removing) {
            // Reattach to a merge left running by an earlier session; never spawns a new process.
            runCatching { Shizuku.peekUserService(args, connection) }
        }
        onChanged()
    }

    private fun onConnected(binder: IBinder?) {
        if (binder == null || !binder.pingBinder()) return
        val s = IMergeService.Stub.asInterface(binder)
        if (runCatching { s.buildTime() }.getOrNull() != BuildConfig.BUILD_TIME) {
            replaceOutdatedService(s)
            return
        }
        service = s
        starting = false
        val flags = pendingStartFlags
        pendingStartFlags = null
        if (flags != null) startOn(s, flags) else refresh()
    }

    /**
     * peekUserService() attaches to a daemon regardless of version, so after an
     * update it can find one still running the old code. Restart it with the
     * new code, resuming merging if it was on.
     */
    private fun replaceOutdatedService(old: IMergeService) {
        val wasRunning = runCatching { MergeStatus.parse(old.status()).running }.getOrDefault(false)
        Log.i(TAG, "replacing merge service from an older install (merging: $wasRunning)")
        runCatching { old.stop() }
        removeService()
        val flags = pendingStartFlags ?: if (wasRunning) currentFlags() else null
        pendingStartFlags = null
        if (flags != null) start(flags) else onChanged()
    }

    /** Removes the connected service, which also ends its shell process. Only used to replace an outdated one. */
    private fun removeService() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        status = null
        removing = true
        // In case the old service's death is never reported.
        mainHandler.postDelayed(finishRemoval, REMOVAL_TIMEOUT_MS)
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
        private const val REMOVAL_TIMEOUT_MS = 3000L
    }
}
