package com.findapptag.findtag_bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mytag.sdk.CustomerBindingHandler
import com.mytag.sdk.FindDeviceResult
import com.mytag.sdk.TagBindingInfo
import com.mytag.sdk.TagBluetoothStateListener
import com.mytag.sdk.TagCallback
import com.mytag.sdk.TagCustomerBindingDecision
import com.mytag.sdk.TagCustomerBindingError
import com.mytag.sdk.TagDevice
import com.mytag.sdk.TagDeviceData
import com.mytag.sdk.TagDeviceDataPreset
import com.mytag.sdk.TagDeviceDataQuery
import com.mytag.sdk.TagDeviceKey
import com.mytag.sdk.TagError
import com.mytag.sdk.TagErrorCode
import com.mytag.sdk.TagIntegrationMode
import com.mytag.sdk.TagLogArchive
import com.mytag.sdk.TagOpenApiCredential
import com.mytag.sdk.TagScanFinishReason
import com.mytag.sdk.TagScanListener
import com.mytag.sdk.TagScanOptions
import com.mytag.sdk.TagSdk
import com.mytag.sdk.TagSdkConfig
import com.mytag.sdk.TagSubscription
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FindTagBridgePlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var context: Context
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var store: SecureStore
    private lateinit var bleDiagnostics: BleDiagnosticsManager
    private lateinit var legacyBinding: LegacyFindTagBindingManager
    private var activity: Activity? = null
    private var eventSink: EventChannel.EventSink? = null
    private var scanSubscription: TagSubscription? = null
    private var bluetoothSubscription: TagSubscription? = null
    private val scannedDevices = linkedMapOf<String, TagDevice>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var operationBusy = false
    private var bindingDevice: TagDevice? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        store = SecureStore(context)
        bleDiagnostics = BleDiagnosticsManager(context) { event ->
            main.post { eventSink?.success(event) }
        }
        legacyBinding = LegacyFindTagBindingManager(context)
        methods = MethodChannel(binding.binaryMessenger, "findtag_bridge/methods")
        events = EventChannel(binding.binaryMessenger, "findtag_bridge/events")
        methods.setMethodCallHandler(this)
        events.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        bleDiagnostics.stop()
        releaseSdk()
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        worker.shutdownNow()
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        eventSink = sink
        if (initialized) observeBluetooth()
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        stopScanInternal()
        bleDiagnostics.stop()
        bluetoothSubscription?.cancel()
        bluetoothSubscription = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "initialize" -> initializeSdk(result)
                "startScan" -> startScan(call.argument<Number>("timeoutMs")?.toLong() ?: 15_000L, result)
                "stopScan" -> { TagSdk.stopScan(); result.success(null) }
                "bindDevice" -> bindDevice(requiredString(call, "scanId"), result)
                "getLatestLocation" -> getLocationData(
                    requiredString(call, "savedTagId"), TagDeviceDataPreset.LATEST, result,
                )
                "getLocationData" -> getLocationData(
                    requiredString(call, "savedTagId"),
                    locationPreset(requiredString(call, "preset")),
                    result,
                    call.argument<Number>("startTimeMs")?.toLong(),
                    call.argument<Number>("endTimeMs")?.toLong(),
                )
                "findDevice" -> findDevice(requiredString(call, "scanId"), result)
                "exportLogs" -> exportLogs(result)
                "startBleDiagnosticScan" -> startBleDiagnosticScan(
                    call.argument<Number>("timeoutMs")?.toLong() ?: 15_000L,
                    result,
                )
                "connectAndReadBleDiagnostics" -> bleDiagnostics.connectAndInspect(
                    requiredString(call, "deviceId"), BleCompletion(result),
                )
                "listenBleDiagnosticNotifications" -> bleDiagnostics.listenForNotifications(
                    BleCompletion(result),
                )
                "stopBleDiagnostics" -> stopBleDiagnostics(result)
                "release" -> { releaseSdk(); result.success(null) }
                "credentialStatus" -> result.success(credentialStatus())
                "saveCredentials" -> saveCredentials(call, result)
                "clearCredentials" -> clearCredentials(result)
                "savedTags" -> result.success(store.records().map(::publicRecord))
                "revealDeviceKey" -> revealKey(requiredString(call, "savedTagId"), result)
                "renameSavedTag" -> renameTag(call, result)
                "removeSavedTag" -> removeTag(requiredString(call, "savedTagId"), result)
                "requestBluetoothPermissions" -> requestPermissions(result)
                "openAppSettings" -> openAppSettings(result)
                "openMap" -> openMap(call, result)
                else -> result.notImplemented()
            }
        } catch (error: Throwable) {
            result.error("localStorageError", safeMessage(error), null)
        }
    }

    private fun initializeSdk(result: MethodChannel.Result) {
        if (initialized) {
            result.success(sdkInfo())
            return
        }
        val apiKey = store.get("apiKey")
        val secret = store.get("apiSecret")
        val credential = if (!apiKey.isNullOrEmpty() && !secret.isNullOrEmpty()) {
            TagOpenApiCredential(apiKey, secret)
        } else null
        val bindingHandler = CustomerBindingHandler { request, completion ->
            val completed = AtomicBoolean(false)
            val timeout = Runnable {
                if (completed.compareAndSet(false, true)) {
                    completion.complete(TagCustomerBindingDecision.rejected(
                        TagCustomerBindingError("Yerel kayıt zaman aşımına uğradı", null)))
                }
            }
            main.postDelayed(timeout, 25_000L)
            worker.execute {
                try {
                    val key = request.bindingInfo.primaryDeviceKey.deviceKey
                    val device = bindingDevice
                    store.upsertRecord(key, device)
                    main.post {
                        if (completed.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout)
                            completion.complete(TagCustomerBindingDecision.approved())
                        }
                    }
                } catch (error: Throwable) {
                    main.post {
                        if (completed.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout)
                            completion.complete(TagCustomerBindingDecision.rejected(
                                TagCustomerBindingError("Cihaz bu telefona kaydedilemedi", error)))
                        }
                    }
                }
            }
        }
        val error = TagSdk.initialize(
            context,
            TagSdkConfig(TagIntegrationMode.CUSTOMER_MANAGED, credential, 30_000L, bindingHandler, true)
        )
        if (error != null) {
            sdkError(result, error)
            return
        }
        initialized = true
        observeBluetooth()
        result.success(sdkInfo())
    }

    private fun sdkInfo() = mapOf(
        "sdkVersion" to TagSdk.getSdkVersion(),
        "mode" to "customerManaged",
        "credentialsReady" to credentialsReady(),
        "platform" to "android"
    )

    private fun observeBluetooth() {
        if (bluetoothSubscription != null || eventSink == null) return
        bluetoothSubscription = TagSdk.observeBluetoothState(TagBluetoothStateListener { state ->
            eventSink?.success(mapOf("type" to "bluetoothState", "state" to state.wireValue))
        })
    }

    private fun startScan(timeoutMs: Long, result: MethodChannel.Result) {
        requireInitialized(result) ?: return
        if (!hasBluetoothPermissions()) {
            result.error("permissionDenied", "Bluetooth izni verilmemiş.", null)
            return
        }
        stopScanInternal()
        scannedDevices.clear()
        scanSubscription = TagSdk.startScan(TagScanOptions(timeoutMs.coerceIn(1_000L, 60_000L)),
            object : TagScanListener {
                override fun onDeviceFound(device: TagDevice) {
                    scannedDevices[device.id] = device
                    eventSink?.success(mapOf(
                        "type" to "scanResult", "id" to device.id, "name" to device.name,
                        "rssi" to device.rssi, "platformAddress" to device.platformAddress,
                        "advertisedMac" to device.advertisedMac
                    ))
                }

                override fun onScanFinished(reason: TagScanFinishReason) {
                    scanSubscription = null
                    eventSink?.success(mapOf("type" to "scanFinished", "reason" to reason.wireValue))
                }

                override fun onScanFailed(error: TagError) {
                    scanSubscription = null
                    emitError("scan", error)
                }
            })
        result.success(null)
    }

    private fun stopScanInternal() {
        scanSubscription?.cancel()
        scanSubscription = null
    }

    private fun bindDevice(scanId: String, result: MethodChannel.Result) {
        requireInitialized(result) ?: return
        if (operationBusy) return result.error("operationInProgress", "Başka bir cihaz işlemi sürüyor.", null)
        val device = scannedDevices[scanId]
            ?: return result.error("staleScanDevice", "Tarama sonucu artık geçerli değil; yeniden tarayın.", null)
        operationBusy = true
        bindingDevice = device
        val safe = SafeResult(result) { operationBusy = false; bindingDevice = null }
        TagSdk.connectAndBindDevice(device, object : TagCallback<TagBindingInfo> {
            override fun onSuccess(data: TagBindingInfo) {
                try {
                    val record = store.recordByKey(data.primaryDeviceKey.deviceKey)
                        ?: return safe.error("localStorageError", "Bağlama kaydı bulunamadı.")
                    safe.success(publicRecord(record))
                } catch (error: Throwable) {
                    safe.error("localStorageError", safeMessage(error))
                }
            }
            override fun onFailure(error: TagError) {
                if (error.code == TagErrorCode.BINDING_UNSUPPORTED) {
                    bindLegacyDevice(device, safe)
                } else {
                    safe.sdkError(error)
                }
            }
        })
    }

    private fun bindLegacyDevice(device: TagDevice, safe: SafeResult) {
        val address = device.platformAddress
        if (address.isNullOrBlank()) {
            safe.error("legacyAddressMissing", "Eski cihaz bağlaması için Android Bluetooth adresi bulunamadı.")
            return
        }
        legacyBinding.bind(
            address,
            persist = { info ->
                try {
                    store.upsertRecord(info.primaryDeviceKey, device)
                    null
                } catch (error: Throwable) {
                    safeMessage(error)
                }
            },
            completion = object : LegacyFindTagBindingManager.Completion {
                override fun success(info: LegacyFindTagBindingManager.BindingInfo) {
                    val record = try {
                        store.recordByKey(info.primaryDeviceKey)
                    } catch (error: Throwable) {
                        safe.error("localStorageError", safeMessage(error))
                        return
                    }
                    if (record == null) {
                        safe.error("localStorageError", "Eski cihaz bağlama kaydı bulunamadı.")
                    } else {
                        safe.success(publicRecord(record))
                    }
                }

                override fun failure(code: String, message: String) {
                    safe.error(code, message)
                }
            },
        )
    }

    private fun findDevice(scanId: String, result: MethodChannel.Result) {
        requireInitialized(result) ?: return
        if (operationBusy) return result.error("operationInProgress", "Başka bir cihaz işlemi sürüyor.", null)
        val device = scannedDevices[scanId]
            ?: return result.error("staleScanDevice", "Cihazı sesle bulmak için yeniden tarayın.", null)
        operationBusy = true
        val safe = SafeResult(result) { operationBusy = false }
        TagSdk.findDevice(device, object : TagCallback<FindDeviceResult> {
            override fun onSuccess(data: FindDeviceResult) = safe.success(mapOf(
                "triggered" to data.triggered,
                "rawBattery" to data.battery?.rawBattery,
                "serverBatteryLevel" to data.battery?.serverLevel,
            ))
            override fun onFailure(error: TagError) = safe.sdkError(error)
        })
    }

    private fun locationPreset(value: String): TagDeviceDataPreset = when (value) {
        "latest" -> TagDeviceDataPreset.LATEST
        "last1Hour" -> TagDeviceDataPreset.LAST_1_HOUR
        "last2Hours" -> TagDeviceDataPreset.LAST_2_HOURS
        "last4Hours" -> TagDeviceDataPreset.LAST_4_HOURS
        "last6Hours" -> TagDeviceDataPreset.LAST_6_HOURS
        "custom" -> TagDeviceDataPreset.CUSTOM
        else -> throw IllegalArgumentException("Desteklenmeyen konum aralığı: $value")
    }

    private fun getLocationData(
        savedTagId: String,
        preset: TagDeviceDataPreset,
        result: MethodChannel.Result,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
    ) {
        requireInitialized(result) ?: return
        // SDK kuralı: özel aralıkta iki uç da zorunlu ve başlangıç bitişten sonra
        // olamaz; hazır aralıklar uç taşımaz.
        val custom = preset == TagDeviceDataPreset.CUSTOM
        if (custom && (startTimeMs == null || endTimeMs == null || startTimeMs > endTimeMs)) return result.error(
            "invalidArgument", "Özel aralık için geçerli başlangıç ve bitiş zamanı gerekir.", null)
        if (!credentialsReady()) return result.error(
            "openApiCredentialMissing", "Önce Test API Ayarları ekranından API bilgilerini kaydedin.", null)
        val record = store.record(savedTagId)
            ?: return result.error("deviceNotFound", "Kayıtlı cihaz bulunamadı.", null)
        TagSdk.getDeviceData(
            TagDeviceDataQuery(
                TagDeviceKey(record.getString("deviceKey")),
                preset,
                if (custom) startTimeMs else null,
                if (custom) endTimeMs else null,
            ),
            object : TagCallback<List<TagDeviceData>> {
                override fun onSuccess(data: List<TagDeviceData>) {
                    result.success(mapOf(
                        "queriedAtMs" to System.currentTimeMillis(),
                        "records" to data.map { item -> mapOf(
                            "collectionTimeMs" to item.collectionTimeMs,
                            "longitude" to item.longitude, "latitude" to item.latitude,
                            "batteryLevel" to item.batteryLevel,
                            "accuracyLevel" to item.accuracyLevel,
                            "googleLocation" to item.googleLocation,
                        ) }
                    ))
                }
                override fun onFailure(error: TagError) = sdkError(result, error)
            })
    }

    private fun exportLogs(result: MethodChannel.Result) {
        requireInitialized(result) ?: return
        TagSdk.exportLogs(object : TagCallback<TagLogArchive> {
            override fun onSuccess(data: TagLogArchive) = result.success(mapOf(
                "filePath" to data.filePath,
                "fileSizeBytes" to data.fileSizeBytes,
                "createdAtMs" to data.createdAtMs,
            ))
            override fun onFailure(error: TagError) = sdkError(result, error)
        })
    }

    private fun startBleDiagnosticScan(timeoutMs: Long, result: MethodChannel.Result) {
        if (!hasBluetoothPermissions()) {
            result.error("permissionDenied", "Bluetooth izni verilmemiş.", null)
            return
        }
        // TagSdk.release() synchronously cancels its queued task/scan and closes its GATT.
        // Only after that do we create a diagnostic scan, so two BLE clients cannot overlap.
        bleDiagnostics.stop()
        releaseSdk()
        bleDiagnostics.startScan(timeoutMs, BleCompletion(result))
    }

    private fun stopBleDiagnostics(result: MethodChannel.Result) {
        bleDiagnostics.stop()
        // Restore the normal application mode after the independent BLE client is closed.
        initializeSdk(result)
    }

    private fun saveCredentials(call: MethodCall, result: MethodChannel.Result) {
        val apiKey = requiredString(call, "apiKey")
        val secret = requiredString(call, "apiSecret")
        if (apiKey.isBlank() || secret.isBlank()) return result.error(
            "invalidArgument", "API key ve API secret boş bırakılamaz.", null)
        store.put("apiKey", apiKey)
        store.put("apiSecret", secret)
        reinitialize(result)
    }

    private fun clearCredentials(result: MethodChannel.Result) {
        store.remove("apiKey")
        store.remove("apiSecret")
        reinitialize(result)
    }

    private fun reinitialize(result: MethodChannel.Result) {
        releaseSdk()
        initializeSdk(object : MethodChannel.Result {
            override fun success(value: Any?) = result.success(null)
            override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
            override fun notImplemented() = result.notImplemented()
        })
    }

    private fun credentialStatus() = mapOf(
        "ready" to credentialsReady(),
        "apiKey" to (store.get("apiKey") ?: "123")
    )

    private fun credentialsReady() = !store.get("apiKey").isNullOrEmpty() && !store.get("apiSecret").isNullOrEmpty()

    private fun revealKey(id: String, result: MethodChannel.Result) {
        val record = store.record(id) ?: return result.error("deviceNotFound", "Kayıtlı cihaz bulunamadı.", null)
        result.success(record.getString("deviceKey"))
    }

    private fun renameTag(call: MethodCall, result: MethodChannel.Result) {
        val name = requiredString(call, "name").trim()
        if (name.isEmpty()) return result.error("invalidArgument", "Cihaz adı boş olamaz.", null)
        store.rename(requiredString(call, "savedTagId"), name)
        result.success(null)
    }

    private fun removeTag(id: String, result: MethodChannel.Result) {
        store.removeRecord(id)
        result.success(null)
    }

    private fun publicRecord(record: JSONObject): Map<String, Any?> {
        val key = record.getString("deviceKey")
        return mapOf(
            "id" to record.getString("id"), "name" to record.getString("name"),
            "maskedKey" to maskKey(key), "createdAtMs" to record.getLong("createdAtMs"),
            "platformAddress" to record.optString("platformAddress").ifEmpty { null },
            "advertisedMac" to record.optString("advertisedMac").ifEmpty { null }
        )
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        val currentActivity = activity ?: return result.error("activityUnavailable", "Uygulama ekranı hazır değil.", null)
        val missing = requiredPermissions().filter {
            currentActivity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return result.success(null)
        if (pendingPermissionResult != null) return result.error("operationInProgress", "İzin isteği zaten açık.", null)
        pendingPermissionResult = result
        currentActivity.requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        val pending = pendingPermissionResult ?: return true
        pendingPermissionResult = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pending.success(null)
        } else pending.error("permissionDenied", "Bluetooth izni reddedildi.", null)
        return true
    }

    private fun hasBluetoothPermissions() = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun openAppSettings(result: MethodChannel.Result) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        result.success(null)
    }

    private fun openMap(call: MethodCall, result: MethodChannel.Result) {
        val latitude = call.argument<Number>("latitude")?.toDouble()
            ?: return result.error("invalidArgument", "Enlem eksik.", null)
        val longitude = call.argument<Number>("longitude")?.toDouble()
            ?: return result.error("invalidArgument", "Boylam eksik.", null)
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent); result.success(null) }
        catch (_: Throwable) { result.error("mapUnavailable", "Harita uygulaması bulunamadı.", null) }
    }

    private fun releaseSdk() {
        legacyBinding.stop()
        stopScanInternal()
        bluetoothSubscription?.cancel()
        bluetoothSubscription = null
        scannedDevices.clear()
        if (initialized) TagSdk.release()
        initialized = false
        operationBusy = false
        bindingDevice = null
    }

    private fun requireInitialized(result: MethodChannel.Result): Unit? {
        if (!initialized) {
            result.error("notInitialized", "FindTag SDK başlatılmadı.", null)
            return null
        }
        return Unit
    }

    private fun requiredString(call: MethodCall, name: String): String =
        call.argument<String>(name) ?: throw IllegalArgumentException("$name eksik")

    private fun sdkError(result: MethodChannel.Result, error: TagError) =
        result.error(error.code, error.message, mapOf("serverCode" to error.serverCode))

    private fun emitError(operation: String, error: TagError) = eventSink?.success(mapOf(
        "type" to "operation", "operation" to operation, "status" to "error",
        "code" to error.code, "message" to error.message, "serverCode" to error.serverCode
    ))

    private fun safeMessage(error: Throwable) = error.message?.take(200) ?: "Yerel işlem başarısız oldu."

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }
    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivity() { activity = null }

    private inner class SafeResult(
        private val delegate: MethodChannel.Result,
        private val onComplete: () -> Unit
    ) {
        private val completed = AtomicBoolean(false)
        fun success(value: Any?) { if (completed.compareAndSet(false, true)) { onComplete(); delegate.success(value) } }
        fun error(code: String, message: String) { if (completed.compareAndSet(false, true)) { onComplete(); delegate.error(code, message, null) } }
        fun sdkError(error: TagError) { if (completed.compareAndSet(false, true)) { onComplete(); this@FindTagBridgePlugin.sdkError(delegate, error) } }
    }

    private inner class BleCompletion(
        private val delegate: MethodChannel.Result,
    ) : BleDiagnosticsManager.Completion {
        private val completed = AtomicBoolean(false)

        override fun success(value: Any?) {
            if (completed.compareAndSet(false, true)) main.post { delegate.success(value) }
        }

        override fun failure(code: String, message: String, details: Any?) {
            if (completed.compareAndSet(false, true)) main.post { delegate.error(code, message, details) }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 7301
        fun maskKey(key: String): String = if (key.length <= 8) "••••••••" else "${key.take(4)}••••${key.takeLast(4)}"
    }
}

private class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("findtag_secure", Context.MODE_PRIVATE)
    private val alias = "findtag_bridge_aes"

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val combined = cipher.iv + encrypted
        check(preferences.edit().putString(name, Base64.encodeToString(combined, Base64.NO_WRAP)).commit()) {
            "Güvenli kayıt diske yazılamadı"
        }
    }

    fun get(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 12) { "Güvenli kayıt bozuk" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), StandardCharsets.UTF_8)
    }

    fun remove(name: String) { preferences.edit().remove(name).apply() }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun records(): List<JSONObject> {
        val array = JSONArray(get("records") ?: "[]")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun record(id: String): JSONObject? = records().firstOrNull { it.getString("id") == id }
    fun recordByKey(key: String): JSONObject? = records().firstOrNull { it.getString("deviceKey") == key }

    @Synchronized
    fun upsertRecord(key: String, device: TagDevice?) {
        val all = records().toMutableList()
        val id = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8)).take(10).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        val existing = all.indexOfFirst { it.getString("deviceKey") == key }
        val record = if (existing >= 0) all[existing] else JSONObject().apply {
            put("id", id); put("deviceKey", key); put("createdAtMs", System.currentTimeMillis())
        }
        if (!record.has("name")) record.put("name", device?.name ?: "FindTag")
        device?.platformAddress?.let { record.put("platformAddress", it) }
        device?.advertisedMac?.let { record.put("advertisedMac", it) }
        if (existing < 0) all.add(record)
        saveRecords(all)
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val all = records().toMutableList()
        val record = all.firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Kayıtlı cihaz bulunamadı")
        record.put("name", name)
        saveRecords(all)
    }

    @Synchronized
    fun removeRecord(id: String) = saveRecords(records().filterNot { it.getString("id") == id })

    private fun saveRecords(records: List<JSONObject>) {
        val array = JSONArray()
        records.forEach(array::put)
        put("records", array.toString())
    }
}
