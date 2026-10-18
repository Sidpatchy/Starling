import CoreBluetooth
import Foundation
import Network

/// Exposes an RNode's Nordic UART BLE service as a loopback TCP serial port.
/// The pinned RNS TCP RNode transport then handles the device protocol unchanged.
final class StarlingRNodeBluetoothBridge: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    private static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private static let tcpPort = NWEndpoint.Port(rawValue: 7633)!

    private let queue = DispatchQueue(label: "com.sidpatchy.Starling.rnode-ble")
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?
    private var listener: NWListener?
    private var tcpConnection: NWConnection?
    private var targetName: String?
    private var startWaiter: DispatchSemaphore?
    private var startupError: String?
    private var startupResolved = false
    private var scanning = false
    private var running = false
    private var pendingWrite = Data()
    private var pendingRead = Data()

    /// Returns nil when the BLE link and loopback listener are ready, or a user-facing error.
    func start(targetName: String?) -> String? {
        let waiter = DispatchSemaphore(value: 0)
        queue.async {
            guard self.central == nil, self.listener == nil else {
                self.startupError = "An iOS BLE RNode connection is already active."
                waiter.signal()
                return
            }
            self.targetName = targetName?.trimmingCharacters(in: .whitespacesAndNewlines)
            self.startWaiter = waiter
            self.startupError = nil
            self.startupResolved = false
            self.running = true
            self.central = CBCentralManager(delegate: self, queue: self.queue)
        }

        guard waiter.wait(timeout: .now() + 40) == .success else {
            stop()
            return "Timed out connecting to the BLE RNode. Check that it is powered on and nearby."
        }
        return queue.sync { startupError }
    }

    func stop() {
        queue.async {
            self.running = false
            self.scanning = false
            self.central?.stopScan()
            if let peripheral = self.peripheral {
                self.central?.cancelPeripheralConnection(peripheral)
                peripheral.delegate = nil
            }
            self.tcpConnection?.cancel()
            self.listener?.cancel()
            self.central = nil
            self.peripheral = nil
            self.rxCharacteristic = nil
            self.txCharacteristic = nil
            self.tcpConnection = nil
            self.listener = nil
            self.pendingWrite.removeAll(keepingCapacity: false)
            self.pendingRead.removeAll(keepingCapacity: false)
            if !self.startupResolved {
                self.resolveStart(error: "The BLE RNode connection was cancelled.")
            }
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            guard !scanning, running else { return }
            scanning = true
            central.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
        case .poweredOff:
            resolveStart(error: "Turn on Bluetooth to connect to a BLE RNode.")
        case .unauthorized:
            resolveStart(error: "Allow Starling to use Bluetooth in iOS Settings.")
        case .unsupported:
            resolveStart(error: "Bluetooth LE is not available on this device.")
        case .resetting, .unknown:
            break
        @unknown default:
            resolveStart(error: "The iOS Bluetooth service is unavailable.")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let matchesTarget: Bool
        if let targetName, !targetName.isEmpty {
            matchesTarget = name.caseInsensitiveCompare(targetName) == .orderedSame
        } else {
            matchesTarget = name.hasPrefix("RNode")
        }
        guard matchesTarget else { return }

        scanning = false
        central.stopScan()
        self.peripheral = peripheral
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if running {
            scanning = false
            central.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
            scanning = true
        } else {
            resolveStart(error: error?.localizedDescription ?? "Could not connect to the BLE RNode.")
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        closeTCPConnection()
        guard running else { return }
        rxCharacteristic = nil
        txCharacteristic = nil
        central.connect(peripheral, options: nil)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            resolveStart(error: "Could not inspect the BLE RNode services: \(error.localizedDescription)")
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            resolveStart(error: "The BLE device does not provide the RNode UART service.")
            return
        }
        peripheral.discoverCharacteristics([Self.rxUUID, Self.txUUID], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error {
            resolveStart(error: "Could not open the BLE RNode UART service: \(error.localizedDescription)")
            return
        }
        guard let characteristics = service.characteristics,
              let rx = characteristics.first(where: { $0.uuid == Self.rxUUID }),
              let tx = characteristics.first(where: { $0.uuid == Self.txUUID }) else {
            resolveStart(error: "The BLE device is missing an RNode UART characteristic.")
            return
        }
        guard rx.properties.contains(.writeWithoutResponse),
              tx.properties.contains(.notify) || tx.properties.contains(.indicate) else {
            resolveStart(error: "The BLE device's UART characteristics are not compatible with RNode.")
            return
        }
        rxCharacteristic = rx
        txCharacteristic = tx
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == Self.txUUID else { return }
        if let error {
            resolveStart(error: "Could not subscribe to BLE RNode data: \(error.localizedDescription)")
            return
        }
        guard characteristic.isNotifying else {
            resolveStart(error: "The BLE RNode did not enable UART notifications.")
            return
        }
        flushPendingWrites()
        startLoopbackListener()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.txUUID, error == nil,
              let data = characteristic.value, !data.isEmpty else { return }
        guard let tcpConnection else {
            pendingRead.append(data)
            if pendingRead.count > 65_536 {
                pendingRead.removeFirst(pendingRead.count - 65_536)
            }
            return
        }
        tcpConnection.send(content: data, completion: .contentProcessed { [weak self] error in
            if error != nil {
                self?.closeTCPConnection()
            }
        })
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        flushPendingWrites()
    }

    private func startLoopbackListener() {
        do {
            let parameters = NWParameters.tcp
            parameters.requiredLocalEndpoint = .hostPort(
                host: .ipv4(.loopback),
                port: Self.tcpPort
            )
            let listener = try NWListener(using: parameters, on: Self.tcpPort)
            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.resolveStart(error: nil)
                case .failed(let error):
                    self.resolveStart(error: "Could not open the local BLE RNode bridge: \(error.localizedDescription)")
                case .cancelled:
                    break
                default:
                    break
                }
            }
            self.listener = listener
            listener.start(queue: queue)
        } catch {
            resolveStart(error: "Could not start the local BLE RNode bridge: \(error.localizedDescription)")
        }
    }

    private func accept(_ connection: NWConnection) {
        guard tcpConnection == nil else {
            connection.cancel()
            return
        }
        tcpConnection = connection
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.closeTCPConnection() }
        }
        connection.start(queue: queue)
        if !pendingRead.isEmpty {
            let initialData = pendingRead
            pendingRead.removeAll(keepingCapacity: true)
            connection.send(content: initialData, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.closeTCPConnection() }
            })
        }
        receive(on: connection)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.pendingWrite.append(data)
                self.flushPendingWrites()
            }
            if isComplete || error != nil {
                self.closeTCPConnection()
            } else {
                self.receive(on: connection)
            }
        }
    }

    private func flushPendingWrites() {
        guard let peripheral, let rxCharacteristic else { return }
        let chunkLimit = peripheral.maximumWriteValueLength(for: .withoutResponse)
        guard chunkLimit > 0 else { return }
        while !pendingWrite.isEmpty && peripheral.canSendWriteWithoutResponse {
            let count = min(chunkLimit, pendingWrite.count)
            let chunk = Data(pendingWrite.prefix(count))
            pendingWrite.removeFirst(count)
            peripheral.writeValue(chunk, for: rxCharacteristic, type: .withoutResponse)
        }
    }

    private func closeTCPConnection() {
        tcpConnection?.cancel()
        tcpConnection = nil
        pendingWrite.removeAll(keepingCapacity: true)
        pendingRead.removeAll(keepingCapacity: true)
    }

    private func resolveStart(error: String?) {
        guard !startupResolved else { return }
        startupResolved = true
        startupError = error
        startWaiter?.signal()
        startWaiter = nil
    }
}
