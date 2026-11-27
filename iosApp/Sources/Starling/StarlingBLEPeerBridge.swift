import CoreBluetooth
import Foundation
import PythonRuntimeShim

/// CoreBluetooth transport for the iOS `_starling_ble_ios` callback ABI. All
/// CoreBluetooth state and callbacks are confined to `queue`; Python drains
/// immutable JSON events separately.
// Mutable CoreBluetooth state is accessed only on `queue`; synchronous callback
// entry points marshal all stateful work onto that queue.
final class StarlingBLEPeerBridge: NSObject, StarlingBLEProvider, CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate, @unchecked Sendable {
    static let shared = StarlingBLEPeerBridge()
    private let queue = DispatchQueue(label: "com.sidpatchy.Starling.peer-ble")
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var service: CBUUID!
    private var rx: CBUUID!
    private var tx: CBUUID!
    private var identityUUID: CBUUID!
    private var identity = Data()
    private var maxPeers = 7
    private var scanTimeout: TimeInterval = 30
    private var connectionTimeout: TimeInterval = 30
    private var running = false
    private var scanning = false
    private var scanningRequested = false
    private var advertisingRequested = false
    private var peers: [String: Peer] = [:]
    private var peripherals: [String: CBPeripheral] = [:]
    private var pendingConnections = Set<String>()
    private var subscribed = Set<String>()
    private var subscribedCentrals: [String: CBCentral] = [:]
    private var connectionGenerations: [String: UUID] = [:]
    private var events = [[String: Any]]()
    private var txCharacteristic: CBMutableCharacteristic?
    private var pendingNotifications: [(address: String, data: Data)] = []
    private let notificationQueueLimit = 256

    private struct Peer {
        var peripheral: CBPeripheral?
        var state = "connecting"
        var mtu = 20
        var remoteIdentity: Data?
        var rx: CBCharacteristic?
        var tx: CBCharacteristic?
        var identity: CBCharacteristic?
        var role = "central"
    }

    private override init() { super.init() }

    func registerCallbacks() { StarlingBLERegisterProvider(self) }

    func performBLEOperation(_ operation: String, arguments: [Data]) -> [String: Any] {
        func response(_ error: String? = nil, data: Data = Data()) -> [String: Any] {
            if let error { return ["error": error] }
            return ["data": data]
        }
        let first = arguments.first ?? Data()
        switch operation {
        case "start":
            guard let config = try? JSONSerialization.jsonObject(with: first) as? [String: Any] else { return response("invalid BLE start configuration") }
            return response(StarlingBLEPeerBridge.shared.start(config))
        case "set_identity": return response(first.count == 16 ? StarlingBLEPeerBridge.shared.setIdentity(first) : "identity must be exactly 16 bytes")
        case "stop": StarlingBLEPeerBridge.shared.stop(); return response()
        case "start_scanning": return response(StarlingBLEPeerBridge.shared.setScanning(true))
        case "stop_scanning": return response(StarlingBLEPeerBridge.shared.setScanning(false))
        case "start_advertising": return response(StarlingBLEPeerBridge.shared.setAdvertising(true))
        case "stop_advertising": return response(StarlingBLEPeerBridge.shared.setAdvertising(false))
        case "connect": return response(StarlingBLEPeerBridge.shared.connect(String(decoding: first, as: UTF8.self)))
        case "disconnect": StarlingBLEPeerBridge.shared.disconnect(String(decoding: first, as: UTF8.self)); return response()
        case "write": return response(StarlingBLEPeerBridge.shared.write(String(decoding: first, as: UTF8.self), arguments.count > 1 ? arguments[1] : Data()))
        case "notify": return response(StarlingBLEPeerBridge.shared.notify(String(decoding: first, as: UTF8.self), arguments.count > 1 ? arguments[1] : Data()))
        case "drain_events":
            let data = (try? JSONSerialization.data(withJSONObject: StarlingBLEPeerBridge.shared.drain(), options: [.fragmentsAllowed])) ?? Data("[]".utf8)
            return response(nil, data: data)
        default: return response("unknown BLE operation")
        }
    }

    private func sync<T>(_ work: () -> T) -> T { queue.sync(execute: work) }
    private func start(_ c: [String: Any]) -> String? { sync {
        stopOnQueue()
        guard let s = c["service_uuid"] as? String, let r = c["rx_uuid"] as? String, let t = c["tx_uuid"] as? String, let i = c["identity_uuid"] as? String else { return "missing BLE UUID configuration" }
        service = CBUUID(string: s); rx = CBUUID(string: r); tx = CBUUID(string: t); identityUUID = CBUUID(string: i)
        scanTimeout = max(1, (c["scan_timeout"] as? Double) ?? 30); connectionTimeout = max(0.1, (c["connection_timeout"] as? Double) ?? 30); maxPeers = max(1, (c["max_peers"] as? Int) ?? 7)
        if let encoded = c["identity"] as? String, !encoded.isEmpty, let value = Data(base64Encoded: encoded), value.count == 16 { identity = value }
        running = true
        let newCentral = CBCentralManager(delegate: self, queue: queue)
        let newPeripheralManager = CBPeripheralManager(delegate: self, queue: queue)
        central = newCentral
        peripheralManager = newPeripheralManager
        return nil
    } }
    private func setIdentity(_ value: Data) -> String? { sync {
        guard value.count == 16 else { return "identity must be exactly 16 bytes" }
        identity = value
        if running, advertisingRequested, let peripheralManager, peripheralManager.state == .poweredOn, txCharacteristic == nil {
            configurePeripheral(peripheralManager)
        }
        return nil
    } }
    private func stop() { sync { stopOnQueue() } }
    private func stopOnQueue() { running = false; scanning = false; scanningRequested = false; advertisingRequested = false; central?.stopScan(); peripheralManager?.stopAdvertising(); peers.values.compactMap(\.peripheral).forEach { central?.cancelPeripheralConnection($0) }; peers.values.compactMap(\.peripheral).forEach { $0.delegate = nil }; peers.removeAll(); peripherals.removeAll(); pendingConnections.removeAll(); connectionGenerations.removeAll(); subscribed.removeAll(); subscribedCentrals.removeAll(); pendingNotifications.removeAll(); txCharacteristic = nil; central = nil; peripheralManager?.removeAllServices(); peripheralManager = nil }
    private func setScanning(_ enabled: Bool) -> String? { sync { guard running else { return "BLE bridge is not running" }; scanningRequested = enabled; if enabled { beginScan() } else { scanning = false; central?.stopScan() }; return nil } }
    private func beginScan() { guard running, scanningRequested, let central, central.state == .poweredOn else { return }; central.stopScan(); scanning = true; central.scanForPeripherals(withServices: [service], options: nil); queue.asyncAfter(deadline: .now() + scanTimeout) { [weak self, weak central] in guard let self, let central, self.central === central, self.scanning, self.scanningRequested else { return }; central.stopScan(); self.scanning = false } }
    private func setAdvertising(_ enabled: Bool) -> String? { sync { guard running else { return "BLE bridge is not running" }; advertisingRequested = enabled; guard let peripheralManager else { return nil }; if enabled { configurePeripheral(peripheralManager) } else { peripheralManager.stopAdvertising() }; return nil } }
    private func connect(_ address: String) -> String? { sync { guard running, identity.count == 16 else { return "local identity is not set" }; guard let p = peripherals[address] else { return "unknown BLE peer" }; guard peers[address] == nil, !pendingConnections.contains(address) else { return "already connected" }; guard peers.count + pendingConnections.count < maxPeers else { return "peer limit reached" }; pendingConnections.insert(address); let generation = UUID(); connectionGenerations[address] = generation; central.connect(p); queue.asyncAfter(deadline: .now() + connectionTimeout) { [weak self, weak p] in guard let self, let p, self.pendingConnections.contains(address), self.connectionGenerations[address] == generation else { return }; self.pendingConnections.remove(address); self.connectionGenerations.removeValue(forKey: address); self.central?.cancelPeripheralConnection(p); self.emit("connect_failed", address, ["message": "connection timed out", "timed_out": true]) }; return nil } }
    private func disconnect(_ a: String) { sync {
        if a.hasPrefix("central:") {
            if pendingConnections.remove(a) != nil, let peripheral = peripherals[a] { connectionGenerations.removeValue(forKey: a); central?.cancelPeripheralConnection(peripheral) }
            if let peer = peers.removeValue(forKey: a) { if let peripheral = peer.peripheral { central?.cancelPeripheralConnection(peripheral) }; emit("disconnected", a) }
        }
        // CoreBluetooth does not let a GATT server terminate a central's link.
        // Remove its local state only; do not claim that its connection was cancelled.
        if a.hasPrefix("peripheral:") { peers.removeValue(forKey: a); subscribed.remove(a); pendingNotifications.removeAll { $0.address == a } }
    } }
    private func write(_ a: String, _ d: Data) -> String? { sync { guard let p = peers[a], p.state == "ready", let ch = p.rx, let peripheral = p.peripheral, d.count <= p.mtu else { return "BLE peer is not ready for write" }; peripheral.writeValue(d, for: ch, type: ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse); return nil } }
    private func notify(_ a: String, _ d: Data) -> String? { sync { guard let p = peers[a], p.role == "peripheral", subscribed.contains(a), d.count <= p.mtu, txCharacteristic != nil else { return "BLE peer is not subscribed or ready" }; guard pendingNotifications.count < notificationQueueLimit else { emit("error", a, ["severity": "error", "message": "BLE notification queue overflow", "limit": notificationQueueLimit]); return "BLE notification queue overflow" }; pendingNotifications.append((a, d)); flushNotifications(); return nil } }
    private func drain() -> [[String: Any]] { sync { defer { events.removeAll() }; return events } }
    private func emit(_ kind: String, _ address: String? = nil, _ extra: [String: Any] = [:]) { var e: [String: Any] = ["kind": kind]; if let address { e["address"] = address }; e.merge(extra) { _, n in n }; events.append(e) }
    private func addr(_ p: CBPeripheral) -> String { "central:" + p.identifier.uuidString }
    private func peripheralAddr(_ id: UUID) -> String { "peripheral:" + id.uuidString }

    func centralManagerDidUpdateState(_ central: CBCentralManager) { guard running, self.central === central else { return }; emit("adapter_state", nil, ["enabled": central.state == .poweredOn]); if central.state == .poweredOn { beginScan() } else if central.state != .unknown && central.state != .resetting { scanning = false; pendingConnections.removeAll(); connectionGenerations.removeAll(); peers.removeAll(); peripherals.removeAll(); subscribed.removeAll(); subscribedCentrals.removeAll(); pendingNotifications.removeAll(); emit("error", nil, ["severity": "error", "message": "Bluetooth is unavailable"]) } }
    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) { guard running, self.central === central else { return }; let a = addr(p); peripherals[a] = p; emit("discovered", a, ["name": advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? NSNull(), "rssi": rssi.intValue]) }
    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) { guard running, self.central === central else { return }; let a = addr(p); guard pendingConnections.contains(a) else { central.cancelPeripheralConnection(p); return }; pendingConnections.remove(a); connectionGenerations.removeValue(forKey: a); p.delegate = self; peers[a] = Peer(peripheral: p, mtu: p.maximumWriteValueLength(for: .withResponse)); p.discoverServices([service]) }
    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { guard running, self.central === central, peripherals[addr(p)] === p, pendingConnections.contains(addr(p)) else { return }; let a = addr(p); pendingConnections.remove(a); emit("connect_failed", a, ["message": error?.localizedDescription ?? "connection failed", "timed_out": false]) }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) { guard running, self.central === central, peers[addr(p)]?.peripheral === p else { return }; let a = addr(p); pendingConnections.remove(a); if peers.removeValue(forKey: a)?.state == "ready" { emit("disconnected", a) } else { emit("connect_failed", a, ["message": error?.localizedDescription ?? "disconnected during setup", "timed_out": false]) } }
    private func isCurrent(_ p: CBPeripheral) -> Bool { guard running, let peer = peers[addr(p)] else { return false }; return peer.peripheral === p }
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) { guard isCurrent(p) else { return }; let a = addr(p); guard error == nil, let s = p.services?.first(where: { $0.uuid == service }) else { failLink(a, error?.localizedDescription ?? "peer service missing"); return }; p.discoverCharacteristics([rx, tx, identityUUID], for: s) }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) { guard isCurrent(p) else { return }; let a = addr(p); guard error == nil, let chars = s.characteristics, let r = chars.first(where: { $0.uuid == rx }), let t = chars.first(where: { $0.uuid == tx }), let i = chars.first(where: { $0.uuid == identityUUID }), var peer = peers[a] else { failLink(a, error?.localizedDescription ?? "peer characteristics missing"); return }; peer.rx = r; peer.tx = t; peer.identity = i; peers[a] = peer; p.readValue(for: i) }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) { guard isCurrent(p) else { return }; let a = addr(p); guard error == nil, let data = c.value, var peer = peers[a] else { if let error { failLink(addr(p), error.localizedDescription) }; return }; if c.uuid == identityUUID { peer.remoteIdentity = data; peers[a] = peer; emit("identity_read", a, ["identity": data.base64EncodedString()]); if let txCharacteristic = peer.tx { p.setNotifyValue(true, for: txCharacteristic) }; emit("mtu_changed", a, ["payload_size": max(20, p.maximumWriteValueLength(for: .withResponse))]) } else if c.uuid == tx { emit("data", a, ["data": data.base64EncodedString()]) } }
    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) { guard isCurrent(p) else { return }; let a = addr(p); guard c.uuid == tx, error == nil, let peer = peers[a], let rxChar = peer.rx, !identity.isEmpty else { if let error { failLink(a, error.localizedDescription) }; return }; p.writeValue(identity, for: rxChar, type: .withResponse) }
    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) { guard isCurrent(p) else { return }; let a = addr(p); guard c.uuid == rx else { return }; if let error { failLink(a, error.localizedDescription); return }; guard let peer = peers[a] else { return }; var ready = peer; ready.state = "ready"; peers[a] = ready; emit("connected", a, ["role": "central", "identity": peer.remoteIdentity?.base64EncodedString() as Any? ?? NSNull(), "payload_size": max(20, p.maximumWriteValueLength(for: .withResponse))]) }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) { guard running, self.peripheralManager === peripheral else { return }; guard peripheral.state == .poweredOn else { if peripheral.state != .unknown && peripheral.state != .resetting { pendingNotifications.removeAll(); txCharacteristic = nil; subscribed.removeAll(); subscribedCentrals.removeAll() }; return }; if advertisingRequested { configurePeripheral(peripheral) } }
    private func configurePeripheral(_ peripheral: CBPeripheralManager) { guard advertisingRequested, identity.count == 16, txCharacteristic == nil else { return }; let identityChar = CBMutableCharacteristic(type: identityUUID, properties: .read, value: nil, permissions: .readable); let rxChar = CBMutableCharacteristic(type: rx, properties: [.write, .writeWithoutResponse], value: nil, permissions: .writeable); let txChar = CBMutableCharacteristic(type: tx, properties: [.read, .notify], value: nil, permissions: .readable); txCharacteristic = txChar; let svc = CBMutableService(type: service, primary: true); svc.characteristics = [rxChar, txChar, identityChar]; peripheral.add(svc); peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [service]]) }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) { guard running, self.peripheralManager === peripheral else { return }; guard request.characteristic.uuid == identityUUID else { peripheral.respond(to: request, withResult: .requestNotSupported); return }; guard !identity.isEmpty, request.offset <= identity.count else { peripheral.respond(to: request, withResult: .invalidOffset); return }; request.value = identity.subdata(in: request.offset..<identity.count); peripheral.respond(to: request, withResult: .success) }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard running, self.peripheralManager === peripheral else { return }
        guard requests.allSatisfy({ $0.characteristic.uuid == rx && $0.value != nil }) else {
            if let request = requests.first { peripheral.respond(to: request, withResult: .requestNotSupported) }
            return
        }
        for request in requests {
            let a = peripheralAddr(request.central.identifier)
            if peers[a] == nil {
                guard peers.count < maxPeers else { peripheral.respond(to: request, withResult: .insufficientResources); return }
                peers[a] = Peer(peripheral: nil, state: "ready", mtu: request.central.maximumUpdateValueLength, role: "peripheral")
                emit("connected", a, ["role": "peripheral", "identity": NSNull(), "payload_size": request.central.maximumUpdateValueLength])
            }
            if let data = request.value { emit("data", a, ["data": data.base64EncodedString()]) }
        }
        if let request = requests.first { peripheral.respond(to: request, withResult: .success) }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) { guard running, self.peripheralManager === peripheral, characteristic.uuid == tx else { return }; let address = peripheralAddr(central.identifier); subscribed.insert(address); subscribedCentrals[address] = central; if var peer = peers[address] { peer.mtu = central.maximumUpdateValueLength; peers[address] = peer } }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) { guard running, self.peripheralManager === peripheral, characteristic.uuid == tx else { return }; let address = peripheralAddr(central.identifier); subscribed.remove(address); subscribedCentrals.removeValue(forKey: address); let count = pendingNotifications.filter { $0.address == address }.count; pendingNotifications.removeAll { $0.address == address }; if count > 0 { emit("error", address, ["severity": "error", "message": "Queued BLE notifications discarded after unsubscribe", "count": count]) }; if peers.removeValue(forKey: address) != nil { emit("disconnected", address) } }
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { guard running, self.peripheralManager === peripheral else { return }; flushNotifications() }
    private func flushNotifications() { guard running, let peripheralManager, let txCharacteristic else { return }; while let first = pendingNotifications.first { guard subscribed.contains(first.address), let central = subscribedCentrals[first.address] else { pendingNotifications.removeFirst(); continue }; guard peripheralManager.updateValue(first.data, for: txCharacteristic, onSubscribedCentrals: [central]) else { return }; pendingNotifications.removeFirst() } }
    private func failLink(_ a: String, _ message: String) { pendingConnections.remove(a); emit("connect_failed", a, ["message": message, "timed_out": false]); if let p = peers.removeValue(forKey: a)?.peripheral { central?.cancelPeripheralConnection(p) } }
}
