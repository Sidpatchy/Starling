package com.sidpatchy.Starling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bridges an RNode Nordic UART BLE connection to the RNS TCP RNode transport. */
internal class AndroidRNodeBluetoothBridge(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "starling-rnode-ble-tcp").apply { isDaemon = true }
    }
    private val pendingBleWrites = ArrayDeque<ByteArray>()
    private val pendingTcpWrites = ArrayDeque<ByteArray>()

    @Volatile private var running = false
    private var startupResolved = false
    private var startupError: String? = null
    private var startupLatch: CountDownLatch? = null
    @Volatile private var targetName: String? = null
    @Volatile private var scanner: BluetoothLeScanner? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var writeInProgress = false
    private var writeChunkSize = DEFAULT_WRITE_CHUNK
    private var adapterStateReceiverRegistered = false
    private val retryScan = Runnable { startScan() }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handleAdapterOff()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull().orEmpty()
            val selectedName = targetName
            val matches = if (selectedName.isNullOrBlank()) {
                deviceName.startsWith("RNode", ignoreCase = true)
            } else {
                deviceName.equals(selectedName, ignoreCase = true) ||
                    result.device.address.equals(selectedName, ignoreCase = true)
            }
            if (!running || !matches) return

            stopScan()
            runCatching {
                val connection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    result.device.connectGatt(
                        appContext,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    result.device.connectGatt(appContext, false, gattCallback)
                }
                if (connection == null) throw IOException("Android did not create a BLE connection.")
                synchronized(lock) { gatt = connection }
            }.onFailure { failStartup("Could not connect to the BLE RNode: ${it.message.orEmpty()}") }
        }

        override fun onScanFailed(errorCode: Int) {
            val isStarting = synchronized(lock) { !startupResolved }
            if (isStarting) {
                failStartup("Android Bluetooth scanning failed (code $errorCode).")
            } else if (running) {
                closeTcpClient()
                scheduleScanRetry()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattDisconnect(gatt, "BLE connection failed (status $status).")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) { this@AndroidRNodeBluetoothBridge.gatt = gatt }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        runCatching { gatt.requestMtu(185) }
                    }
                    if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                        failStartup("Could not discover the BLE RNode services.")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                    handleGattDisconnect(gatt, "The BLE RNode disconnected.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
                synchronized(lock) { writeChunkSize = (mtu - 3).coerceAtLeast(DEFAULT_WRITE_CHUNK) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failStartup("Could not inspect the BLE RNode services (status $status).")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                failStartup("The BLE device does not provide the RNode Nordic UART service.")
                return
            }
            val canWrite = rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val canNotify = tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!canWrite || !canNotify) {
                failStartup("The BLE device's UART characteristics are not compatible with an RNode.")
                return
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null || !runCatching { gatt.setCharacteristicNotification(tx, true) }.getOrDefault(false)) {
                failStartup("Could not enable BLE RNode notifications.")
                return
            }
            synchronized(lock) {
                rxCharacteristic = rx
                txCharacteristic = tx
                writeType = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            }
            val descriptorValue = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, descriptorValue)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = descriptorValue
                    @Suppress("DEPRECATION")
                    if (gatt.writeDescriptor(cccd)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                }
            }.getOrDefault(BluetoothGatt.GATT_FAILURE)
            if (result != BluetoothGatt.GATT_SUCCESS) {
                failStartup("Could not subscribe to BLE RNode notifications.")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failStartup("The BLE RNode rejected UART notifications (status $status).")
                return
            }
            if (!openTcpServer(gatt)) {
                handleGattDisconnect(gatt, "Could not open the local port for the BLE RNode bridge.")
                return
            }
            synchronized(lock) {
                startupResolved = true
                startupError = null
                startupLatch?.countDown()
                startupLatch = null
            }
            flushPendingTcpWrites()
            pumpBleWrites()
        }

        @Deprecated("Android 13 added a value parameter")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onRNodeData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onRNodeData(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeTcpClient()
            }
            synchronized(lock) { writeInProgress = false }
            pumpBleWrites()
        }
    }

    /** Returns null after BLE notifications and the loopback listener are ready. */
    fun start(targetName: String): String? {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (running) return "An Android BLE RNode connection is already active."
            running = true
            startupResolved = false
            startupError = null
            startupLatch = latch
            this.targetName = targetName.trim().takeIf(String::isNotEmpty)
        }

        try {
            registerAdapterStateReceiver()

            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                ?: return stopWithError("Bluetooth is not available on this device.")
            val adapter: BluetoothAdapter = manager.adapter
                ?: return stopWithError("Bluetooth is not available on this device.")
            if (!adapter.isEnabled) return stopWithError("Turn on Bluetooth to connect to a BLE RNode.")
            scanner = adapter.bluetoothLeScanner
                ?: return stopWithError("Android could not start Bluetooth LE scanning.")
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (error: SecurityException) {
            return stopWithError("Allow Starling Bluetooth access to connect to the RNode.")
        } catch (error: Exception) {
            return stopWithError("Could not start the BLE RNode bridge: ${error.message.orEmpty()}")
        }

        val ready = runCatching { latch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!ready) return stopWithError("Timed out finding the BLE RNode. Keep Starling open and check that the radio is nearby.")
        val error = synchronized(lock) { startupError }
        if (error != null) stop()
        return error
    }

    fun stop() {
        synchronized(lock) {
            // A service stop can arrive while start() is waiting for discovery,
            // service inspection, or notification subscription. Wake that
            // caller with a failure so it cannot mistake cancellation for a
            // successfully initialized bridge.
            if (!startupResolved) {
                startupResolved = true
                startupError = "BLE RNode startup was cancelled."
                startupLatch?.countDown()
                startupLatch = null
            }
            running = false
            mainHandler.removeCallbacks(retryScan)
            if (adapterStateReceiverRegistered) {
                adapterStateReceiverRegistered = false
                runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
            }
            runCatching { scanner?.stopScan(scanCallback) }
            scanner = null
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            runCatching { clientSocket?.close() }
            runCatching { serverSocket?.close() }
            clientSocket = null
            serverSocket = null
            rxCharacteristic = null
            txCharacteristic = null
            pendingBleWrites.clear()
            pendingTcpWrites.clear()
            writeInProgress = false
        }
    }

    private fun stopWithError(message: String): String {
        stop()
        return message
    }

    private fun startScan() {
        if (!running) return
        try {
            val currentScanner = scanner ?: return failStartup("Android Bluetooth scanning is unavailable.")
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            currentScanner.startScan(listOf(filter), settings, scanCallback)
        } catch (error: SecurityException) {
            handleScanRestartFailure("Allow Starling Bluetooth access to reconnect to the RNode.")
        } catch (error: Exception) {
            handleScanRestartFailure("Could not resume BLE RNode scanning: ${error.message.orEmpty()}")
        }
    }

    private fun handleScanRestartFailure(message: String) {
        val isStarting = synchronized(lock) { !startupResolved }
        if (isStarting) {
            failStartup(message)
        } else if (running) {
            closeTcpClient()
            scheduleScanRetry()
        }
    }

    private fun scheduleScanRetry() {
        mainHandler.removeCallbacks(retryScan)
        mainHandler.postDelayed(retryScan, RECONNECT_SCAN_DELAY_MS)
    }

    private fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun registerAdapterStateReceiver() {
        synchronized(lock) {
            if (!running || adapterStateReceiverRegistered) return
            // The adapter state is a protected system broadcast, but it is not
            // always sent by the system UID, so the receiver must be exported.
            ContextCompat.registerReceiver(
                appContext,
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            adapterStateReceiverRegistered = true
        }
    }

    /**
     * Android does not reliably report a GATT disconnect when Bluetooth is
     * switched off. Drop the link the same way as a lost RNode; the scan retry
     * then keeps failing until the adapter is back on and the RNode is found.
     */
    private fun handleAdapterOff() {
        if (!running) return
        val message = "Bluetooth was turned off."
        val activeGatt = synchronized(lock) { gatt }
        if (activeGatt != null) {
            handleGattDisconnect(activeGatt, message)
        } else {
            stopScan()
            handleScanRestartFailure(message)
        }
    }

    private fun failStartup(message: String) {
        synchronized(lock) {
            if (startupResolved) return
            startupResolved = true
            startupError = message
            startupLatch?.countDown()
            startupLatch = null
        }
    }

    private fun handleGattDisconnect(disconnectedGatt: BluetoothGatt, message: String) {
        val wasCurrentGatt = synchronized(lock) {
            if (gatt !== disconnectedGatt) {
                false
            } else {
                gatt = null
                rxCharacteristic = null
                txCharacteristic = null
                writeInProgress = false
                pendingBleWrites.clear()
                true
            }
        }
        runCatching { disconnectedGatt.close() }
        if (!wasCurrentGatt) return
        closeTcpServer()
        closeTcpClient()
        if (!running) return
        val isStillStarting = synchronized(lock) { !startupResolved }
        if (isStillStarting) failStartup(message) else startScan()
    }

    private fun onRNodeData(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val destination = synchronized(lock) {
            val socket = clientSocket
            if (socket == null) {
                if (pendingTcpWrites.sumOf { it.size } + bytes.size <= MAX_PENDING_BYTES) {
                    pendingTcpWrites.addLast(bytes.copyOf())
                }
                null
            } else {
                socket
            }
        }
        if (destination != null) sendToTcp(destination, bytes.copyOf())
    }

    /**
     * Listens for RNS only while the RNode can answer. RNS reconnects its
     * RNode interface without detecting the device again, and stops retrying
     * for good once the radio does not confirm its configuration. A session
     * with no RNode behind it would end that way, while a refused connection
     * is simply retried.
     */
    private fun openTcpServer(readyGatt: BluetoothGatt): Boolean {
        synchronized(lock) {
            if (!running || gatt !== readyGatt) return false
            if (serverSocket != null) return true
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), TCP_PORT), 1)
                }
            } catch (_: IOException) {
                return false
            }
            serverSocket = server
            Thread({ acceptTcpConnections(server) }, "starling-rnode-ble-accept").apply {
                isDaemon = true
                start()
            }
            return true
        }
    }

    private fun closeTcpServer() {
        val closing = synchronized(lock) { serverSocket.also { serverSocket = null } }
        runCatching { closing?.close() }
    }

    private fun acceptTcpConnections(server: ServerSocket) {
        while (running) {
            val accepted = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            val acceptedForUse = synchronized(lock) {
                if (!running || serverSocket !== server || clientSocket != null) false else {
                    clientSocket = accepted
                    true
                }
            }
            if (!acceptedForUse) {
                runCatching { accepted.close() }
                continue
            }
            flushPendingTcpWrites()
            Thread({ readTcpInput(accepted) }, "starling-rnode-ble-read").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readTcpInput(socket: Socket) {
        val input = runCatching { socket.getInputStream() }.getOrNull() ?: return closeTcpClient(socket)
        val buffer = ByteArray(TCP_READ_BUFFER_BYTES)
        try {
            while (running && !socket.isClosed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) queueBleWrites(buffer.copyOf(count))
            }
        } catch (_: IOException) {
            // RNS reconnects its TCP client when the local bridge socket closes.
        } finally {
            closeTcpClient(socket)
        }
    }

    private fun queueBleWrites(bytes: ByteArray) {
        var overflowSocket: Socket? = null
        synchronized(lock) {
            if (!running) return
            if (pendingBleWrites.sumOf { it.size } + bytes.size > MAX_PENDING_BYTES) {
                overflowSocket = closeTcpClientLocked()
                pendingBleWrites.clear()
            } else {
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + writeChunkSize, bytes.size)
                    pendingBleWrites.addLast(bytes.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
        runCatching { overflowSocket?.close() }
        pumpBleWrites()
    }

    private fun pumpBleWrites() {
        val work = synchronized(lock) {
            if (!running || writeInProgress || pendingBleWrites.isEmpty()) return
            val activeGatt = gatt ?: return
            val characteristic = rxCharacteristic ?: return
            val chunk = pendingBleWrites.removeFirst()
            writeInProgress = true
            Triple(activeGatt, characteristic, chunk)
        }
        val status = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                work.first.writeCharacteristic(
                    work.second,
                    work.third,
                    writeType,
                )
            } else {
                @Suppress("DEPRECATION")
                work.second.writeType = writeType
                @Suppress("DEPRECATION")
                work.second.value = work.third
                @Suppress("DEPRECATION")
                if (work.first.writeCharacteristic(work.second)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
        } catch (_: SecurityException) {
            BluetoothGatt.GATT_FAILURE
        } catch (_: Exception) {
            // The Bluetooth stack can die under an active connection, for
            // example when the adapter is switched off, and the write then
            // throws instead of reporting a status. That is a lost link, not
            // a reason to take down the RNS process.
            handleGattDisconnect(work.first, "The BLE RNode connection was lost.")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            synchronized(lock) {
                writeInProgress = false
                if (running) pendingBleWrites.addFirst(work.third)
            }
            mainHandler.postDelayed({ pumpBleWrites() }, WRITE_RETRY_DELAY_MS)
        }
    }

    private fun flushPendingTcpWrites() {
        val (socket, writes) = synchronized(lock) {
            val currentSocket = clientSocket ?: return
            val queued = buildList {
                while (pendingTcpWrites.isNotEmpty()) add(pendingTcpWrites.removeFirst())
            }
            currentSocket to queued
        }
        writes.forEach { sendToTcp(socket, it) }
    }

    private fun sendToTcp(socket: Socket, bytes: ByteArray) {
        ioExecutor.execute {
            try {
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            } catch (_: IOException) {
                closeTcpClient(socket)
            }
        }
    }

    private fun closeTcpClient(expected: Socket? = null) {
        val closing = synchronized(lock) {
            if (expected != null && clientSocket !== expected) return
            closeTcpClientLocked()
        }
        runCatching { closing?.close() }
    }

    private fun closeTcpClientLocked(): Socket? {
        val closing = clientSocket
        clientSocket = null
        pendingTcpWrites.clear()
        return closing
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val TCP_PORT = 7633
        private const val STARTUP_TIMEOUT_SECONDS = 40L
        private const val DEFAULT_WRITE_CHUNK = 20
        private const val MAX_PENDING_BYTES = 128 * 1024
        private const val TCP_READ_BUFFER_BYTES = 4096
        private const val WRITE_RETRY_DELAY_MS = 30L
        private const val RECONNECT_SCAN_DELAY_MS = 2_000L
        private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
