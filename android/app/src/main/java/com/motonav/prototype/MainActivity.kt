package com.motonav.prototype

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.model.LatLng
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode

class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleClient
    private var nav: NavController? = null

    private lateinit var tvBle: TextView
    private lateinit var tvLog: TextView
    private val logLines = ArrayDeque<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var testRunnable: Runnable? = null
    private var testSeq = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys.map { it.substringAfterLast('.') }
            log(if (denied.isEmpty()) "Permissions granted" else "Permissions denied: ${denied.joinToString()}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBle = findViewById(R.id.tvBle)
        tvLog = findViewById(R.id.tvLog)

        NavBridge.log = this::log
        ble = BleClient(
            applicationContext,
            log = this::log,
            onState = { state -> runOnUiThread { onBleState(state) } },
            onDeviceEvent = { bytes -> log("Device event: " + bytes.joinToString(" ") { "%02X".format(it) }) },
        )
        NavBridge.ble = ble

        askPermissions()

        click(R.id.btnConnect) { askPermissions(); ble.connect() }
        click(R.id.btnDisconnect) { stopTest(); ble.disconnect() }

        // 1) Display tests — no Google involved
        click(R.id.btnLeft) { startTest(Man.LEFT, 300, "Brigade Road") }
        click(R.id.btnRight) { startTest(Man.RIGHT, 80, "MG Road") }
        click(R.id.btnStraight) { startTest(Man.STRAIGHT, 1800, "Outer Ring Road") }
        click(R.id.btnRound) { startTest(Man.ROUNDABOUT, 150, "Silk Board Junction", exit = 2) }
        click(R.id.btnUturn) { startTest(Man.UTURN_LEFT, 60, "Hosur Road") }
        click(R.id.btnArrive) { startTest(Man.ARRIVE, 40, "Destination") }
        click(R.id.btnStopTest) { stopTest(); NavBridge.sendMode(Mode.IDLE) }

        // 2) Google Navigation SDK
        click(R.id.btnInitNav) {
            askPermissions()
            val controller = nav ?: NavController(this, this::log).also { nav = it }
            controller.init { log("Ready: pick Simulated route or Real navigation") }
        }
        click(R.id.btnSim) { startNavigation(simulate = true) }
        click(R.id.btnReal) { startNavigation(simulate = false) }
        click(R.id.btnStop) { stopTest(); nav?.stop() }

        log("MotoNav prototype ready. 1) Connect  2) test buttons  3) Init Navigation SDK")
    }

    override fun onDestroy() {
        stopTest()
        super.onDestroy()
    }

    // ---- UI helpers ---------------------------------------------------------------------

    private fun click(id: Int, action: () -> Unit) = findViewById<Button>(id).setOnClickListener { action() }

    private fun log(msg: String) {
        android.util.Log.d("MotoNav", msg)
        runOnUiThread {
            logLines.addLast(msg)
            while (logLines.size > 60) logLines.removeFirst()
            tvLog.text = logLines.joinToString("\n")
        }
    }

    private fun onBleState(state: BleClient.State) {
        tvBle.text = "Bluetooth: " + when (state) {
            BleClient.State.DISCONNECTED -> "disconnected"
            BleClient.State.SCANNING -> "scanning for MotoNav..."
            BleClient.State.CONNECTING -> "connecting..."
            BleClient.State.CONNECTED -> "CONNECTED to MotoNav"
        }
        if (state == BleClient.State.CONNECTED) NavBridge.onBleConnected()
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(p.toTypedArray())
    }

    // ---- display test (no Google) -------------------------------------------------------

    /** Re-sends the same scenario every second, counting the distance down, until stopped. */
    private fun startTest(maneuver: Int, startDistM: Long, street: String, exit: Int = 0) {
        stopTest()
        if (!ble.isConnected) log("Not connected yet — press Connect first")
        ble.sendStreet(NavProtocol.encodeStreet(street))
        var dist = startDistM
        val r = object : Runnable {
            override fun run() {
                val core = NavProtocol.encodeCore(
                    mode = Mode.NAV, maneuver = maneuver, roundaboutExit = exit, seq = testSeq++,
                    distToManeuverM = dist, remainingM = 5200, etaMinutes = 12, speedKmhX10 = 300,
                )
                ble.sendCore(core)
                dist = maxOf(0L, dist - 8)   // 30 km/h ≈ 8 m/s
                handler.postDelayed(this, 1000)
            }
        }
        testRunnable = r
        r.run()
        log("Test: maneuver=$maneuver from ${startDistM}m")
    }

    private fun stopTest() {
        testRunnable?.let { handler.removeCallbacks(it) }
        testRunnable = null
    }

    // ---- Google Navigation SDK ----------------------------------------------------------

    private fun startNavigation(simulate: Boolean) {
        stopTest()
        val controller = nav
        if (controller == null || !controller.isReady) {
            log("Press 'Init Navigation SDK' first and wait for 'Navigator ready'")
            return
        }
        val dest = Coords.parse(findViewById<EditText>(R.id.etDest).text.toString())
        if (dest == null) {
            log("Destination must look like 12.9352,77.6245")
            return
        }
        val start = Coords.parse(findViewById<EditText>(R.id.etStart).text.toString())
        val twoWheeler = findViewById<Switch>(R.id.swTwoWheeler).isChecked
        controller.start(
            simulate = simulate,
            start = start?.let { LatLng(it.first, it.second) },
            dest = LatLng(dest.first, dest.second),
            twoWheeler = twoWheeler,
        )
    }
}
