import CoreBluetooth
import Foundation

struct BleDiagnosticFailure {
    let code: String
    let message: String
    let details: Any?
}

typealias BleDiagnosticCompletion = (Any?, BleDiagnosticFailure?) -> Void

/// Independent, command-free CoreBluetooth client for the explicit diagnostics UI.
final class BleDiagnosticsManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let emit: ([String: Any]) -> Void
    private lazy var central = CBCentralManager(
        delegate: self,
        queue: .main,
        options: [CBCentralManagerOptionShowPowerAlertKey: false]
    )
    private var discoveries: [UUID: CBPeripheral] = [:]
    private var scanTimeout: DispatchWorkItem?
    private var pendingScanStart: BleDiagnosticCompletion?
    private var pendingScanTimeoutMs: Int64 = 15_000
    private var operationTimeout: DispatchWorkItem?
    private var activePeripheral: CBPeripheral?
    private var inspectCompletion: BleDiagnosticCompletion?
    private var notificationCompletion: BleDiagnosticCompletion?
    private var pendingCharacteristicServices: Set<ObjectIdentifier> = []
    private var pendingDescriptorCharacteristics: Set<ObjectIdentifier> = []
    private var discoveryStarted = false
    private var servicesCallbackHandled = false
    private var inventory: [[String: Any]] = []
    private var readQueue: [ReadTarget] = []
    private var currentRead: ReadTarget?
    private var readValues: [[String: Any]] = []
    private var errors: [[String: Any]] = []
    private var notifyTargets: [CBCharacteristic] = []
    private var notifyIndex = 0
    private var notifyAction: NotifyAction?
    private var listening = false
    private var notificationValues: [[String: Any]] = []
    private var notificationErrors: [[String: Any]] = []

    init(emit: @escaping ([String: Any]) -> Void) {
        self.emit = emit
        super.init()
        _ = central
    }

    func startScan(timeoutMs: Int64, completion: @escaping BleDiagnosticCompletion) {
        stopScan(reason: "restarted")
        closePeripheral()
        discoveries.removeAll()
        guard pendingScanStart == nil else {
            fail(completion, "operationInProgress", "BLE taraması zaten başlatılıyor.")
            return
        }
        switch central.state {
        case .poweredOn:
            beginScan(timeoutMs: timeoutMs, completion: completion)
        case .unknown, .resetting:
            pendingScanStart = completion
            pendingScanTimeoutMs = timeoutMs
            scheduleOperationTimeout(label: "adapterReady", seconds: 3) { [weak self] in
                guard let self, let callback = self.pendingScanStart else { return }
                self.pendingScanStart = nil
                self.fail(callback, "bluetoothUnavailable", "Bluetooth durumu 3 saniyede hazır olmadı.")
            }
        case .unauthorized:
            fail(completion, "permissionDenied", "Bluetooth izni verilmemiş.")
        default:
            fail(completion, "bluetoothUnavailable", "Bluetooth kapalı veya BLE kullanılamıyor.")
        }
    }

    func connectAndInspect(deviceId: String, completion: @escaping BleDiagnosticCompletion) {
        stopScan(reason: "operationStarted")
        guard inspectCompletion == nil, notificationCompletion == nil else {
            fail(completion, "operationInProgress", "Başka bir BLE tanılama işlemi sürüyor.")
            return
        }
        guard let uuid = UUID(uuidString: deviceId), let peripheral = discoveries[uuid] else {
            fail(completion, "staleScanDevice", "Tanılama tarama sonucu artık geçerli değil.")
            return
        }
        closePeripheral()
        resetReport()
        inspectCompletion = completion
        activePeripheral = peripheral
        peripheral.delegate = self
        debug("connect deviceId=\(deviceId)")
        central.connect(peripheral, options: nil)
        scheduleOperationTimeout(label: "connect", seconds: 12) { [weak self] in
            self?.failInspect("bleConnectionTimeout", "BLE bağlantısı 12 saniyede kurulamadı.")
        }
    }

    func listenForNotifications(completion: @escaping BleDiagnosticCompletion) {
        guard inspectCompletion == nil, notificationCompletion == nil else {
            fail(completion, "operationInProgress", "Başka bir BLE tanılama işlemi sürüyor.")
            return
        }
        guard let peripheral = activePeripheral, peripheral.state == .connected else {
            fail(completion, "bleNotConnected", "Önce cihazı bağlayıp servisleri keşfedin.")
            return
        }
        notifyTargets = (peripheral.services ?? []).flatMap { service in
            (service.characteristics ?? []).filter {
                $0.properties.contains(.notify) || $0.properties.contains(.indicate)
            }
        }
        notificationValues.removeAll()
        notificationErrors.removeAll()
        notificationCompletion = completion
        notifyIndex = 0
        notifyAction = nil
        listening = false
        if notifyTargets.isEmpty {
            finishNotifications()
            return
        }
        debug("notification configuration starts targets=\(notifyTargets.count); CoreBluetooth manages CCCD writes")
        configureNextNotification(enable: true)
    }

    func stop(completion: BleDiagnosticCompletion? = nil) {
        stopScan(reason: "stopped")
        cancelOperationTimeout()
        if let callback = pendingScanStart {
            fail(callback, "diagnosticStopped", "BLE tanılama oturumu sonlandırıldı.")
        }
        if let callback = inspectCompletion {
            fail(callback, "diagnosticStopped", "BLE tanılama oturumu sonlandırıldı.")
        }
        if let callback = notificationCompletion {
            fail(callback, "diagnosticStopped", "BLE tanılama oturumu sonlandırıldı.")
        }
        pendingScanStart = nil
        inspectCompletion = nil
        notificationCompletion = nil
        listening = false
        notifyAction = nil
        closePeripheral()
        completion?(nil, nil)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard let callback = pendingScanStart else { return }
        if central.state == .poweredOn {
            pendingScanStart = nil
            cancelOperationTimeout()
            beginScan(timeoutMs: pendingScanTimeoutMs, completion: callback)
        } else if central.state != .unknown && central.state != .resetting {
            pendingScanStart = nil
            cancelOperationTimeout()
            let code = central.state == .unauthorized ? "permissionDenied" : "bluetoothUnavailable"
            fail(callback, code, "Bluetooth tarama için kullanılamıyor (state=\(central.state.rawValue)).")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discoveries[peripheral.identifier] = peripheral
        var manufacturers: [[String: Any]] = []
        if let data = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data {
            let bytes = [UInt8](data)
            let id = bytes.count >= 2 ? Int(bytes[0]) | (Int(bytes[1]) << 8) : nil
            manufacturers.append([
                "id": id as Any? ?? NSNull(),
                "idHex": id.map { String(format: "0x%04X", $0) } as Any? ?? NSNull(),
                "payloadHex": bytes.count >= 2 ? hex(Data(bytes.dropFirst(2))) : "",
                "rawHex": hex(data),
            ])
        }
        let serviceData = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?
            .map { ["uuid": $0.key.uuidString, "hex": hex($0.value)] } ?? []
        let serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?
            .map(\.uuidString) ?? []
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let event: [String: Any] = [
            "type": "bleDiagnosticAdvertisement",
            "timestamp": now(),
            "deviceId": peripheral.identifier.uuidString,
            "platformIdKind": "iosPeripheralUuid",
            "platformAddress": NSNull(),
            "name": localName ?? peripheral.name as Any? ?? NSNull(),
            "localName": localName as Any? ?? NSNull(),
            "rssi": RSSI.intValue,
            "txPower": advertisementData[CBAdvertisementDataTxPowerLevelKey] as Any? ?? NSNull(),
            "connectable": advertisementData[CBAdvertisementDataIsConnectable] as Any? ?? NSNull(),
            "serviceUuids": serviceUuids,
            "manufacturerData": manufacturers,
            "serviceData": serviceData,
            "rawPacketAvailable": false,
            "rawPacketHex": NSNull(),
            "rawPacketUnavailableReason": "iOS CoreBluetooth özgün ham reklam paketini uygulamaya açmıyor.",
        ]
        debug("advertisement \(event)")
        emit(event)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === activePeripheral, inspectCompletion != nil else { return }
        guard !discoveryStarted else {
            debug("duplicate connected ignored deviceId=\(peripheral.identifier.uuidString)")
            return
        }
        discoveryStarted = true
        cancelOperationTimeout()
        debug("connected deviceId=\(peripheral.identifier.uuidString)")
        peripheral.discoverServices(nil)
        scheduleOperationTimeout(label: "discoverServices", seconds: 12) { [weak self] in
            self?.failInspect("serviceDiscoveryTimeout", "Servis keşfi 12 saniyede tamamlanmadı.")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral === activePeripheral else { return }
        failInspect("bleConnectionFailed", safeError(error, fallback: "BLE bağlantısı kurulamadı."))
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral === activePeripheral else { return }
        let message = safeError(error, fallback: "BLE bağlantısı kapandı.")
        if inspectCompletion != nil { failInspect("bleDisconnected", message) }
        if notificationCompletion != nil { failNotifications("bleDisconnected", message) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === activePeripheral, inspectCompletion != nil else { return }
        guard !servicesCallbackHandled else {
            debug("duplicate services discovery callback ignored deviceId=\(peripheral.identifier.uuidString)")
            return
        }
        servicesCallbackHandled = true
        cancelOperationTimeout()
        if let error {
            failInspect("serviceDiscoveryFailed", safeError(error, fallback: "Servis keşfi başarısız oldu."))
            return
        }
        let services = peripheral.services ?? []
        if services.isEmpty {
            finishDiscoveryAndStartReads(peripheral)
            return
        }
        pendingCharacteristicServices = Set(services.map(ObjectIdentifier.init))
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
        scheduleOperationTimeout(label: "discoverCharacteristics", seconds: 12) { [weak self] in
            self?.failInspect("characteristicDiscoveryTimeout", "Characteristic keşfi 12 saniyede tamamlanmadı.")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === activePeripheral, inspectCompletion != nil else { return }
        guard pendingCharacteristicServices.remove(ObjectIdentifier(service)) != nil else {
            debug("duplicate characteristic discovery callback ignored service=\(service.uuid.uuidString)")
            return
        }
        if let error {
            errors.append(errorRecord(
                operation: "discoverCharacteristics", service: service.uuid,
                characteristic: nil, descriptor: nil, message: safeError(error)
            ))
        }
        guard pendingCharacteristicServices.isEmpty else { return }
        cancelOperationTimeout()
        let characteristics = (peripheral.services ?? []).flatMap { $0.characteristics ?? [] }
        if characteristics.isEmpty {
            finishDiscoveryAndStartReads(peripheral)
            return
        }
        pendingDescriptorCharacteristics = Set(characteristics.map(ObjectIdentifier.init))
        for characteristic in characteristics { peripheral.discoverDescriptors(for: characteristic) }
        scheduleOperationTimeout(label: "discoverDescriptors", seconds: 12) { [weak self] in
            self?.failInspect("descriptorDiscoveryTimeout", "Descriptor keşfi 12 saniyede tamamlanmadı.")
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverDescriptorsFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === activePeripheral, inspectCompletion != nil else { return }
        guard pendingDescriptorCharacteristics.remove(ObjectIdentifier(characteristic)) != nil else {
            debug("duplicate descriptor discovery callback ignored characteristic=\(characteristic.uuid.uuidString)")
            return
        }
        if let error {
            errors.append(errorRecord(
                operation: "discoverDescriptors", service: characteristic.service?.uuid,
                characteristic: characteristic.uuid, descriptor: nil, message: safeError(error)
            ))
        }
        guard pendingDescriptorCharacteristics.isEmpty else { return }
        cancelOperationTimeout()
        finishDiscoveryAndStartReads(peripheral)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === activePeripheral else { return }
        if case let .characteristic(expected)? = currentRead, expected === characteristic {
            cancelOperationTimeout()
            if let error {
                recordReadError(.characteristic(characteristic), safeError(error))
            } else {
                recordValue(
                    sourceType: "characteristic", service: characteristic.service?.uuid,
                    characteristic: characteristic.uuid, descriptor: nil,
                    value: characteristic.value ?? Data()
                )
            }
            currentRead = nil
            readNext()
        } else if listening, notificationCompletion != nil {
            recordNotification(characteristic, value: characteristic.value ?? Data())
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor descriptor: CBDescriptor,
        error: Error?
    ) {
        guard peripheral === activePeripheral else { return }
        if case let .descriptor(expected)? = currentRead, expected === descriptor {
            cancelOperationTimeout()
            if let error {
                recordReadError(.descriptor(descriptor), safeError(error))
            } else {
                recordDescriptorValue(descriptor)
            }
            currentRead = nil
            readNext()
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard peripheral === activePeripheral,
              let action = notifyAction,
              notifyIndex < notifyTargets.count,
              notifyTargets[notifyIndex] === characteristic else { return }
        cancelOperationTimeout()
        if let error {
            notificationErrors.append(errorRecord(
                operation: action == .enable ? "configureNotificationEnable" : "configureNotificationDisable",
                service: characteristic.service?.uuid, characteristic: characteristic.uuid,
                descriptor: cccdUuid, message: safeError(error)
            ))
        }
        notifyIndex += 1
        configureNextNotification(enable: action == .enable)
    }

    private func beginScan(timeoutMs: Int64, completion: @escaping BleDiagnosticCompletion) {
        cancelOperationTimeout()
        central.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        let bounded = min(max(timeoutMs, 1_000), 60_000)
        let timeout = DispatchWorkItem { [weak self] in self?.stopScan(reason: "timeout") }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(bounded)), execute: timeout)
        completion([
            "platform": "ios",
            "rawPacketAvailable": false,
            "rawPacketUnavailableReason": "iOS CoreBluetooth özgün ham reklam paketini uygulamaya açmıyor.",
        ], nil)
    }

    private func stopScan(reason: String) {
        scanTimeout?.cancel()
        scanTimeout = nil
        guard central.isScanning else { return }
        central.stopScan()
        emit(["type": "bleDiagnosticScanFinished", "reason": reason])
    }

    private func finishDiscoveryAndStartReads(_ peripheral: CBPeripheral) {
        inventory = (peripheral.services ?? []).map { service in
            [
                "serviceUuid": service.uuid.uuidString,
                "characteristics": (service.characteristics ?? []).map { characteristic in
                    [
                        "characteristicUuid": characteristic.uuid.uuidString,
                        "properties": propertyNames(characteristic),
                        "descriptorUuids": (characteristic.descriptors ?? []).map { $0.uuid.uuidString },
                    ] as [String: Any]
                },
            ] as [String: Any]
        }
        debug("inventory \(inventory)")
        readQueue = (peripheral.services ?? []).flatMap { service in
            (service.characteristics ?? [])
                .filter { $0.properties.contains(.read) }
                .map { ReadTarget.characteristic($0) }
        }
        readQueue += (peripheral.services ?? []).flatMap { service in
            (service.characteristics ?? []).flatMap { characteristic in
                (characteristic.descriptors ?? []).map { ReadTarget.descriptor($0) }
            }
        }
        readNext()
    }

    private func readNext() {
        cancelOperationTimeout()
        guard let peripheral = activePeripheral, peripheral.state == .connected else {
            failInspect("bleDisconnected", "BLE bağlantısı okuma sırasında kapandı.")
            return
        }
        guard !readQueue.isEmpty else {
            currentRead = nil
            guard let callback = inspectCompletion else { return }
            inspectCompletion = nil
            callback([
                "platform": "ios",
                "deviceId": peripheral.identifier.uuidString,
                "services": inventory,
                "values": readValues,
                "errors": errors,
                "note": "Yalnız standart READ characteristic ve descriptor okumaları yapıldı; özel komut gönderilmedi.",
            ], nil)
            return
        }
        let target = readQueue.removeFirst()
        currentRead = target
        switch target {
        case let .characteristic(characteristic): peripheral.readValue(for: characteristic)
        case let .descriptor(descriptor): peripheral.readValue(for: descriptor)
        }
        scheduleOperationTimeout(label: "read", seconds: 7) { [weak self] in
            guard let self, let timedOut = self.currentRead else { return }
            self.recordReadError(timedOut, "Okuma 7 saniyede yanıt vermedi.")
            self.currentRead = nil
            self.readNext()
        }
    }

    private func recordReadError(_ target: ReadTarget, _ message: String) {
        let record: [String: Any]
        switch target {
        case let .characteristic(characteristic):
            record = errorRecord(
                operation: "readCharacteristic", service: characteristic.service?.uuid,
                characteristic: characteristic.uuid, descriptor: nil, message: message
            )
        case let .descriptor(descriptor):
            record = errorRecord(
                operation: "readDescriptor", service: descriptor.characteristic?.service?.uuid,
                characteristic: descriptor.characteristic?.uuid, descriptor: descriptor.uuid, message: message
            )
        }
        errors.append(record)
        debug("read error \(record)")
    }

    private func recordValue(
        sourceType: String,
        service: CBUUID?,
        characteristic: CBUUID?,
        descriptor: CBUUID?,
        value: Data
    ) {
        let record = encodedValue(
            sourceType: sourceType, service: service, characteristic: characteristic,
            descriptor: descriptor, value: value
        )
        readValues.append(record)
        debug("read \(record)")
    }

    private func recordDescriptorValue(_ descriptor: CBDescriptor) {
        if let data = descriptor.value as? Data {
            recordValue(
                sourceType: "descriptor", service: descriptor.characteristic?.service?.uuid,
                characteristic: descriptor.characteristic?.uuid,
                descriptor: descriptor.uuid, value: data
            )
            return
        }
        // CoreBluetooth decodes some well-known descriptors into NSString/NSNumber
        // and does not expose their original bytes. Do not manufacture a byte array.
        let platformValue: String?
        if let text = descriptor.value as? String {
            platformValue = text
        } else if let number = descriptor.value as? NSNumber {
            platformValue = number.stringValue
        } else {
            platformValue = nil
        }
        let record: [String: Any] = [
            "timestamp": now(),
            "sourceType": "descriptor",
            "serviceUuid": descriptor.characteristic?.service?.uuid.uuidString as Any? ?? NSNull(),
            "characteristicUuid": descriptor.characteristic?.uuid.uuidString as Any? ?? NSNull(),
            "descriptorUuid": descriptor.uuid.uuidString,
            "length": NSNull(),
            "hex": NSNull(),
            "base64": NSNull(),
            "utf8": (descriptor.value is String ? platformValue : nil) as Any? ?? NSNull(),
            "rawValueAvailable": false,
            "platformValue": platformValue as Any? ?? NSNull(),
            "rawValueUnavailableReason": "CoreBluetooth bu descriptor değerini ham Data olarak sunmadı; byte üretilmedi.",
        ]
        readValues.append(record)
        debug("read \(record)")
    }

    private func configureNextNotification(enable: Bool) {
        cancelOperationTimeout()
        guard let peripheral = activePeripheral, peripheral.state == .connected else {
            failNotifications("bleDisconnected", "BLE bağlantısı kapandı.")
            return
        }
        if notifyIndex >= notifyTargets.count {
            if enable {
                listening = true
                notifyAction = nil
                emit(["type": "bleDiagnosticListenStarted", "durationSeconds": 20])
                scheduleOperationTimeout(label: "listenNotifications", seconds: 20) { [weak self] in
                    guard let self else { return }
                    self.listening = false
                    self.notifyIndex = 0
                    self.configureNextNotification(enable: false)
                }
            } else {
                finishNotifications()
            }
            return
        }
        notifyAction = enable ? .enable : .disable
        let characteristic = notifyTargets[notifyIndex]
        peripheral.setNotifyValue(enable, for: characteristic)
        scheduleOperationTimeout(label: enable ? "enableNotification" : "disableNotification", seconds: 7) { [weak self] in
            guard let self else { return }
            self.notificationErrors.append(self.errorRecord(
                operation: enable ? "configureNotificationEnable" : "configureNotificationDisable",
                service: characteristic.service?.uuid, characteristic: characteristic.uuid,
                descriptor: self.cccdUuid, message: "Bildirim yapılandırması 7 saniyede yanıt vermedi."
            ))
            self.notifyIndex += 1
            self.configureNextNotification(enable: enable)
        }
    }

    private func recordNotification(_ characteristic: CBCharacteristic, value: Data) {
        guard listening, notificationCompletion != nil else { return }
        let record = encodedValue(
            sourceType: "notification", service: characteristic.service?.uuid,
            characteristic: characteristic.uuid, descriptor: nil, value: value
        )
        notificationValues.append(record)
        debug("notification \(record)")
        emit(["type": "bleDiagnosticNotification", "value": record])
    }

    private func finishNotifications() {
        cancelOperationTimeout()
        listening = false
        notifyAction = nil
        guard let callback = notificationCompletion else { return }
        notificationCompletion = nil
        callback([
            "platform": "ios",
            "durationSeconds": notifyTargets.isEmpty ? 0 : 20,
            "configurationWritesPerformed": !notifyTargets.isEmpty,
            "configurationMethod": "CoreBluetooth setNotifyValue; platform-managed CCCD enable/disable",
            "values": notificationValues,
            "dataReceived": !notificationValues.isEmpty,
            "message": notificationValues.isEmpty ? "Veri gelmedi" : NSNull(),
            "errors": notificationErrors,
        ], nil)
    }

    private func failInspect(_ code: String, _ message: String) {
        cancelOperationTimeout()
        guard let callback = inspectCompletion else { return }
        inspectCompletion = nil
        currentRead = nil
        readQueue.removeAll()
        fail(callback, code, message, details: ["errors": errors])
        closePeripheral()
    }

    private func failNotifications(_ code: String, _ message: String) {
        cancelOperationTimeout()
        guard let callback = notificationCompletion else { return }
        notificationCompletion = nil
        listening = false
        notifyAction = nil
        fail(callback, code, message, details: ["values": notificationValues])
        closePeripheral()
    }

    private func resetReport() {
        cancelOperationTimeout()
        pendingCharacteristicServices.removeAll()
        pendingDescriptorCharacteristics.removeAll()
        discoveryStarted = false
        servicesCallbackHandled = false
        inventory.removeAll()
        readQueue.removeAll()
        currentRead = nil
        readValues.removeAll()
        errors.removeAll()
        notifyTargets.removeAll()
        notifyIndex = 0
        notifyAction = nil
        listening = false
        notificationValues.removeAll()
        notificationErrors.removeAll()
    }

    private func closePeripheral() {
        guard let peripheral = activePeripheral else { return }
        activePeripheral = nil
        peripheral.delegate = nil
        if peripheral.state != .disconnected { central.cancelPeripheralConnection(peripheral) }
    }

    private func scheduleOperationTimeout(label: String, seconds: Int, action: @escaping () -> Void) {
        cancelOperationTimeout()
        debug("timeout scheduled operation=\(label) seconds=\(seconds)")
        let item = DispatchWorkItem(block: action)
        operationTimeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(seconds), execute: item)
    }

    private func cancelOperationTimeout() {
        operationTimeout?.cancel()
        operationTimeout = nil
    }

    private func encodedValue(
        sourceType: String,
        service: CBUUID?,
        characteristic: CBUUID?,
        descriptor: CBUUID?,
        value: Data
    ) -> [String: Any] {
        [
            "timestamp": now(),
            "sourceType": sourceType,
            "serviceUuid": service?.uuidString as Any? ?? NSNull(),
            "characteristicUuid": characteristic?.uuidString as Any? ?? NSNull(),
            "descriptorUuid": descriptor?.uuidString as Any? ?? NSNull(),
            "length": value.count,
            "hex": hex(value),
            "base64": value.base64EncodedString(),
            "utf8": readableUtf8(value) as Any? ?? NSNull(),
        ]
    }

    private func errorRecord(
        operation: String,
        service: CBUUID?,
        characteristic: CBUUID?,
        descriptor: CBUUID?,
        message: String
    ) -> [String: Any] {
        [
            "timestamp": now(),
            "operation": operation,
            "serviceUuid": service?.uuidString as Any? ?? NSNull(),
            "characteristicUuid": characteristic?.uuidString as Any? ?? NSNull(),
            "descriptorUuid": descriptor?.uuidString as Any? ?? NSNull(),
            "message": message,
        ]
    }

    private func propertyNames(_ characteristic: CBCharacteristic) -> [String] {
        var values: [String] = []
        if characteristic.properties.contains(.read) { values.append("READ") }
        if characteristic.properties.contains(.write) { values.append("WRITE") }
        if characteristic.properties.contains(.writeWithoutResponse) { values.append("WRITE_WITHOUT_RESPONSE") }
        if characteristic.properties.contains(.notify) { values.append("NOTIFY") }
        if characteristic.properties.contains(.indicate) { values.append("INDICATE") }
        return values
    }

    private func readableUtf8(_ data: Data) -> String? {
        guard let value = String(data: data, encoding: .utf8) else { return nil }
        let allowedControls: Set<UInt32> = [9, 10, 13]
        guard value.unicodeScalars.allSatisfy({
            !CharacterSet.controlCharacters.contains($0) || allowedControls.contains($0.value)
        }) else { return nil }
        return value
    }

    private func fail(
        _ completion: BleDiagnosticCompletion,
        _ code: String,
        _ message: String,
        details: Any? = nil
    ) {
        completion(nil, BleDiagnosticFailure(code: code, message: message, details: details))
    }

    private func safeError(_ error: Error?, fallback: String = "İşlem başarısız oldu.") -> String {
        String((error?.localizedDescription ?? fallback).prefix(240))
    }

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02X", $0) }.joined(separator: " ")
    }

    private func now() -> String { Self.dateFormatter.string(from: Date()) }

    private func debug(_ message: String) {
        #if DEBUG
        print("BleDiagnostics: \(message)")
        #endif
    }

    private enum ReadTarget {
        case characteristic(CBCharacteristic)
        case descriptor(CBDescriptor)
    }

    private enum NotifyAction { case enable, disable }

    private let cccdUuid = CBUUID(string: CBUUIDClientCharacteristicConfigurationString)
    private static let dateFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
