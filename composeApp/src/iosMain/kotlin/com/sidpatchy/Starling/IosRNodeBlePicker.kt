package com.sidpatchy.Starling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Receives CoreBluetooth results from Swift. It is a named interface so the
 * Objective-C export has plain `void` methods for Swift to call.
 */
interface IosRNodeBleListener {
    /** [status] is one of the `IOS_RNODE_SCAN_*` values; [code] is only set for a failure. */
    fun onScanStatus(status: String, code: Int)
    fun onDevice(identifier: String, name: String, rssi: Int)

    /** [outcome] is one of the `IOS_RNODE_TRIAL_*` values. */
    fun onTrialResult(outcome: String)
}

const val IOS_RNODE_SCAN_SCANNING = "scanning"
const val IOS_RNODE_SCAN_BLUETOOTH_OFF = "bluetooth_off"
const val IOS_RNODE_SCAN_UNAUTHORIZED = "unauthorized"
const val IOS_RNODE_SCAN_UNSUPPORTED = "unsupported"
const val IOS_RNODE_SCAN_FAILED = "failed"
const val IOS_RNODE_TRIAL_READY = "ready"
const val IOS_RNODE_TRIAL_STALE = "stale"
const val IOS_RNODE_TRIAL_FAILED = "failed"

/** Maps a failure code from the Swift RNode bridge to the message shown for [name]. */
internal fun iosRNodeBleProxyError(error: String, name: String): String =
    if (!error.startsWith(IOS_RNODE_PROXY_ERROR_PREFIX)) error else when (error.removePrefix(IOS_RNODE_PROXY_ERROR_PREFIX)) {
        "not_found" -> RNodeBleText.notFound(name)
        "not_paired" -> RNodeBleText.notPairedIos(name)
        "pairing_lost" -> RNodeBleText.pairingLost(name)
        "permission" -> RNodeBleText.permission(name)
        "held_elsewhere" -> RNodeBleText.heldElsewhere(name)
        "unknown_id" -> RNodeBleText.unknownToIos(name)
        else -> RNodeBleText.notFound(name)
    }

/** Swift prefixes a failure it reports by code, so the wording stays in shared code. */
const val IOS_RNODE_PROXY_ERROR_PREFIX = "RNODE_BLE:"

/** Finds RNodes and makes the first trial connection through the Swift CoreBluetooth scanner. */
class IosRNodeBlePicker(private val runtime: IosPythonRuntime) : RNodeBlePicker, IosRNodeBleListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val platform = RNodeBlePlatform.IOS
    private val _scanStatus = MutableStateFlow<RNodeBleScanStatus>(RNodeBleScanStatus.Idle)
    override val scanStatus: StateFlow<RNodeBleScanStatus> = _scanStatus.asStateFlow()
    private val _sightings = MutableSharedFlow<RNodeBleSighting>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sightings: Flow<RNodeBleSighting> = _sightings.asSharedFlow()
    private val _pairing = MutableStateFlow<RNodeBlePairingState>(RNodeBlePairingState.Idle)
    override val pairing: StateFlow<RNodeBlePairingState> = _pairing.asStateFlow()

    private var scanTimeout: Job? = null
    private var trialTimeout: Job? = null
    private var trialName: String? = null

    override fun startScan() {
        scanTimeout?.cancel()
        runtime.stopRNodeBleScan()
        // iOS asks for Bluetooth access the first time the scanner is used.
        runtime.startRNodeBleScan(this)
    }

    override fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        runtime.stopRNodeBleScan()
        if (_scanStatus.value == RNodeBleScanStatus.Scanning) _scanStatus.value = RNodeBleScanStatus.Finished
    }

    override fun resolveScanBlocker() {
        if (_scanStatus.value != RNodeBleScanStatus.PermissionPermanentlyDenied) return
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    override fun pair(deviceId: String, name: String) {
        trialTimeout?.cancel()
        runtime.cancelRNodeBleTrial()
        val label = name.ifBlank { "RNode" }
        trialName = label
        _pairing.value = RNodeBlePairingState.Waiting(label)
        runtime.startRNodeBleTrial(deviceId, this)
        trialTimeout = scope.launch {
            delay(TRIAL_TIMEOUT_MS)
            runtime.cancelRNodeBleTrial()
            finishTrial(RNodeBlePairingState.Failed(label, RNodeBlePairingFailure.TIMEOUT))
        }
    }

    override fun cancelPairing() {
        trialTimeout?.cancel()
        trialTimeout = null
        trialName = null
        runtime.cancelRNodeBleTrial()
        _pairing.value = RNodeBlePairingState.Idle
    }

    /** iOS does not tell apps whether a peripheral is paired. */
    override fun isPaired(deviceId: String): Boolean? = null

    override fun onScanStatus(status: String, code: Int) {
        scope.launch {
            when (status) {
                IOS_RNODE_SCAN_SCANNING -> {
                    _scanStatus.value = RNodeBleScanStatus.Scanning
                    scanTimeout?.cancel()
                    scanTimeout = scope.launch {
                        delay(SCAN_DURATION_MS)
                        stopScan()
                    }
                }
                IOS_RNODE_SCAN_BLUETOOTH_OFF -> _scanStatus.value = RNodeBleScanStatus.BluetoothOff
                IOS_RNODE_SCAN_UNAUTHORIZED -> _scanStatus.value = RNodeBleScanStatus.PermissionPermanentlyDenied
                IOS_RNODE_SCAN_UNSUPPORTED -> _scanStatus.value = RNodeBleScanStatus.Unsupported
                else -> _scanStatus.value = RNodeBleScanStatus.Failed(code)
            }
        }
    }

    override fun onDevice(identifier: String, name: String, rssi: Int) {
        _sightings.tryEmit(RNodeBleSighting(identifier, name, rssi))
    }

    override fun onTrialResult(outcome: String) {
        scope.launch {
            val label = trialName ?: return@launch
            finishTrial(
                when (outcome) {
                    IOS_RNODE_TRIAL_READY -> RNodeBlePairingState.Ready(label)
                    IOS_RNODE_TRIAL_STALE -> RNodeBlePairingState.Failed(label, RNodeBlePairingFailure.STALE_BOND)
                    else -> RNodeBlePairingState.Failed(label, RNodeBlePairingFailure.OTHER)
                },
            )
        }
    }

    private fun finishTrial(result: RNodeBlePairingState) {
        if (trialName == null) return
        trialName = null
        trialTimeout?.cancel()
        trialTimeout = null
        _pairing.value = result
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
        const val TRIAL_TIMEOUT_MS = 60_000L
    }
}
