package com.motonav.prototype

import android.Manifest
import android.content.Intent
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var ble: BleClient
    private var nav: NavController? = null

    private lateinit var tvBle: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvDestName: TextView
    private lateinit var tvDestInfo: TextView
    private lateinit var tvNavStatus: TextView
    private lateinit var etDest: EditText
    private lateinit var etStart: EditText
    private lateinit var swTwoWheeler: Switch
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
        tvDestName = findViewById(R.id.tvDestName)
        tvDestInfo = findViewById(R.id.tvDestInfo)
        tvNavStatus = findViewById(R.id.tvNavStatus)
        etDest = findViewById(R.id.etDest)
        etStart = findViewById(R.id.etStart)
        swTwoWheeler = findViewById(R.id.swTwoWheeler)

        NavBridge.log = this::log
        NavBridge.onStatus = this::showStatus
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

        // Destination
        click(R.id.btnStartNav) { startNavigation(simulate = false) }
        click(R.id.btnStop) {
            stopTest()
            nav?.stop()
            showStatus("Not navigating")
        }

        // Advanced / testing
        click(R.id.btnInitNav) {
            askPermissions()
            val controller = nav ?: NavController(this, this::log, this::showStatus).also { nav = it }
            controller.whenReady { showStatus("Google Navigation is ready. Share a place, then Start navigation.") }
        }
        click(R.id.btnSim) { startNavigation(simulate = true) }

        click(R.id.btnLeft) { startTest(Man.LEFT, 300, "Brigade Road") }
        click(R.id.btnRight) { startTest(Man.RIGHT, 80, "MG Road") }
        click(R.id.btnStraight) { startTest(Man.STRAIGHT, 1800, "Outer Ring Road") }
        click(R.id.btnRound) { startTest(Man.ROUNDABOUT, 150, "Silk Board Junction", exit = 2) }
        click(R.id.btnUturn) { startTest(Man.UTURN_LEFT, 60, "Hosur Road") }
        click(R.id.btnArrive) { startTest(Man.ARRIVE, 40, "Destination") }
        click(R.id.btnStopTest) { stopTest(); NavBridge.sendMode(Mode.IDLE) }

        log("MotoNav ready. 1) Connect  2) Share a place from Google Maps  3) Start navigation")
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
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

    /** Live "what is navigation doing" text next to the Start button. */
    private fun showStatus(text: String) {
        runOnUiThread { tvNavStatus.text = text }
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

    // ---- Share from Google Maps ---------------------------------------------------------

    /** Google Maps -> Share -> MotoNav arrives here as ACTION_SEND (text) or a geo: VIEW intent. */
    private fun handleIncoming(incoming: Intent?) {
        val text = when (incoming?.action) {
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> incoming.dataString
            else -> null
        }
        if (text.isNullOrBlank()) return
        setIntent(Intent(this, MainActivity::class.java))   // do not re-import after a screen rotation
        importShared(text)
    }

    private fun importShared(text: String) {
        tvDestName.text = "Reading shared link..."
        tvDestInfo.text = ""
        log("Shared: " + text.take(120).replace('\n', ' '))
        Thread {
            val result = try {
                ShareImporter.import(text)
            } catch (e: Exception) {
                ImportResult.Failed("Could not read the link: ${e.message}")
            }
            runOnUiThread { showImport(result) }
        }.start()
    }

    private fun showImport(result: ImportResult) {
        when (result) {
            is ImportResult.Ok -> {
                val p = result.place
                val lat = p.lat ?: return
                val lng = p.lng ?: return
                tvDestName.text = p.name ?: "Shared location"
                tvDestInfo.text = String.format(Locale.US, "%.5f, %.5f", lat, lng) +
                    if (p.approximate) "   (approximate: map centre, not the exact place)" else ""
                etDest.setText(String.format(Locale.US, "%.6f,%.6f", lat, lng))
                log("Destination set. Press Start navigation.")
            }

            is ImportResult.NeedsLookup -> {
                tvDestName.text = result.name ?: "Unknown place"
                tvDestInfo.text = "This link has no coordinates. Share the place itself (open its page in " +
                    "Google Maps, then Share), or type lat,lng under Advanced."
                log("Link understood but contains no coordinates")
            }

            is ImportResult.Failed -> {
                tvDestName.text = "Could not read the shared link"
                tvDestInfo.text = result.reason
                log("Share failed: ${result.reason}")
            }
        }
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
        val dest = Coords.parse(etDest.text.toString())
        if (dest == null) {
            showStatus("No destination yet. In Google Maps tap Share → MotoNav, or type lat,lng under Advanced.")
            log("No destination yet — share a place from Google Maps, or type lat,lng under Advanced")
            return
        }
        val start = Coords.parse(etStart.text.toString())
        if (!ble.isConnected) log("Note: the MotoNav display is not connected (press Connect)")
        showStatus("Starting…")

        val controller = nav ?: NavController(this, this::log, this::showStatus).also { nav = it }
        controller.whenReady {
            controller.start(
                simulate = simulate,
                start = start?.let { LatLng(it.first, it.second) },
                dest = LatLng(dest.first, dest.second),
                twoWheeler = swTwoWheeler.isChecked,
            )
        }
    }
}
