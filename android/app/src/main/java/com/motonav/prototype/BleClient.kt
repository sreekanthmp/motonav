package com.motonav.prototype

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * Minimal BLE central for the MotoNav device.
 *
 * - scans for the MotoNav service UUID and connects to the first device found
 * - auto-reconnects (until [disconnect] is called)
 * - keeps only the *latest* NavCore: the protocol sends full state, so a stale packet is useless
 *
 * Permissions (BLUETOOTH_SCAN / CONNECT, location on old Android) are requested in MainActivity.
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onState: (State) -> Unit,
    private val onDeviceEvent: (ByteArray) -> Unit,
) {
    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var state = State.DISCONNECTED
    private var gatt: BluetoothGatt? = null
    private var coreChar: BluetoothGattCharacteristic? = null
    private var streetChar: BluetoothGattCharacteristic? = null

    private var wantConnected = false
    private var scanning = false

    private var writing = false
    private var writeStartedMs = 0L
    private var pendingCore: ByteArray? = null
    private var pendingStreet: ByteArray? = null

    val isConnected: Boolean get() = state == State.CONNECTED

    // ---- public API (call from any thread) ------------------------------------------------

    fun connect() = handler.post {
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is off — turn it on first")
            return@post
        }
        wantConnected = true
        if (state == State.DISCONNECTED) startScan()
    }

    fun disconnect() = handler.post {
        wantConnected = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        stopScan()
        val g = gatt
        if (g != null) g.disconnect() else setState(State.DISCONNECTED)
    }

    fun sendCore(bytes: ByteArray) {
        handler.post {
            pendingCore = bytes
            pump()
        }
    }

    fun sendStreet(bytes: ByteArray) {
        handler.post {
            pendingStreet = if (bytes.isEmpty()) " ".toByteArray() else bytes
            pump()
        }
    }

    // ---- scanning / connecting ------------------------------------------------------------

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("No BLE scanner available")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NavProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            setState(State.SCANNING)
            log("Scanning for \"${NavProtocol.DEVICE_NAME}\"...")
            handler.postDelayed(bondedFallbackRunnable, 3_000)
            handler.postDelayed(scanHintRunnable, 10_000)
        } catch (e: SecurityException) {
            log("Scan blocked: missing Bluetooth permission")
        }
    }

    /**
     * A device that is already connected to this phone (e.g. paired in Android's Bluetooth settings) stops
     * advertising, so scanning never finds it. If it is paired with this phone, connect to it directly.
     */
    private val bondedFallbackRunnable = Runnable {
        if (state != State.SCANNING || !scanning) return@Runnable
        val bonded = try {
            adapter?.bondedDevices?.firstOrNull { it.name == NavProtocol.DEVICE_NAME }
        } catch (e: SecurityException) {
            null
        }
        if (bonded != null) {
            log("MotoNav is paired but not advertising (already connected) — connecting directly")
            stopScan()
            connectDevice(bonded)
        }
    }

    private val scanHintRunnable = Runnable {
        if (state == State.SCANNING && scanning) {
            log("Still scanning. Check the ESP32 is powered and shows \"Waiting for phone\". " +
                "If MotoNav is listed under Paired devices in Android's Bluetooth settings, tap its gear " +
                "and choose Forget, then press EN on the ESP32.")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(bondedFallbackRunnable)
        handler.removeCallbacks(scanHintRunnable)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                stopScan()
                connectDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                log("Scan failed (code $errorCode)")
                setState(State.DISCONNECTED)
                scheduleReconnect()
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        setState(State.CONNECTING)
        log("Found ${device.address}, connecting...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectTimeoutRunnable, 12_000)
    }

    private val connectTimeoutRunnable = Runnable {
        if (state == State.CONNECTING) {
            log("Connect timed out, retrying")
            gatt?.let { cleanup(it) }
            setState(State.DISCONNECTED)
            scheduleReconnect()
        }
    }

    private val reconnectRunnable = Runnable {
        if (wantConnected && state == State.DISCONNECTED) startScan()
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 1_500)
    }

    private fun cleanup(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: Exception) {
        }
        if (gatt === g) gatt = null
        coreChar = null
        streetChar = null
        writing = false
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        onState(s)
    }

    // ---- GATT callbacks (hop onto the main thread) ----------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("Connected, discovering services")
                        g.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        handler.removeCallbacks(connectTimeoutRunnable)
                        log("Disconnected (status $status)")
                        cleanup(g)
                        setState(State.DISCONNECTED)
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val svc = g.getService(NavProtocol.SERVICE_UUID)
                coreChar = svc?.getCharacteristic(NavProtocol.NAVCORE_UUID)
                streetChar = svc?.getCharacteristic(NavProtocol.NAVSTREET_UUID)
                if (svc == null || coreChar == null || streetChar == null) {
                    log("MotoNav service/characteristics not found — wrong device?")
                    g.disconnect()
                    return@post
                }
                val events = svc.getCharacteristic(NavProtocol.EVENTS_UUID)
                val cccd = events?.getDescriptor(CCCD_UUID)
                if (events != null && cccd != null) {
                    g.setCharacteristicNotification(events, true)
                    writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    onReady()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { onReady() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                writing = false
                pump()
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            handler.post { onDeviceEvent(v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { onDeviceEvent(value) }
        }
    }

    private fun onReady() {
        handler.removeCallbacks(connectTimeoutRunnable)
        writing = false
        setState(State.CONNECTED)
        log("Device ready")
        pump()
    }

    // ---- writing ---------------------------------------------------------------------------

    private fun pump() {
        if (state != State.CONNECTED) return
        val now = SystemClock.uptimeMillis()
        if (writing && now - writeStartedMs < 500) return   // previous write still in flight

        val street = pendingStreet
        val core = pendingCore
        val target: BluetoothGattCharacteristic?
        val value: ByteArray
        when {
            street != null -> {
                target = streetChar; value = street; pendingStreet = null
            }

            core != null -> {
                target = coreChar; value = core; pendingCore = null
            }

            else -> return
        }
        if (target == null) return
        writing = true
        writeStartedMs = now
        if (!writeCharacteristic(target, value)) {
            writing = false
            log("BLE write failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
