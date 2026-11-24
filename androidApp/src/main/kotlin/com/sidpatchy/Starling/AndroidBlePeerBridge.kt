package com.sidpatchy.Starling

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.chaquo.python.PyObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Events the bridge reports to the protocol driver. */
internal interface BlePeerListener {
    fun discovered(address: String, name: String?, rssi: Int)

    /** Returns false when the peer is already linked and this connection should be dropped. */
    fun identityRead(address: String, identity: ByteArray): Boolean
    fun connected(address: String, role: String, identity: ByteArray?, payloadSize: Int)
    fun connectFailed(address: String, message: String, timedOut: Boolean)
    fun disconnected(address: String)
    fun mtuChanged(address: String, payloadSize: Int)
    fun data(address: String, value: ByteArray)
    fun error(severity: String, message: String)

    /** Bluetooth was turned off (every link is gone) or is usable again. */
    fun adapterStateChanged(enabled: Boolean)
}

/** Forwards bridge events to the Python driver in starling_ble_android. */
private class PythonBlePeerListener(private val driver: PyObject) : BlePeerListener {
    override fun discovered(address: String, name: String?, rssi: Int) {
        driver.callAttr("bridge_discovered", address, name, rssi)
    }

    override fun identityRead(address: String, identity: ByteArray): Boolean =
        driver.callAttr("bridge_identity_read", address, identity).toBoolean()

    override fun connected(address: String, role: String, identity: ByteArray?, payloadSize: Int) {
        driver.callAttr("bridge_connected", address, role, identity, payloadSize)
    }

    override fun connectFailed(address: String, message: String, timedOut: Boolean) {
        driver.callAttr("bridge_connect_failed", address, message, timedOut)
    }

    override fun disconnected(address: String) {
        driver.callAttr("bridge_disconnected", address)
    }

    override fun mtuChanged(address: String, payloadSize: Int) {
        driver.callAttr("bridge_mtu_changed", address, payloadSize)
    }

    override fun data(address: String, value: ByteArray) {
        driver.callAttr("bridge_data", address, value)
    }

    override fun error(severity: String, message: String) {
        driver.callAttr("bridge_error", severity, message)
    }

    override fun adapterStateChanged(enabled: Boolean) {
        driver.callAttr("bridge_adapter_state", enabled)
    }
}

/**
 * Android transport for the ble-reticulum v2.2 peer protocol.
 *
 * The protocol itself (peer selection, identity handshake handling,
 * fragmentation) runs in Python. This class owns the Android pieces it needs:
 * a scanner and an advertiser for the service UUID, a GATT server for peers
 * that connect to this phone, and a GATT client per peer this phone connects
 * to. It shares the adapter with [AndroidRNodeBluetoothBridge]; each uses its
 * own scan callback and GATT handles, so neither stops the other's work.
 *
 * Turning Bluetooth off invalidates the GATT server, the advertiser, the scan,
 * and every connection without telling their callbacks. The bridge listens for
 * adapter state changes: it drops all of them when the adapter goes off and
 * recreates the server, advertising, and scanning when it comes back on.
 */
@SuppressLint("MissingPermission")
internal class AndroidBlePeerBridge(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerToken = Any()
    private val operations = BlePeerOperationQueue<GattOperation>()
    private val centralLinks = HashMap<String, CentralLink>()
    private val serverPeers = HashMap<String, ServerPeer>()
    private val serverMtus = HashMap<String, Int>()
    private val subscribedCentrals = HashSet<String>()
    private val lastDiscovery = HashMap<String, Long>()

    @Volatile private var running = false
    @Volatile private var listener: BlePeerListener? = null
    @Volatile private var localIdentity: ByteArray? = null
    private var events: ExecutorService? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverTx: BluetoothGattCharacteristic? = null
    private var serviceAdded = false
    private var advertisingRequested = false
    private var advertising = false
    private var scanning = false

    /** False from the moment Bluetooth is turned off until it is on again. */
    private var adapterOn = false
    private var adapterReceiverRegistered = false
    private var restartAttempts = 0
    private var scanRetryAttempts = 0
    private var scanMode = ScanSettings.SCAN_MODE_BALANCED
    private var connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS
    private var maxPeers = DEFAULT_MAX_PEERS

    private var serviceUuid: UUID = UUID(0L, 0L)
    private var rxUuid: UUID = UUID(0L, 0L)
    private var txUuid: UUID = UUID(0L, 0L)
    private var identityUuid: UUID = UUID(0L, 0L)

    private class GattOperation(
        val address: String,
        val kind: String,
        /** Returns false when Android refuses to start the request. */
        val execute: () -> Boolean,
        /** Runs exactly once, with whether the request was confirmed. */
        val onDone: (Boolean) -> Unit,
    ) {
        var done = false
        var watchdog: Runnable? = null
    }

    private enum class LinkState { CONNECTING, SETUP, READY, CLOSED }

    /** A peer this phone connected to; data goes out as writes to its RX characteristic. */
    private inner class CentralLink(val address: String) {
        var gatt: BluetoothGatt? = null
        var state = LinkState.CONNECTING
        var attMtu = BLE_DEFAULT_ATT_MTU
        var identity: ByteArray? = null
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null
        var identityCharacteristic: BluetoothGattCharacteristic? = null
        var timeout: Runnable? = null

        /** Set while the handshake is held back because the local identity is not known yet. */
        var awaitingLocalIdentity = false
        val callback = CentralCallback(this)
    }

    /** A peer connected to the local GATT server; data goes out as TX notifications. */
    private class ServerPeer(val address: String, val device: BluetoothDevice, var attMtu: Int)

    // ---------------------------------------------------------------------
    // Lifecycle (called by the Python driver)
    // ---------------------------------------------------------------------

    /**
     * Opens the GATT server. Returns a message for the user on failure, or
     * null. With Bluetooth turned off this succeeds and the server, scanning,
     * and advertising start when it is turned on.
     */
    fun start(
        serviceUuid: String,
        rxUuid: String,
        txUuid: String,
        identityUuid: String,
        connectionTimeoutSeconds: Double,
        maxPeers: Int,
        driver: PyObject,
    ): String? = open(
        serviceUuid, rxUuid, txUuid, identityUuid, connectionTimeoutSeconds, maxPeers, PythonBlePeerListener(driver),
    )

    private fun open(
        serviceUuid: String,
        rxUuid: String,
        txUuid: String,
        identityUuid: String,
        connectionTimeoutSeconds: Double,
        maxPeers: Int,
        listener: BlePeerListener,
    ): String? {
        stop()
        if (!hasBluetoothPermissions()) return "Bluetooth permission is required for Bluetooth peers."
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: return "Bluetooth is not available on this device."
        val bluetoothAdapter = manager.adapter ?: return "Bluetooth is not available on this device."
        try {
            this.serviceUuid = UUID.fromString(serviceUuid)
            this.rxUuid = UUID.fromString(rxUuid)
            this.txUuid = UUID.fromString(txUuid)
            this.identityUuid = UUID.fromString(identityUuid)
        } catch (_: IllegalArgumentException) {
            return "The Bluetooth peer service identifiers are not valid."
        }

        val enabled = runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)
        synchronized(lock) {
            this.listener = listener
            this.connectionTimeoutMs = (connectionTimeoutSeconds * 1000).toLong().coerceIn(5_000L, 120_000L)
            this.maxPeers = maxPeers.coerceAtLeast(1)
            adapter = bluetoothAdapter
            events = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "starling-ble-peer-events").apply { isDaemon = true }
            }
            advertisingRequested = false
            adapterOn = enabled
            restartAttempts = 0
            scanRetryAttempts = 0
            running = true
        }
        if (enabled) {
            openServer(manager)?.let { message ->
                stop()
                return message
            }
        }
        registerAdapterReceiver()
        if (!enabled) {
            Log.i(TAG, "Bluetooth is off; the peer service will start when it is turned on")
            emit { it.adapterStateChanged(false) }
            // It may have been turned on before the receiver was registered.
            if (runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)) adapterTurnedOn()
        }
        return null
    }

    /**
     * Opens the GATT server and publishes the peer service on it. Advertising
     * begins from [serverCallback] once Android confirms the service. Returns
     * a message for the user on failure, or null.
     */
    private fun openServer(manager: BluetoothManager): String? {
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            rxUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            txUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val identity = BluetoothGattCharacteristic(
            identityUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        service.addCharacteristic(identity)

        synchronized(lock) {
            serverTx = tx
            serviceAdded = false
            advertising = false
        }
        val server = runCatching { manager.openGattServer(appContext, serverCallback) }.getOrNull()
            ?: return "Android could not open the Bluetooth peer service."
        synchronized(lock) { gattServer = server }
        if (!runCatching { server.addService(service) }.getOrDefault(false)) {
            synchronized(lock) { if (gattServer === server) gattServer = null }
            runCatching { server.close() }
            return "Android could not publish the Bluetooth peer service."
        }
        return null
    }

    /** Stops scanning, advertising, the GATT server, and every connection. */
    fun stop() {
        val links: List<CentralLink>
        val peers: List<ServerPeer>
        val dropped: List<GattOperation>
        val server: BluetoothGattServer?
        val executor: ExecutorService?
        synchronized(lock) {
            if (!running && gattServer == null && events == null) return
            running = false
            listener = null
            links = centralLinks.values.toList()
            peers = serverPeers.values.toList()
            centralLinks.clear()
            serverPeers.clear()
            serverMtus.clear()
            subscribedCentrals.clear()
            lastDiscovery.clear()
            dropped = operations.clear()
            dropped.forEach { it.done = true }
            links.forEach { it.state = LinkState.CLOSED }
            server = gattServer
            gattServer = null
            serverTx = null
            serviceAdded = false
            executor = events
            events = null
            adapterOn = false
        }
        unregisterAdapterReceiver()
        mainHandler.removeCallbacksAndMessages(timerToken)
        stopScanning()
        stopAdvertising()
        dropped.forEach { operation -> runCatching { operation.onDone(false) } }
        links.forEach { link ->
            runCatching { link.gatt?.disconnect() }
            runCatching { link.gatt?.close() }
        }
        if (server != null) {
            peers.forEach { peer -> runCatching { server.cancelConnection(peer.device) } }
            runCatching { server.clearServices() }
            runCatching { server.close() }
        }
        executor?.shutdownNow()
        synchronized(lock) {
            adapter = null
            scanner = null
            advertiser = null
        }
    }

    // ---------------------------------------------------------------------
    // Bluetooth being turned off and on
    // ---------------------------------------------------------------------

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.i(TAG, "Bluetooth adapter state changed to ${adapterStateName(state)}")
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> adapterTurnedOff()
                BluetoothAdapter.STATE_ON -> adapterTurnedOn()
            }
        }
    }

    private fun registerAdapterReceiver() {
        synchronized(lock) {
            if (!running || adapterReceiverRegistered) return
            // The adapter state is a protected system broadcast, but it is not
            // always sent by the system UID, so the receiver must be exported.
            ContextCompat.registerReceiver(
                appContext,
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            adapterReceiverRegistered = true
        }
    }

    private fun unregisterAdapterReceiver() {
        synchronized(lock) {
            if (!adapterReceiverRegistered) return
            adapterReceiverRegistered = false
        }
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
    }

    /**
     * Releases everything the adapter restart invalidates. What the driver
     * asked for (scanning, advertising) is remembered for [adapterTurnedOn].
     */
    private fun adapterTurnedOff() {
        val links: List<CentralLink>
        val peerCount: Int
        val dropped: List<GattOperation>
        val server: BluetoothGattServer?
        val leScanner: BluetoothLeScanner?
        val leAdvertiser: BluetoothLeAdvertiser?
        synchronized(lock) {
            if (!running || !adapterOn) return
            adapterOn = false
            scanRetryAttempts = 0
            links = centralLinks.values.toList()
            peerCount = serverPeers.size
            centralLinks.clear()
            serverPeers.clear()
            serverMtus.clear()
            subscribedCentrals.clear()
            lastDiscovery.clear()
            dropped = operations.clear()
            dropped.forEach { it.done = true }
            links.forEach {
                it.state = LinkState.CLOSED
                it.timeout = null
            }
            server = gattServer
            gattServer = null
            serverTx = null
            serviceAdded = false
            advertising = false
            leScanner = scanner
            leAdvertiser = advertiser
            scanner = null
            advertiser = null
        }
        // Connection timeouts, operation watchdogs, the scan refresh, and any restart retry.
        mainHandler.removeCallbacksAndMessages(timerToken)
        Log.i(
            TAG,
            "Bluetooth is off: closed ${links.size} central link(s), $peerCount peripheral link(s), " +
                "the GATT server, advertising, and scanning",
        )
        dropped.forEach { operation -> runCatching { operation.onDone(false) } }
        runCatching { leScanner?.stopScan(scanCallback) }
        runCatching { leAdvertiser?.stopAdvertising(advertiseCallback) }
        links.forEach { link -> runCatching { link.gatt?.close() } }
        runCatching { server?.close() }
        emit { it.adapterStateChanged(false) }
    }

    private fun adapterTurnedOn() {
        synchronized(lock) {
            if (!running || adapterOn) return
            adapterOn = true
            restartAttempts = 0
        }
        restartAfterAdapterOn()
    }

    private val restartRetry = Runnable { restartAfterAdapterOn() }

    private fun restartAfterAdapterOn() {
        val message = recreateAdapterState()
        if (message == null) return
        val attempt = synchronized(lock) {
            if (!running || !adapterOn) return
            ++restartAttempts
        }
        if (attempt < MAX_RESTART_ATTEMPTS) {
            Log.i(TAG, "Bluetooth peer restart attempt $attempt failed ($message); trying again")
            schedule(RESTART_RETRY_MS, restartRetry)
        } else {
            Log.w(TAG, "Bluetooth peer restart gave up after $attempt attempts: $message")
            emit { it.error("error", "Bluetooth peers could not restart after Bluetooth came back on: $message") }
        }
    }

    /**
     * Recreates the GATT server, advertising, and scanning on the restarted
     * adapter. Returns what could not be restarted, or null when all of it was.
     */
    private fun recreateAdapterState(): String? {
        val wantScan: Boolean
        val wantAdvertising: Boolean
        val needServer: Boolean
        synchronized(lock) {
            if (!running || !adapterOn) return null
            wantScan = scanning
            wantAdvertising = advertisingRequested
            needServer = gattServer == null
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: return "Bluetooth is not available"
        val bluetoothAdapter = manager.adapter ?: return "Bluetooth is not available"
        if (!bluetoothAdapter.isEnabled) return "Bluetooth is not on yet"
        val leScanner = bluetoothAdapter.bluetoothLeScanner
        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (wantScan && leScanner == null) return "Bluetooth scanning is not available yet"
        if (wantAdvertising && leAdvertiser == null) return "Bluetooth advertising is not available yet"
        synchronized(lock) {
            adapter = bluetoothAdapter
            scanner = leScanner
            advertiser = leAdvertiser
        }
        if (needServer) {
            openServer(manager)?.let { return it }
            Log.i(TAG, "Bluetooth is on: GATT server reopened")
        }
        // On a first attempt advertising begins once Android confirms the service.
        if (synchronized(lock) { serviceAdded }) beginAdvertising()?.let { return it }
        if (wantScan) {
            beginScan()?.let { return it }
            Log.i(TAG, "Bluetooth is on: scanning restarted")
        }
        Log.i(TAG, "Bluetooth is on: peer service restarted (scanning $wantScan, advertising $wantAdvertising)")
        emit { it.adapterStateChanged(true) }
        return null
    }

    private fun adapterStateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "off"
        BluetoothAdapter.STATE_TURNING_ON -> "turning on"
        BluetoothAdapter.STATE_ON -> "on"
        BluetoothAdapter.STATE_TURNING_OFF -> "turning off"
        else -> "unknown ($state)"
    }

    fun setIdentity(identity: ByteArray) {
        val waiting = synchronized(lock) {
            localIdentity = identity.copyOf()
            centralLinks.values
                .filter { it.awaitingLocalIdentity && it.state == LinkState.SETUP }
                .onEach { it.awaitingLocalIdentity = false }
        }
        Log.i(TAG, "Local identity set; ${waiting.size} link(s) were waiting for it")
        waiting.forEach(::sendHandshake)
    }

    /** The adapter address when Android discloses it, which it normally does not. */
    @SuppressLint("HardwareIds")
    fun localAddress(): String? {
        val address = runCatching { synchronized(lock) { adapter }?.address }.getOrNull()
        return address?.takeIf { blePeerConnectionDirection(it, PROBE_ADDRESS) != BlePeerConnectionDirection.UNKNOWN }
    }

    /**
     * Applies the protocol's address ordering. Android hides the adapter
     * address and advertises from rotating private addresses, so the order is
     * normally unknown and this returns true; the protocol then drops any
     * second link to the same identity.
     */
    fun shouldInitiate(address: String): Boolean =
        blePeerConnectionDirection(localAddress(), address) != BlePeerConnectionDirection.WAIT

    // ---------------------------------------------------------------------
    // Scanning and advertising
    // ---------------------------------------------------------------------

    fun startScanning(powerMode: String): String? {
        val mode = when (powerMode) {
            "aggressive" -> ScanSettings.SCAN_MODE_LOW_LATENCY
            "saver" -> ScanSettings.SCAN_MODE_LOW_POWER
            else -> ScanSettings.SCAN_MODE_BALANCED
        }
        val bluetoothAdapter = synchronized(lock) {
            if (!running) return "Bluetooth peers are not running."
            if (!adapterOn) {
                // Bluetooth is off; the scan starts when it comes back on.
                scanMode = mode
                scanning = true
                return null
            }
            adapter
        } ?: return "Bluetooth peers are not running."
        val leScanner = bluetoothAdapter.bluetoothLeScanner ?: return "Bluetooth scanning is not available."
        synchronized(lock) {
            scanner = leScanner
            scanMode = mode
        }
        return beginScan()
    }

    private fun beginScan(): String? {
        val leScanner: BluetoothLeScanner
        val mode: Int
        synchronized(lock) {
            if (!running) return null
            leScanner = scanner ?: return null
            mode = scanMode
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        runCatching { leScanner.stopScan(scanCallback) }
        return try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            synchronized(lock) { scanning = true }
            mainHandler.removeCallbacks(scanRefresh)
            // Android quietly downgrades scans that run for too long, so renew this one.
            schedule(SCAN_REFRESH_MS, scanRefresh)
            null
        } catch (error: RuntimeException) {
            "Android could not start Bluetooth scanning: ${error.message.orEmpty()}"
        }
    }

    private val scanRefresh = Runnable {
        if (synchronized(lock) { running && scanning }) {
            beginScan()?.let { message -> emit { it.error("warning", message) } }
        }
    }

    /**
     * Starts the scan again after Android reported that it failed, waiting
     * longer after each failure in a row. The scan stays wanted meanwhile.
     */
    private fun retryScanLater(reason: String) {
        val attempt = synchronized(lock) {
            if (!running || !scanning || !adapterOn) return
            ++scanRetryAttempts
        }
        val delayMs = blePeerScanRetryDelayMs(attempt)
        Log.i(TAG, "Bluetooth scanning failed ($reason); retry $attempt in $delayMs ms")
        if (attempt == 1) emit { it.error("warning", "Bluetooth scanning failed ($reason); retrying.") }
        mainHandler.removeCallbacks(scanRetry)
        schedule(delayMs, scanRetry)
    }

    private val scanRetry = Runnable {
        if (synchronized(lock) { running && scanning && adapterOn }) {
            val message = beginScan()
            if (message == null) Log.i(TAG, "Bluetooth scanning requested again after a failure")
            else retryScanLater(message)
        }
    }

    fun stopScanning() {
        val leScanner = synchronized(lock) {
            scanning = false
            scanRetryAttempts = 0
            scanner
        }
        mainHandler.removeCallbacks(scanRefresh)
        mainHandler.removeCallbacks(scanRetry)
        runCatching { leScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device?.address ?: return
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (!running || !scanning) return
                scanRetryAttempts = 0
                val previous = lastDiscovery[address]
                if (previous != null && now - previous < DISCOVERY_REPORT_INTERVAL_MS) return
                if (lastDiscovery.size >= MAX_TRACKED_DISCOVERIES) lastDiscovery.clear()
                lastDiscovery[address] = now
            }
            val name = result.scanRecord?.deviceName
            val rssi = result.rssi
            emit { it.discovered(address, name, rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            retryScanLater("code $errorCode")
        }
    }

    /** Advertises the service UUID once the GATT service is published. */
    fun startAdvertising(): String? {
        val bluetoothAdapter = synchronized(lock) {
            if (!running) return "Bluetooth peers are not running."
            if (!adapterOn) {
                // Bluetooth is off; advertising starts when it comes back on.
                advertisingRequested = true
                return null
            }
            adapter
        } ?: return "Bluetooth peers are not running."
        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: return "This device cannot advertise over Bluetooth."
        val ready = synchronized(lock) {
            advertiser = leAdvertiser
            advertisingRequested = true
            serviceAdded
        }
        return if (ready) beginAdvertising() else null
    }

    private fun beginAdvertising(): String? {
        val leAdvertiser = synchronized(lock) {
            if (!running || !advertisingRequested || advertising) return null
            advertiser ?: return null
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // A 128-bit service UUID leaves no room for a device name in the 31-byte packet.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        return try {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
            synchronized(lock) { advertising = true }
            Log.i(TAG, "Advertising the Bluetooth peer service")
            null
        } catch (error: RuntimeException) {
            "Android could not start Bluetooth advertising: ${error.message.orEmpty()}"
        }
    }

    fun stopAdvertising() {
        val leAdvertiser = synchronized(lock) {
            advertisingRequested = false
            advertising = false
            advertiser
        }
        runCatching { leAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            synchronized(lock) { advertising = false }
            emit { it.error("error", "Bluetooth advertising failed (code $errorCode).") }
        }
    }

    // ---------------------------------------------------------------------
    // Central role: connections this phone opens
    // ---------------------------------------------------------------------

    /**
     * Starts connecting to [address]. Returns a failure message, or null once
     * the attempt is under way; its outcome then arrives through the listener.
     */
    fun connect(address: String): String? {
        val bluetoothAdapter = synchronized(lock) {
            if (running && !adapterOn) return "Bluetooth is off."
            if (running) adapter else null
        } ?: return "Bluetooth peers are not running."
        val device = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return "not a Bluetooth address"
        }
        val link = CentralLink(address)
        synchronized(lock) {
            if (address in centralLinks || address in serverPeers) return "already connected"
            if (centralLinks.size + serverPeers.size >= maxPeers) return "peer limit reached"
            centralLinks[address] = link
        }
        val gatt = runCatching {
            // The replacement API is newer than this app's minimum Android version.
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, link.callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            synchronized(lock) {
                link.state = LinkState.CLOSED
                centralLinks.remove(address)
            }
            Log.i(TAG, "Connect to $address failed: Android did not create a BLE connection")
            return "Android did not create a BLE connection."
        }
        Log.i(TAG, "Connecting to $address as central")
        val timeout = Runnable {
            if (synchronized(lock) { link.state != LinkState.READY }) {
                abandon(link, "connection timed out", timedOut = true)
            }
        }
        synchronized(lock) {
            link.gatt = gatt
            link.timeout = timeout
        }
        schedule(connectionTimeoutMs, timeout)
        return null
    }

    /** Closes the connection to [address] in whichever role it was made. */
    fun disconnect(address: String) {
        val link: CentralLink?
        val peer: ServerPeer?
        val server: BluetoothGattServer?
        synchronized(lock) {
            link = centralLinks[address]
            peer = serverPeers.remove(address)
            server = gattServer
        }
        if (link != null && closeLink(link)) Log.i(TAG, "Disconnected from $address (central): closed by the driver")
        if (peer != null) {
            Log.i(TAG, "Disconnected from $address (peripheral): closed by the driver")
            releaseOperations(address)
            // The server of an adapter that is off or restarting only throws.
            if (synchronized(lock) { adapterOn && gattServer === server }) {
                runCatching { server?.cancelConnection(peer.device) }
            }
        }
    }

    private inner class CentralCallback(private val link: CentralLink) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val previous = synchronized(lock) {
                link.state.also {
                    val connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
                    if (connected && it == LinkState.CONNECTING) link.state = LinkState.SETUP
                }
            }
            Log.i(TAG, "Client connection state for ${link.address}: status $status, newState $newState, link was $previous")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                when (previous) {
                    LinkState.CONNECTING -> discoverServices(link)
                    LinkState.SETUP, LinkState.READY -> {
                        // A new connection has none of this link's setup: the peer has not
                        // been sent the handshake on it. Drop it so the driver starts over.
                        runCatching { gatt.disconnect() }
                        linkLost(link, "BLE connection was re-established without a disconnect")
                    }
                    LinkState.CLOSED -> Unit
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                linkLost(link, "BLE connection closed (status $status, state $newState)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            finishCurrent(link.address, KIND_DISCOVER, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            identityRead(characteristic, value, status)
        }

        @Deprecated("Android 13 added a value parameter")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            identityRead(characteristic, characteristic.value, status)
        }

        private fun identityRead(characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
            if (characteristic.uuid != identityUuid) return
            val identity = value?.takeIf { status == BluetoothGatt.GATT_SUCCESS }?.copyOf()
            if (identity != null) synchronized(lock) { link.identity = identity }
            finishCurrent(link.address, KIND_IDENTITY, identity != null)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val ready = synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= BLE_DEFAULT_ATT_MTU) link.attMtu = mtu
                link.state == LinkState.READY
            }
            finishCurrent(link.address, KIND_MTU, status == BluetoothGatt.GATT_SUCCESS)
            if (ready && status == BluetoothGatt.GATT_SUCCESS) {
                emit { it.mtuChanged(link.address, blePeerPayloadSize(mtu)) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                finishCurrent(link.address, KIND_SUBSCRIBE, status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == rxUuid) {
                finishCurrent(link.address, KIND_WRITE, status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notificationReceived(characteristic, value)
        }

        @Deprecated("Android 13 added a value parameter")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            notificationReceived(characteristic, characteristic.value)
        }

        private fun notificationReceived(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
            if (characteristic.uuid != txUuid || value == null) return
            if (synchronized(lock) { link.state != LinkState.READY }) return
            val copy = value.copyOf()
            emit { it.data(link.address, copy) }
        }
    }

    // The steps below follow the protocol's connection order: discover the
    // service, read the peer identity, negotiate the MTU, subscribe to TX,
    // then write the local identity to RX as the handshake.

    private fun discoverServices(link: CentralLink) {
        submit(GattOperation(link.address, KIND_DISCOVER, { link.gatt?.discoverServices() == true }) { succeeded ->
            val service = if (succeeded) link.gatt?.getService(serviceUuid) else null
            val rx = service?.getCharacteristic(rxUuid)
            val tx = service?.getCharacteristic(txUuid)
            val identity = service?.getCharacteristic(identityUuid)
            if (rx == null || tx == null || identity == null) {
                abandon(link, "Reticulum service $serviceUuid not found")
            } else {
                synchronized(lock) {
                    link.rx = rx
                    link.tx = tx
                    link.identityCharacteristic = identity
                }
                readIdentity(link)
            }
        })
    }

    private fun readIdentity(link: CentralLink) {
        submit(
            GattOperation(link.address, KIND_IDENTITY, {
                val characteristic = link.identityCharacteristic
                characteristic != null && link.gatt?.readCharacteristic(characteristic) == true
            }) { succeeded ->
                val identity = synchronized(lock) { link.identity }
                if (!succeeded || identity == null || identity.size != IDENTITY_LENGTH) {
                    abandon(link, "could not read the peer identity")
                } else {
                    confirmIdentity(link, identity)
                }
            },
        )
    }

    private fun confirmIdentity(link: CentralLink, identity: ByteArray) {
        val queued = emit { listener ->
            val accepted = runCatching { listener.identityRead(link.address, identity) }.getOrDefault(true)
            if (!accepted) {
                // The driver has already recorded why; this is not a failure.
                if (closeLink(link)) Log.i(TAG, "Disconnected from ${link.address}: the driver declined its identity")
            } else {
                requestMtu(link)
            }
        }
        if (!queued) abandon(link, "Bluetooth peers stopped")
    }

    private fun requestMtu(link: CentralLink) {
        // A refused or failed request is not fatal: the link keeps the default MTU.
        submit(GattOperation(link.address, KIND_MTU, { link.gatt?.requestMtu(BLE_REQUESTED_ATT_MTU) == true }) {
            subscribe(link)
        })
    }

    private fun subscribe(link: CentralLink) {
        submit(
            GattOperation(link.address, KIND_SUBSCRIBE, {
                val gatt = link.gatt
                val tx = link.tx
                val cccd = tx?.getDescriptor(CCCD_UUID)
                if (gatt == null || tx == null || cccd == null || !gatt.setCharacteristicNotification(tx, true)) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }) { succeeded ->
                if (succeeded) sendHandshake(link) else abandon(link, "could not subscribe to peer notifications")
            },
        )
    }

    /**
     * Writes the local identity to RX. The peer drops everything from a
     * central it cannot identify, so a link only becomes ready once this
     * write is confirmed. Until the driver supplies the identity the link
     * stays in setup; [setIdentity] resumes it, or the connection timeout
     * abandons it and the driver connects again later.
     */
    private fun sendHandshake(link: CentralLink) {
        val identity = synchronized(lock) {
            if (link.state != LinkState.SETUP) return
            localIdentity.also { link.awaitingLocalIdentity = it == null }
        }
        if (identity == null) {
            Log.i(TAG, "Holding the identity handshake to ${link.address}: the local identity is not set yet")
            return
        }
        submit(
            GattOperation(link.address, KIND_WRITE, {
                writeRx(link, identity, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }) { succeeded ->
                if (succeeded) {
                    Log.i(TAG, "Identity handshake written to ${link.address}")
                    linkReady(link)
                } else {
                    abandon(link, "identity handshake was not accepted")
                }
            },
        )
    }

    private fun linkReady(link: CentralLink) {
        val identity: ByteArray?
        val payload: Int
        synchronized(lock) {
            if (link.state != LinkState.SETUP) return
            link.state = LinkState.READY
            link.timeout?.let { mainHandler.removeCallbacks(it) }
            link.timeout = null
            identity = link.identity
            payload = blePeerPayloadSize(link.attMtu)
            // Queued under the lock so no notification can be reported ahead of it.
            emit { it.connected(link.address, ROLE_CENTRAL, identity, payload) }
        }
        Log.i(TAG, "Link to ${link.address} ready as central (payload $payload bytes)")
    }

    private fun writeRx(link: CentralLink, value: ByteArray, writeType: Int): Boolean {
        val gatt = link.gatt ?: return false
        val rx = link.rx ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            rx.writeType = writeType
            @Suppress("DEPRECATION")
            rx.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rx)
        }
    }

    /** Ends a connection attempt that could not be completed and reports why. */
    private fun abandon(link: CentralLink, message: String, timedOut: Boolean = false) {
        if (!closeLink(link)) return
        Log.i(TAG, "Abandoned the connection to ${link.address}: $message")
        emit { it.connectFailed(link.address, message, timedOut) }
    }

    /** The peer or the radio dropped the link. */
    private fun linkLost(link: CentralLink, message: String) {
        val wasReady = synchronized(lock) { link.state == LinkState.READY }
        runCatching { link.gatt?.close() }
        if (!closeLink(link, disconnect = false)) return
        Log.i(TAG, "Disconnected from ${link.address} (central, ${if (wasReady) "was ready" else "during setup"}): $message")
        if (wasReady) emit { it.disconnected(link.address) }
        else emit { it.connectFailed(link.address, message, false) }
    }

    /** Releases a central link. Returns false when it was already closed. */
    private fun closeLink(link: CentralLink, disconnect: Boolean = true): Boolean {
        val gatt: BluetoothGatt?
        synchronized(lock) {
            if (link.state == LinkState.CLOSED) return false
            link.state = LinkState.CLOSED
            link.timeout?.let { mainHandler.removeCallbacks(it) }
            link.timeout = null
            if (centralLinks[link.address] === link) centralLinks.remove(link.address)
            gatt = link.gatt
        }
        releaseOperations(link.address)
        if (disconnect && gatt != null) {
            runCatching { gatt.disconnect() }
            // Closing straight away can leave the radio link up; give the stack a moment.
            mainHandler.postDelayed({ runCatching { gatt.close() } }, GATT_CLOSE_DELAY_MS)
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    /** Central role: writes one fragment to the peer's RX characteristic and waits for the stack. */
    fun writeRx(address: String, data: ByteArray): Boolean {
        val link = synchronized(lock) {
            centralLinks[address]?.takeIf { it.state == LinkState.READY && data.size <= blePeerPayloadSize(it.attMtu) }
        } ?: return false
        val writeType = if ((link.rx?.properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val result = CompletableFuture<Boolean>()
        submit(GattOperation(address, KIND_WRITE, { writeRx(link, data, writeType) }) { result.complete(it) })
        return await(result)
    }

    /** Peripheral role: notifies one fragment on TX and waits for the stack. */
    fun notifyTx(address: String, data: ByteArray): Boolean {
        val peer = synchronized(lock) {
            serverPeers[address]?.takeIf { data.size <= blePeerPayloadSize(it.attMtu) }
        } ?: return false
        val result = CompletableFuture<Boolean>()
        submit(
            GattOperation(address, KIND_NOTIFY, {
                val server = synchronized(lock) { gattServer }
                val tx = synchronized(lock) { serverTx }
                if (server == null || tx == null) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(peer.device, tx, false, data) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = data
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(peer.device, tx, false)
                }
            }) { result.complete(it) },
        )
        return await(result)
    }

    private fun await(result: CompletableFuture<Boolean>): Boolean = try {
        result.get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------------
    // Peripheral role: the local GATT server
    // ---------------------------------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != serviceUuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit { it.error("error", "Android could not publish the Bluetooth peer service (status $status).") }
                return
            }
            synchronized(lock) { serviceAdded = true }
            beginAdvertising()?.let { message -> emit { it.error("error", message) } }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Android reports every LE link here, including ones this app opened
            // as a central and the RNode radio. A device only becomes a peer once
            // it writes to RX, so unrelated links are never tracked or closed.
            val address = device.address
            Log.i(TAG, "Server connection state for $address: status $status, newState $newState")
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            val peer = synchronized(lock) {
                serverMtus.remove(address)
                subscribedCentrals.remove(address)
                serverPeers.remove(address)
            } ?: return
            releaseOperations(peer.address)
            Log.i(TAG, "Disconnected from ${peer.address} (peripheral): link closed")
            emit { it.disconnected(peer.address) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val address = device.address
            val registered = synchronized(lock) {
                serverMtus[address] = mtu
                serverPeers[address]?.also { it.attMtu = mtu } != null
            }
            if (registered) emit { it.mtuChanged(address, blePeerPayloadSize(mtu)) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = if (characteristic.uuid == identityUuid) localIdentity else ByteArray(0)
            when {
                value == null -> respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                offset > value.size -> respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else -> respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            // Fragments always fit one write, so queued (long) writes are not part of the protocol.
            if (characteristic.uuid != rxUuid || preparedWrite || offset != 0 || value == null) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val address = device.address
            if (!registerServerPeer(device)) return
            val copy = value.copyOf()
            emit { it.data(address, copy) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid != CCCD_UUID || offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val subscribed = synchronized(lock) { device.address in subscribedCentrals }
            val value = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid != CCCD_UUID || preparedWrite || offset != 0 || value == null) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            synchronized(lock) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedCentrals += device.address
                } else {
                    subscribedCentrals -= device.address
                }
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            finishCurrent(device.address, KIND_NOTIFY, status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val server = synchronized(lock) { gattServer } ?: return
        runCatching { server.sendResponse(device, requestId, status, offset, value) }
    }

    /** Starts tracking a central after its first RX write. Returns false when it cannot be a peer. */
    private fun registerServerPeer(device: BluetoothDevice): Boolean {
        val address = device.address
        val payload: Int
        synchronized(lock) {
            if (!running) return false
            if (address in serverPeers) return true
            // One role per address: this phone already talks to it as a central.
            if (address in centralLinks) return false
            if (centralLinks.size + serverPeers.size >= maxPeers) return false
            val attMtu = serverMtus[address] ?: BLE_DEFAULT_ATT_MTU
            serverPeers[address] = ServerPeer(address, device, attMtu)
            payload = blePeerPayloadSize(attMtu)
            emit { it.connected(address, ROLE_PERIPHERAL, null, payload) }
        }
        Log.i(TAG, "Peer $address connected to this phone (peripheral, payload $payload bytes)")
        return true
    }

    // ---------------------------------------------------------------------
    // The single GATT operation queue
    // ---------------------------------------------------------------------

    private fun submit(operation: GattOperation) {
        val next = synchronized(lock) {
            if (!running || !adapterOn) {
                operation.done = true
                null
            } else {
                operations.submit(operation)
            }
        }
        if (operation.done) {
            runCatching { operation.onDone(false) }
            return
        }
        next?.let(::startOperation)
    }

    private fun startOperation(operation: GattOperation) {
        val watchdog = Runnable { complete(operation, false) }
        synchronized(lock) { operation.watchdog = watchdog }
        schedule(OPERATION_TIMEOUT_MS, watchdog)
        val started = try {
            operation.execute()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Bluetooth ${operation.kind} request failed", error)
            false
        }
        if (!started) complete(operation, false)
    }

    /** Completes the running operation if it is the [kind] request for [address]. */
    private fun finishCurrent(address: String, kind: String, succeeded: Boolean) {
        val operation = synchronized(lock) {
            operations.current?.takeIf { it.address == address && it.kind == kind }
        } ?: return
        complete(operation, succeeded)
    }

    private fun complete(operation: GattOperation, succeeded: Boolean) {
        val next: GattOperation?
        synchronized(lock) {
            if (operation.done) return
            operation.done = true
            operation.watchdog?.let { mainHandler.removeCallbacks(it) }
            operation.watchdog = null
            next = operations.finish(operation)
        }
        try {
            operation.onDone(succeeded)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Bluetooth ${operation.kind} completion failed", error)
        }
        next?.let(::startOperation)
    }

    /** Fails everything queued or running for a connection that is going away. */
    private fun releaseOperations(address: String) {
        val cancelled: List<GattOperation>
        val current: GattOperation?
        synchronized(lock) {
            cancelled = operations.cancelPending { it.address == address }
            cancelled.forEach { it.done = true }
            current = operations.current?.takeIf { it.address == address }
        }
        cancelled.forEach { operation -> runCatching { operation.onDone(false) } }
        current?.let { complete(it, false) }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Queues an event for the driver, in order, off the Bluetooth callback threads. */
    private fun emit(event: (BlePeerListener) -> Unit): Boolean {
        val executor: ExecutorService
        val target: BlePeerListener
        synchronized(lock) {
            if (!running) return false
            executor = events ?: return false
            target = listener ?: return false
        }
        return try {
            executor.execute {
                if (!running) return@execute
                try {
                    event(target)
                } catch (error: Exception) {
                    Log.w(TAG, "Bluetooth peer event was not delivered", error)
                }
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun schedule(delayMs: Long, task: Runnable) {
        mainHandler.postAtTime(task, timerToken, SystemClock.uptimeMillis() + delayMs)
    }

    private fun hasBluetoothPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ROLE_CENTRAL = "central"
        const val ROLE_PERIPHERAL = "peripheral"

        /** Runtime permissions needed to scan, connect, and advertise on this Android version. */
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        private const val TAG = "AndroidBlePeerBridge"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PROBE_ADDRESS = "FF:FF:FF:FF:FF:FF"
        private const val IDENTITY_LENGTH = 16
        private const val KIND_DISCOVER = "service discovery"
        private const val KIND_IDENTITY = "identity read"
        private const val KIND_MTU = "MTU"
        private const val KIND_SUBSCRIBE = "subscribe"
        private const val KIND_WRITE = "write"
        private const val KIND_NOTIFY = "notify"
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L
        private const val DEFAULT_MAX_PEERS = 7
        private const val OPERATION_TIMEOUT_MS = 10_000L
        private const val SEND_TIMEOUT_MS = 5_000L
        private const val GATT_CLOSE_DELAY_MS = 1_000L
        private const val SCAN_REFRESH_MS = 20 * 60 * 1000L
        private const val DISCOVERY_REPORT_INTERVAL_MS = 1_000L
        private const val MAX_TRACKED_DISCOVERIES = 256
        private const val RESTART_RETRY_MS = 2_000L
        private const val MAX_RESTART_ATTEMPTS = 10
    }
}
