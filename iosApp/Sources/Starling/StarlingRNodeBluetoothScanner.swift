import ComposeApp
import CoreBluetooth
import Foundation

/// Finds nearby RNodes for Settings and makes the first trial connection to the one
/// that is picked. It is separate from the bridge that carries RNode traffic and is
/// only used while the node is stopped.
final class StarlingRNodeBluetoothScanner: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    private static var serviceUUID: CBUUID { CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E") }
    private static var txUUID: CBUUID { CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E") }

    /// Carries a Kotlin listener onto the Bluetooth queue; its methods are safe to call from any thread.
    private struct Listener: @unchecked Sendable {
        let value: IosRNodeBleListener
    }

    private let queue = DispatchQueue(label: "com.sidpatchy.Starling.rnode-ble-scan")
    private var central: CBCentralManager?
    /// Peripherals must be retained to be connected to after the scan stops.
    private var discovered: [UUID: CBPeripheral] = [:]
    private var scanListener: IosRNodeBleListener?
    private var scanning = false
    private var trialListener: IosRNodeBleListener?
    private var trialIdentifier: UUID?
    private var trialPeripheral: CBPeripheral?

    /// Creating the central manager is what makes iOS ask for Bluetooth access.
    func startScan(listener: IosRNodeBleListener) {
        let listener = Listener(value: listener)
        queue.async {
            self.scanListener = listener.value
            if let central = self.central {
                self.apply(state: central.state)
            } else {
                self.central = CBCentralManager(delegate: self, queue: self.queue)
            }
        }
    }

    func stopScan() {
        queue.async {
            self.scanListener = nil
            self.endScan()
        }
    }

    /// Connects, finds the UART service, and subscribes to it. iOS shows its pairing
    /// prompt during the subscription if the RNode requires pairing.
    func startTrial(identifier: String, listener: IosRNodeBleListener) {
        let listener = Listener(value: listener)
        queue.async {
            self.endTrial()
            self.endScan()
            guard let uuid = UUID(uuidString: identifier) else {
                listener.value.onTrialResult(outcome: "failed")
                return
            }
            self.trialListener = listener.value
            self.trialIdentifier = uuid
            if let central = self.central {
                self.apply(state: central.state)
            } else {
                self.central = CBCentralManager(delegate: self, queue: self.queue)
            }
        }
    }

    func cancelTrial() {
        queue.async { self.endTrial() }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        apply(state: central.state)
    }

    private func apply(state: CBManagerState) {
        switch state {
        case .poweredOn:
            if trialIdentifier != nil {
                beginTrial()
            } else if scanListener != nil, !scanning, let central {
                scanning = true
                // Repeated advertisements keep the signal strength current.
                central.scanForPeripherals(
                    withServices: [Self.serviceUUID],
                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
                )
                scanListener?.onScanStatus(status: "scanning", code: 0)
            }
        case .poweredOff:
            scanning = false
            scanListener?.onScanStatus(status: "bluetooth_off", code: 0)
            finishTrial(outcome: "failed")
        case .unauthorized:
            scanning = false
            scanListener?.onScanStatus(status: "unauthorized", code: 0)
            finishTrial(outcome: "failed")
        case .unsupported:
            scanning = false
            scanListener?.onScanStatus(status: "unsupported", code: 0)
            finishTrial(outcome: "failed")
        case .resetting, .unknown:
            break
        @unknown default:
            scanning = false
            scanListener?.onScanStatus(status: "failed", code: Int32(state.rawValue))
            finishTrial(outcome: "failed")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // 127 means the signal strength is not available for this advertisement.
        guard scanning, RSSI.intValue != 127 else { return }
        discovered[peripheral.identifier] = peripheral
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        scanListener?.onDevice(
            identifier: peripheral.identifier.uuidString,
            name: name,
            rssi: Int32(RSSI.intValue)
        )
    }

    private func beginTrial() {
        guard let central, let trialIdentifier, trialPeripheral == nil else { return }
        guard let peripheral = discovered[trialIdentifier]
            ?? central.retrievePeripherals(withIdentifiers: [trialIdentifier]).first else {
            finishTrial(outcome: "failed")
            return
        }
        trialPeripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === trialPeripheral else { return }
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === trialPeripheral else { return }
        finishTrial(outcome: Self.outcome(for: error))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === trialPeripheral else { return }
        finishTrial(outcome: Self.outcome(for: error))
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === trialPeripheral else { return }
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            finishTrial(outcome: Self.outcome(for: error))
            return
        }
        peripheral.discoverCharacteristics([Self.txUUID], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard peripheral === trialPeripheral else { return }
        guard error == nil,
              let tx = service.characteristics?.first(where: { $0.uuid == Self.txUUID }) else {
            finishTrial(outcome: Self.outcome(for: error))
            return
        }
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === trialPeripheral, characteristic.uuid == Self.txUUID else { return }
        if error == nil, characteristic.isNotifying {
            finishTrial(outcome: "ready")
        } else {
            finishTrial(outcome: Self.outcome(for: error))
        }
    }

    private static func outcome(for error: Error?) -> String {
        StarlingRNodeBluetoothBridge.isPairingRemoved(error) ? "stale" : "failed"
    }

    private func finishTrial(outcome: String) {
        guard let listener = trialListener else { return }
        endTrial()
        listener.onTrialResult(outcome: outcome)
    }

    /// Drops the trial connection; the RNode is left free for the node to connect to.
    private func endTrial() {
        trialListener = nil
        trialIdentifier = nil
        if let peripheral = trialPeripheral {
            trialPeripheral = nil
            peripheral.delegate = nil
            central?.cancelPeripheralConnection(peripheral)
        }
    }

    private func endScan() {
        guard scanning else { return }
        scanning = false
        if central?.state == .poweredOn { central?.stopScan() }
    }
}
