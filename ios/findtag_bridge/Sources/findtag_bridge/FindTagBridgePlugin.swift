import CryptoKit
import Flutter
import Security
import UIKit

public final class FindTagBridgePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private let sdk = FTSDKAdapter()
    private let store = KeychainStore()
    private lazy var bleDiagnostics = BleDiagnosticsManager { [weak self] event in
        self?.eventSink?(event)
    }
    private var eventSink: FlutterEventSink?
    private var initialized = false
    private var operationBusy = false
    private var bindingMetadata: BindingMetadata?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FindTagBridgePlugin()
        let methods = FlutterMethodChannel(name: "findtag_bridge/methods", binaryMessenger: registrar.messenger())
        let events = FlutterEventChannel(name: "findtag_bridge/events", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methods)
        events.setStreamHandler(instance)
    }

    override init() {
        super.init()
        sdk.bluetoothEvent = { [weak self] state in
            self?.eventSink?(["type": "bluetoothState", "state": state])
        }
        sdk.scanDeviceEvent = { [weak self] device in
            var event = device
            event["type"] = "scanResult"
            self?.eventSink?(event)
        }
        sdk.scanFinishedEvent = { [weak self] reason in
            self?.eventSink?(["type": "scanFinished", "reason": reason])
        }
        sdk.scanErrorEvent = { [weak self] error in
            var event = error
            event["type"] = "operation"
            event["operation"] = "scan"
            event["status"] = "error"
            self?.eventSink?(event)
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        if initialized { sdk.observeBluetooth() }
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        sdk.stopScan()
        bleDiagnostics.stop()
        return nil
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            switch call.method {
            case "initialize": try initializeSdk(result)
            case "startScan": try startScan(call, result)
            case "stopScan": sdk.stopScan(); result(nil)
            case "bindDevice": try bindDevice(call, result)
            case "getLatestLocation": try getLocationData(call, preset: "latest", result)
            case "getLocationData": try getLocationData(
                call,
                preset: try stringArgument(call, "preset"),
                result
            )
            case "findDevice": try findDevice(call, result)
            case "exportLogs": exportLogs(result)
            case "startBleDiagnosticScan": try startBleDiagnosticScan(call, result)
            case "connectAndReadBleDiagnostics": try connectAndReadBleDiagnostics(call, result)
            case "listenBleDiagnosticNotifications": listenBleDiagnosticNotifications(result)
            case "stopBleDiagnostics": stopBleDiagnostics(result)
            case "release": releaseSdk(); result(nil)
            case "credentialStatus": result(credentialStatus())
            case "saveCredentials": try saveCredentials(call, result)
            case "clearCredentials": try clearCredentials(result)
            case "savedTags": result(try store.records().map(publicRecord))
            case "revealDeviceKey": try revealKey(call, result)
            case "renameSavedTag": try renameTag(call, result)
            case "removeSavedTag": try removeTag(call, result)
            case "requestBluetoothPermissions": result(nil)
            case "openAppSettings": openAppSettings(result)
            case "openMap": try openMap(call, result)
            default: result(FlutterMethodNotImplemented)
            }
        } catch {
            result(pluginError("localStorageError", safeMessage(error)))
        }
    }

    private func initializeSdk(_ result: @escaping FlutterResult) throws {
        if initialized { result(sdkInfo()); return }
        let apiKey = try store.get("apiKey")
        let secret = try store.get("apiSecret")
        let error = sdk.initialize(withApiKey: apiKey, apiSecret: secret) { [weak self] key, metadata, decision in
            guard let self else { decision(false); return }
            let metadata = BindingMetadata(
                name: metadata["name"] as? String,
                platformAddress: metadata["platformAddress"] as? String,
                advertisedMac: metadata["advertisedMac"] as? String
            )
            let gate = CompletionGate()
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try self.store.upsert(key: key, metadata: metadata)
                    if gate.claim() { decision(true) }
                } catch {
                    if gate.claim() { decision(false) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 25) {
                if gate.claim() { decision(false) }
            }
        }
        if let error { result(flutterError(error)); return }
        initialized = true
        if eventSink != nil { sdk.observeBluetooth() }
        result(sdkInfo())
    }

    private func sdkInfo() -> [String: Any] { [
        "sdkVersion": sdk.sdkVersion(), "mode": "customerManaged",
        "credentialsReady": credentialsReady(), "platform": "ios"
    ] }

    private func startScan(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        guard initialized else { result(pluginError("notInitialized", "FindTag SDK başlatılmadı.")); return }
        let timeout = (try arguments(call)["timeoutMs"] as? NSNumber)?.int64Value ?? 15_000
        sdk.startScan(withTimeoutMs: min(max(timeout, 1_000), 60_000))
        result(nil)
    }

    private func bindDevice(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        guard initialized else { result(pluginError("notInitialized", "FindTag SDK başlatılmadı.")); return }
        guard !operationBusy else { result(pluginError("operationInProgress", "Başka bir cihaz işlemi sürüyor.")); return }
        let args = try arguments(call)
        bindingMetadata = BindingMetadata(
            name: args["name"] as? String,
            platformAddress: args["platformAddress"] as? String,
            advertisedMac: args["advertisedMac"] as? String
        )
        operationBusy = true
        let safe = ResultGate(result) { [weak self] in self?.operationBusy = false; self?.bindingMetadata = nil }
        sdk.bindScanId(try stringArgument(call, "scanId")) { [weak self] value, error in
            guard let self else { return }
            if let error { safe.error(self.flutterError(error)); return }
            guard let key = value as? String, let record = try? self.store.recordByKey(key) else {
                safe.error(self.pluginError("localStorageError", "Bağlama kaydı bulunamadı.")); return
            }
            safe.success(self.publicRecord(record))
        }
    }

    private func findDevice(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        guard initialized else { result(pluginError("notInitialized", "FindTag SDK başlatılmadı.")); return }
        guard !operationBusy else { result(pluginError("operationInProgress", "Başka bir cihaz işlemi sürüyor.")); return }
        operationBusy = true
        let safe = ResultGate(result) { [weak self] in self?.operationBusy = false }
        sdk.findScanId(try stringArgument(call, "scanId")) { [weak self] value, error in
            guard let self else { return }
            if let error { safe.error(self.flutterError(error)); return }
            safe.success(value as? [String: Any] ?? ["triggered": false])
        }
    }

    private func getLocationData(
        _ call: FlutterMethodCall,
        preset: String,
        _ result: @escaping FlutterResult
    ) throws {
        guard initialized else { result(pluginError("notInitialized", "FindTag SDK başlatılmadı.")); return }
        guard credentialsReady() else {
            result(pluginError("openApiCredentialMissing", "Önce Test API Ayarları ekranından API bilgilerini kaydedin.")); return
        }
        guard let record = try store.record(try stringArgument(call, "savedTagId")) else {
            result(pluginError("deviceNotFound", "Kayıtlı cihaz bulunamadı.")); return
        }
        let args = try arguments(call)
        sdk.getDataForDeviceKey(
            record.deviceKey,
            preset: preset,
            startTimeMs: args["startTimeMs"] as? NSNumber,
            endTimeMs: args["endTimeMs"] as? NSNumber
        ) { [weak self] value, error in
            guard let self else { return }
            if let error { result(self.flutterError(error)); return }
            result([
                "queriedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
                "records": value as? [[String: Any]] ?? []
            ])
        }
    }

    private func exportLogs(_ result: @escaping FlutterResult) {
        guard initialized else {
            result(pluginError("notInitialized", "FindTag SDK başlatılmadı.")); return
        }
        sdk.exportLogs { [weak self] value, error in
            guard let self else { return }
            if let error { result(self.flutterError(error)); return }
            result(value)
        }
    }

    private func startBleDiagnosticScan(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        let timeout = (try arguments(call)["timeoutMs"] as? NSNumber)?.int64Value ?? 15_000
        // Release cancels the SDK scan/task and closes its connection before CoreBluetooth starts.
        bleDiagnostics.stop()
        releaseSdk()
        bleDiagnostics.startScan(timeoutMs: min(max(timeout, 1_000), 60_000), completion: bridgeCompletion(result))
    }

    private func connectAndReadBleDiagnostics(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        bleDiagnostics.connectAndInspect(
            deviceId: try stringArgument(call, "deviceId"),
            completion: bridgeCompletion(result)
        )
    }

    private func listenBleDiagnosticNotifications(_ result: @escaping FlutterResult) {
        bleDiagnostics.listenForNotifications(completion: bridgeCompletion(result))
    }

    private func stopBleDiagnostics(_ result: @escaping FlutterResult) {
        bleDiagnostics.stop()
        // Restore the existing CUSTOMER_MANAGED SDK after the independent BLE client closes.
        do { try initializeSdk(result) }
        catch { result(pluginError("localStorageError", safeMessage(error))) }
    }

    private func bridgeCompletion(_ result: @escaping FlutterResult) -> BleDiagnosticCompletion {
        let gate = ResultGate(result) {}
        return { failureValue, failure in
            DispatchQueue.main.async {
                if let failure {
                    gate.error(FlutterError(
                        code: failure.code,
                        message: failure.message,
                        details: failure.details
                    ))
                } else {
                    gate.success(failureValue)
                }
            }
        }
    }

    private func saveCredentials(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        let key = try stringArgument(call, "apiKey")
        let secret = try stringArgument(call, "apiSecret")
        guard !key.isEmpty, !secret.isEmpty else {
            result(pluginError("invalidArgument", "API key ve API secret boş bırakılamaz.")); return
        }
        try store.put("apiKey", key); try store.put("apiSecret", secret)
        try reinitialize(result)
    }

    private func clearCredentials(_ result: @escaping FlutterResult) throws {
        try store.remove("apiKey"); try store.remove("apiSecret")
        try reinitialize(result)
    }

    private func reinitialize(_ result: @escaping FlutterResult) throws {
        releaseSdk()
        try initializeSdk { value in result(value is FlutterError ? value : nil) }
    }

    private func credentialStatus() -> [String: Any] {
        ["ready": credentialsReady(), "apiKey": (try? store.get("apiKey")) ?? "123"]
    }

    private func credentialsReady() -> Bool {
        let key = (try? store.get("apiKey")) ?? nil
        let secret = (try? store.get("apiSecret")) ?? nil
        return !(key?.isEmpty ?? true) && !(secret?.isEmpty ?? true)
    }

    private func revealKey(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        guard let record = try store.record(try stringArgument(call, "savedTagId")) else {
            result(pluginError("deviceNotFound", "Kayıtlı cihaz bulunamadı.")); return
        }
        result(record.deviceKey)
    }

    private func renameTag(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        let name = try stringArgument(call, "name").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { result(pluginError("invalidArgument", "Cihaz adı boş olamaz.")); return }
        try store.rename(try stringArgument(call, "savedTagId"), name: name); result(nil)
    }

    private func removeTag(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        try store.removeRecord(try stringArgument(call, "savedTagId")); result(nil)
    }

    private func publicRecord(_ record: SavedRecord) -> [String: Any] { [
        "id": record.id, "name": record.name, "maskedKey": maskKey(record.deviceKey),
        "createdAtMs": record.createdAtMs, "platformAddress": record.platformAddress as Any,
        "advertisedMac": record.advertisedMac as Any
    ] }

    private func openAppSettings(_ result: @escaping FlutterResult) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            result(pluginError("settingsUnavailable", "Uygulama ayarları açılamadı.")); return
        }
        UIApplication.shared.open(url); result(nil)
    }

    private func openMap(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) throws {
        let args = try arguments(call)
        guard let latitude = (args["latitude"] as? NSNumber)?.doubleValue,
              let longitude = (args["longitude"] as? NSNumber)?.doubleValue,
              let url = URL(string: "http://maps.apple.com/?ll=\(latitude),\(longitude)") else {
            result(pluginError("invalidArgument", "Koordinat geçersiz.")); return
        }
        UIApplication.shared.open(url); result(nil)
    }

    private func releaseSdk() {
        if initialized { sdk.releaseSdk() }
        initialized = false; operationBusy = false; bindingMetadata = nil
    }

    private func arguments(_ call: FlutterMethodCall) throws -> [String: Any] {
        guard let value = call.arguments as? [String: Any] else { throw BridgeError.invalidArgument }
        return value
    }
    private func stringArgument(_ call: FlutterMethodCall, _ name: String) throws -> String {
        guard let value = try arguments(call)[name] as? String else { throw BridgeError.invalidArgument }
        return value
    }
    private func pluginError(_ code: String, _ message: String) -> FlutterError {
        FlutterError(code: code, message: message, details: nil)
    }
    private func flutterError(_ source: [AnyHashable: Any]) -> FlutterError {
        FlutterError(code: source["code"] as? String ?? "unknown",
                     message: source["message"] as? String,
                     details: ["serverCode": source["serverCode"]])
    }
    private func safeMessage(_ error: Error) -> String { String(describing: error).prefix(200).description }
    private func maskKey(_ key: String) -> String {
        key.count <= 8 ? "••••••••" : "\(key.prefix(4))••••\(key.suffix(4))"
    }
}

private final class CompletionGate: @unchecked Sendable {
    private let lock = NSLock(); private var completed = false
    func claim() -> Bool { lock.lock(); defer { lock.unlock() }; if completed { return false }; completed = true; return true }
}

private final class ResultGate {
    private let lock = NSLock(); private var completed = false
    private let result: FlutterResult; private let cleanup: () -> Void
    init(_ result: @escaping FlutterResult, cleanup: @escaping () -> Void) { self.result = result; self.cleanup = cleanup }
    func success(_ value: Any?) { finish(value) }
    func error(_ value: FlutterError) { finish(value) }
    private func finish(_ value: Any?) { lock.lock(); guard !completed else { lock.unlock(); return }; completed = true; lock.unlock(); cleanup(); result(value) }
}

private struct BindingMetadata { let name: String?; let platformAddress: String?; let advertisedMac: String? }
private struct SavedRecord: Codable {
    var id: String; var name: String; let deviceKey: String; let createdAtMs: Int64
    var platformAddress: String?; var advertisedMac: String?
}
private enum BridgeError: Error { case invalidArgument, corruptStorage }

private final class KeychainStore: @unchecked Sendable {
    private let service = "com.example.findapptag.findtag-bridge"
    func put(_ name: String, _ value: String) throws { try putData(name, Data(value.utf8)) }
    func get(_ name: String) throws -> String? {
        guard let data = try getData(name), let value = String(data: data, encoding: .utf8) else { return nil }
        return value
    }
    func remove(_ name: String) throws {
        let status = SecItemDelete(query(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    func records() throws -> [SavedRecord] {
        guard let data = try getData("records") else { return [] }
        return try JSONDecoder().decode([SavedRecord].self, from: data)
    }
    func record(_ id: String) throws -> SavedRecord? { try records().first { $0.id == id } }
    func recordByKey(_ key: String) throws -> SavedRecord? { try records().first { $0.deviceKey == key } }
    func upsert(key: String, metadata: BindingMetadata?) throws {
        var all = try records()
        if let index = all.firstIndex(where: { $0.deviceKey == key }) {
            if let value = metadata?.platformAddress { all[index].platformAddress = value }
            if let value = metadata?.advertisedMac { all[index].advertisedMac = value }
        } else {
            let id = CryptoKit.SHA256.hash(data: Data(key.utf8)).prefix(10).map { String(format: "%02x", $0) }.joined()
            all.append(SavedRecord(id: id, name: metadata?.name ?? "FindTag", deviceKey: key,
                                   createdAtMs: Int64(Date().timeIntervalSince1970 * 1000),
                                   platformAddress: metadata?.platformAddress, advertisedMac: metadata?.advertisedMac))
        }
        try saveRecords(all)
    }
    func rename(_ id: String, name: String) throws {
        var all = try records(); guard let index = all.firstIndex(where: { $0.id == id }) else { throw BridgeError.invalidArgument }
        all[index].name = name; try saveRecords(all)
    }
    func removeRecord(_ id: String) throws { try saveRecords(try records().filter { $0.id != id }) }
    private func saveRecords(_ records: [SavedRecord]) throws { try putData("records", JSONEncoder().encode(records)) }
    private func query(_ name: String) -> [String: Any] { [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service, kSecAttrAccount as String: name
    ] }
    private func putData(_ name: String, _ data: Data) throws {
        var item = query(name); item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(query(name) as CFDictionary,
                [kSecValueData as String: data] as CFDictionary)
            guard updateStatus == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus)) }
        } else if status != errSecSuccess { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
    private func getData(_ name: String) throws -> Data? {
        var item = query(name); item[kSecReturnData as String] = true; item[kSecMatchLimit as String] = kSecMatchLimitOne
        var output: CFTypeRef?; let status = SecItemCopyMatching(item as CFDictionary, &output)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = output as? Data else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        return data
    }
}
