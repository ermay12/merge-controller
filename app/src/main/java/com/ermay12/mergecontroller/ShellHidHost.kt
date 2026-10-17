package com.ermay12.mergecontroller

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Android's Bluetooth HID host profile, used from [MergeUserService]'s shell
 * process. The calls used here are hidden from the SDK and need
 * BLUETOOTH_PRIVILEGED, which the shell user holds.
 */
class ShellHidHost {

    @Volatile private var proxy: BluetoothProfile? = null
    private var adapter: BluetoothAdapter? = null
    private var sendDataMethod: Method? = null
    private var disconnectMethod: Method? = null
    private var lastConnectFailure = 0L

    /** Sends an output report (hex, starting with the report ID) on the interrupt channel. */
    @Synchronized
    fun sendData(address: String, reportHex: String): Boolean = call(address) { host, device ->
        sendDataMethod?.invoke(host, device, reportHex) as? Boolean ?: false
    }

    @Synchronized
    fun disconnect(address: String): Boolean = call(address) { host, device ->
        disconnectMethod?.invoke(host, device) as? Boolean ?: false
    }

    private fun call(address: String, action: (BluetoothProfile, BluetoothDevice) -> Boolean): Boolean {
        val host = connect() ?: return false
        val device = adapter?.getRemoteDevice(address.uppercase()) ?: return false
        return try {
            action(host, device)
        } catch (e: Exception) {
            Log.w(TAG, "HID host call for $address failed", e)
            proxy = null
            false
        }
    }

    private fun connect(): BluetoothProfile? {
        proxy?.let { return it }
        if (SystemClock.elapsedRealtime() - lastConnectFailure < RETRY_DELAY_MS) return null
        return try {
            val adapter = adapter ?: createAdapter().also { adapter = it }
            val connected = CompletableFuture<BluetoothProfile>()
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    connected.complete(p)
                }

                override fun onServiceDisconnected(profile: Int) {
                    proxy = null
                }
            }
            check(adapter.getProfileProxy(ShellContext, listener, HID_HOST)) { "getProfileProxy failed" }
            val host = connected.get(PROXY_TIMEOUT_S, TimeUnit.SECONDS)
            sendDataMethod = host.javaClass.getMethod("sendData", BluetoothDevice::class.java, String::class.java)
            disconnectMethod = host.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            Log.i(TAG, "connected to Bluetooth HID host")
            host.also { proxy = it }
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth HID host unavailable", e)
            lastConnectFailure = SystemClock.elapsedRealtime()
            null
        }
    }

    private fun createAdapter(): BluetoothAdapter {
        // App processes register this during ActivityThread startup, which this process skips.
        val initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
        if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
            val managerClass = Class.forName("android.os.BluetoothServiceManager")
            val manager = managerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            initializer.getMethod("setBluetoothServiceManager", managerClass).invoke(null, manager)
        }
        // The Bluetooth service rejects calls whose AttributionSource doesn't match
        // the calling uid, so give the adapter an explicit identity.
        val create = BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", Context::class.java)
        create.isAccessible = true
        return create.invoke(null, ShellContext) as BluetoothAdapter
    }

    /** Just enough Context for BluetoothAdapter, identifying as the package that owns this uid. */
    private object ShellContext : ContextWrapper(null) {
        private val isShell = Process.myUid() == SHELL_UID

        override fun getPackageName() = SHELL_PACKAGE

        override fun getOpPackageName() = SHELL_PACKAGE

        override fun getAttributionSource(): AttributionSource =
            if (isShell) {
                AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            } else {
                AttributionSource.myAttributionSource()
            }

        override fun getApplicationContext(): Context = this

        override fun getMainExecutor() = Executor { it.run() }

        override fun getApplicationInfo() = ApplicationInfo().apply {
            packageName = SHELL_PACKAGE
            uid = Process.myUid()
            targetSdkVersion = Build.VERSION.SDK_INT
        }
    }

    companion object {
        private const val TAG = "ShellHidHost"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val HID_HOST = 4  // BluetoothProfile.HID_HOST, hidden from the SDK
        private const val PROXY_TIMEOUT_S = 3L
        private const val RETRY_DELAY_MS = 5000L
    }
}
