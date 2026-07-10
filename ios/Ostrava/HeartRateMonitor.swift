import CoreBluetooth
import Foundation

/// Connects to a standard Bluetooth LE heart-rate strap (GATT Heart Rate service).
final class HeartRateMonitor: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    static let shared = HeartRateMonitor()

    enum ConnectionState { case idle, scanning, connecting, connected }

    struct FoundDevice: Identifiable {
        var id: UUID
        var name: String
    }

    @Published var state: ConnectionState = .idle
    @Published var bpm: Int?
    @Published var deviceName: String?
    @Published var foundDevices: [FoundDevice] = []

    private let hrService = CBUUID(string: "180D")
    private let hrMeasurement = CBUUID(string: "2A37")

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var discovered: [UUID: CBPeripheral] = [:]

    func startScan() {
        // Don't clobber an existing or in-progress connection.
        guard state == .idle || state == .scanning else { return }
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        }
        foundDevices = []
        discovered = [:]
        guard central?.state == .poweredOn else {
            // Scan begins in centralManagerDidUpdateState once powered on.
            state = .scanning
            return
        }
        state = .scanning
        central?.scanForPeripherals(withServices: [hrService])
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            self?.stopScan()
        }
    }

    func stopScan() {
        central?.stopScan()
        if state == .scanning { state = .idle }
    }

    func connect(id: UUID) {
        guard let target = discovered[id] else { return }
        stopScan()
        state = .connecting
        deviceName = target.name ?? "Sensor"
        peripheral = target
        target.delegate = self
        central?.connect(target)
    }

    func disconnect() {
        if let peripheral {
            central?.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        bpm = nil
        deviceName = nil
        if state != .scanning { state = .idle }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && state == .scanning {
            // A scan requested before the radio was ready starts here instead.
            central.scanForPeripherals(withServices: [hrService])
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                self?.stopScan()
            }
        } else if central.state != .poweredOn {
            state = .idle
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discovered[peripheral.identifier] = peripheral
        if !foundDevices.contains(where: { $0.id == peripheral.identifier }) {
            foundDevices.append(
                FoundDevice(id: peripheral.identifier, name: peripheral.name ?? "Unknown sensor")
            )
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([hrService])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        disconnect()
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == hrService }) else {
            disconnect()
            return
        }
        peripheral.discoverCharacteristics([hrMeasurement], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == hrMeasurement })
        else {
            disconnect()
            return
        }
        peripheral.setNotifyValue(true, for: characteristic)
        state = .connected
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == hrMeasurement, let data = characteristic.value, !data.isEmpty
        else { return }
        // Flags bit 0: 0 = uint8 bpm at offset 1, 1 = uint16 little-endian.
        let uint16Format = data[0] & 0x01 != 0
        if uint16Format, data.count >= 3 {
            bpm = Int(data[1]) | (Int(data[2]) << 8)
        } else if !uint16Format, data.count >= 2 {
            bpm = Int(data[1])
        }
    }
}
