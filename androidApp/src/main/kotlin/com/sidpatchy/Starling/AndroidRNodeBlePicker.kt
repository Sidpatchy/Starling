package com.sidpatchy.Starling

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
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
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** What the user answered when Android asked for Bluetooth access. */
enum class BluetoothPermissionResult { GRANTED, DENIED, PERMANENTLY_DENIED }

/**
 * Finds RNodes and pairs with them for Settings. It runs in the UI process
 * and only while the node is stopped, so it never competes with the bridge
 * that carries RNode traffic in the node's own process.
 */
internal class AndroidRNodeBlePicker(
    context: Context,
    private val requestPermissions: suspend (Array<String>) -> BluetoothPermissionResult,
    private val requestEnableBluetooth: suspend () -> Unit,
    private val startActivity: (Intent) -> Unit,
) : RNodeBlePicker {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val platform = RNodeBlePlatform.ANDROID
    private val _scanStatus = MutableStateFlow<RNodeBleScanStatus>(RNodeBleScanStatus.Idle)
    override val scanStatus: StateFlow<RNodeBleScanStatus> = _scanStatus.asStateFlow()
    private val _sightings = MutableSharedFlow<RNodeBleSighting>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sightings: Flow<RNodeBleSighting> = _sightings.asSharedFlow()
    private val _pairing = MutableStateFlow<RNodeBlePairingState>(RNodeBlePairingState.Idle)
    override val pairing: StateFlow<RNodeBlePairingState> = _pairing.asStateFlow()

    private var scanJob: Job? = null
    private var activeScanner: BluetoothLeScanner? = null
    private val scanStarts = mutableListOf<Long>()

    private var pairingDevice: BluetoothDevice? = null
    private var pairingName = ""
    private var pairingTimeout: Job? = null
    private var pairingGatt: BluetoothGatt? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var bondRequested = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull().orEmpty()
            _sightings.tryEmit(RNodeBleSighting(result.device.address, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch {
                scanJob?.cancel()
                activeScanner = null
                _scanStatus.value = RNodeBleScanStatus.Failed(errorCode)
            }
        }
    }

    override fun startScan() {
        stopScanner()
        scanJob?.cancel()
        scanJob = scope.launch {
            val bluetooth = adapter
            if (bluetooth == null || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                _scanStatus.value = RNodeBleScanStatus.Unsupported
                return@launch
            }
            val permissions = scanPermissions()
            if (!hasPermissions(permissions)) {
                if (_scanStatus.value != RNodeBleScanStatus.PermissionDenied) {
                    _scanStatus.value = RNodeBleScanStatus.PermissionNotAsked
                }
                when (requestPermissions(permissions)) {
                    BluetoothPermissionResult.GRANTED -> Unit
                    BluetoothPermissionResult.DENIED -> {
                        _scanStatus.value = RNodeBleScanStatus.PermissionDenied
                        return@launch
                    }
                    BluetoothPermissionResult.PERMANENTLY_DENIED -> {
                        _scanStatus.value = RNodeBleScanStatus.PermissionPermanentlyDenied
                        return@launch
                    }
                }
            }
            if (!bluetooth.isEnabled) {
                _scanStatus.value = RNodeBleScanStatus.BluetoothOff
                return@launch
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locationEnabled()) {
                _scanStatus.value = RNodeBleScanStatus.LocationOff
                return@launch
            }
            val scanner = bluetooth.bluetoothLeScanner
            if (scanner == null) {
                _scanStatus.value = RNodeBleScanStatus.BluetoothOff
                return@launch
            }
            _scanStatus.value = RNodeBleScanStatus.Scanning
            // Android stops returning results to an app that starts scans too often.
            delay(rnodeScanStartDelayMillis(scanStarts, SystemClock.elapsedRealtime()))
            try {
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (_: SecurityException) {
                _scanStatus.value = RNodeBleScanStatus.PermissionDenied
                return@launch
            }
            activeScanner = scanner
            val startedAt = SystemClock.elapsedRealtime()
            scanStarts += startedAt
            scanStarts.removeAll { startedAt - it > SCAN_START_HISTORY_MS }
            delay(SCAN_DURATION_MS)
            stopScanner()
            _scanStatus.value = RNodeBleScanStatus.Finished
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        stopScanner()
        if (_scanStatus.value == RNodeBleScanStatus.Scanning) _scanStatus.value = RNodeBleScanStatus.Finished
    }

    private fun stopScanner() {
        val scanner = activeScanner ?: return
        activeScanner = null
        runCatching { scanner.stopScan(scanCallback) }
    }

    override fun resolveScanBlocker() {
        when (_scanStatus.value) {
            RNodeBleScanStatus.PermissionNotAsked, RNodeBleScanStatus.PermissionDenied -> startScan()
            RNodeBleScanStatus.PermissionPermanentlyDenied -> open(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null)),
            )
            RNodeBleScanStatus.BluetoothOff -> scope.launch {
                runCatching { requestEnableBluetooth() }
                startScan()
            }
            RNodeBleScanStatus.LocationOff -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> Unit
        }
    }

    override fun openBluetoothSettings() = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    override fun isPaired(deviceId: String): Boolean? {
        if (!hasPermissions(connectPermissions())) return null
        return runCatching { remoteDevice(deviceId)?.bondState == BluetoothDevice.BOND_BONDED }.getOrNull()
    }

    override fun knownName(deviceId: String): String? {
        if (!hasPermissions(connectPermissions())) return null
        return runCatching { remoteDevice(deviceId)?.name }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun remoteDevice(deviceId: String): BluetoothDevice? {
        val address = deviceId.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        return adapter?.getRemoteDevice(address)
    }

    override fun pair(deviceId: String, name: String) {
        endPairing()
        val label = name.ifBlank { "RNode" }
        val device = try {
            remoteDevice(deviceId)
        } catch (_: SecurityException) {
            null
        }
        if (device == null) {
            _pairing.value = RNodeBlePairingState.Failed(label, RNodeBlePairingFailure.OTHER)
            return
        }
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                _pairing.value = RNodeBlePairingState.AlreadyPaired(label)
                return
            }
            pairingDevice = device
            pairingName = label
            bondRequested = false
            _pairing.value = RNodeBlePairingState.Waiting(label)
            registerBondReceiver()
            pairingTimeout = scope.launch {
                delay(PAIRING_TIMEOUT_MS)
                failPairing(RNodeBlePairingFailure.TIMEOUT, 0)
            }
            // createBond() cannot name a transport. For a device Android knows as
            // dual-mode or classic it may pair over classic Bluetooth, which does
            // not cover the LE UART service, so those devices are paired from an
            // LE connection instead: reading the protected service makes Android
            // start LE pairing itself.
            val type = device.type
            val pairOverGatt = type == BluetoothDevice.DEVICE_TYPE_DUAL || type == BluetoothDevice.DEVICE_TYPE_CLASSIC
            if (pairOverGatt || !requestBond(device)) connectForPairing(device)
        } catch (_: SecurityException) {
            failPairing(RNodeBlePairingFailure.OTHER, 0)
        }
    }

    override fun cancelPairing() {
        endPairing()
        _pairing.value = RNodeBlePairingState.Idle
    }

    private fun requestBond(device: BluetoothDevice): Boolean {
        bondRequested = true
        return device.createBond()
    }

    private fun registerBondReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = pairingDevice ?: return
                val changed: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changed?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                when {
                    state == BluetoothDevice.BOND_BONDED -> finishPairing(RNodeBlePairingState.Paired(pairingName))
                    state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                        // Android does not document this extra; it is read on a best-effort basis.
                        val reason = intent.getIntExtra(EXTRA_UNBOND_REASON, 0)
                        failPairing(
                            when (reason) {
                                UNBOND_REASON_AUTH_FAILED -> RNodeBlePairingFailure.WRONG_PIN
                                UNBOND_REASON_AUTH_TIMEOUT -> RNodeBlePairingFailure.TIMEOUT
                                else -> RNodeBlePairingFailure.OTHER
                            },
                            reason,
                        )
                    }
                }
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** Opens the LE UART service so Android pairs if the RNode protects it. */
    private fun connectForPairing(device: BluetoothDevice) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) gattPairingFailed(gatt, 0)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gattPairingFailed(gatt, status)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val tx = gatt.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID)
                val cccd = tx?.getDescriptor(CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || tx == null || cccd == null) {
                    gattPairingFailed(gatt, status)
                    return
                }
                val value = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                val written = runCatching {
                    gatt.setCharacteristicNotification(tx, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = value
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }.getOrDefault(false)
                if (!written) gattPairingFailed(gatt, 0)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CCCD_UUID) return
                scope.launch {
                    if (pairingGatt !== gatt) return@launch
                    val bondState = runCatching { gatt.device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                    when {
                        bondState == BluetoothDevice.BOND_BONDING -> Unit
                        status == BluetoothGatt.GATT_SUCCESS && bondState == BluetoothDevice.BOND_BONDED ->
                            finishPairing(RNodeBlePairingState.Paired(pairingName))
                        // The RNode accepted the subscription without a pairing.
                        status == BluetoothGatt.GATT_SUCCESS -> finishPairing(RNodeBlePairingState.Ready(pairingName))
                        // The service is protected and Android did not start pairing on its own.
                        !bondRequested && runCatching { requestBond(gatt.device) }.getOrDefault(false) -> Unit
                        else -> failPairing(RNodeBlePairingFailure.OTHER, status)
                    }
                }
            }
        }
        val gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) failPairing(RNodeBlePairingFailure.OTHER, 0) else pairingGatt = gatt
    }

    private fun gattPairingFailed(gatt: BluetoothGatt, status: Int) {
        scope.launch {
            if (pairingGatt !== gatt) return@launch
            // The link can drop while Android is pairing; the pairing result decides the outcome.
            val bonding = runCatching { gatt.device.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)
            if (!bonding) failPairing(RNodeBlePairingFailure.OTHER, status)
        }
    }

    private fun finishPairing(result: RNodeBlePairingState) {
        if (pairingDevice == null) return
        endPairing()
        _pairing.value = result
    }

    private fun failPairing(reason: RNodeBlePairingFailure, code: Int) {
        if (pairingDevice == null) return
        val name = pairingName
        endPairing()
        _pairing.value = RNodeBlePairingState.Failed(name, reason, code)
    }

    /** Releases everything a pairing attempt holds. A pairing Android has already started is left to finish. */
    private fun endPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
        bondReceiver?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
        bondReceiver = null
        pairingGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        pairingGatt = null
        pairingDevice = null
    }

    fun dispose() {
        stopScan()
        endPairing()
        scope.cancel()
    }

    private fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun connectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
        const val SCAN_START_HISTORY_MS = 60_000L
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
        const val UNBOND_REASON_AUTH_FAILED = 1
        const val UNBOND_REASON_AUTH_TIMEOUT = 6
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
