package com.findapptag.findtag_bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * A deliberately command-free BLE client used only by the explicit diagnostics UI.
 * It performs discovery, standard GATT reads and user-initiated CCCD configuration.
 */
@SuppressLint("MissingPermission")
internal class BleDiagnosticsManager(
    context: Context,
    private val emit: (Map<String, Any?>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val debugLogs = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val discoveries = linkedMapOf<String, BluetoothDevice>()
    private var scannerCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var connectedId: String? = null
    private var connected = false
    private var activeSession = 0L
    private var discoveryStarted = false
    private var discoveryCompleted = false
    private var servicesInventory: List<Map<String, Any?>> = emptyList()
    private var inspectCompletion: Completion? = null
    private var notificationCompletion: Completion? = null
    private var operationTimeout: Runnable? = null
    private val readQueue = ArrayDeque<ReadTarget>()
    private var currentRead: ReadTarget? = null
    private val readValues = mutableListOf<Map<String, Any?>>()
    private val errors = mutableListOf<Map<String, Any?>>()
    private val notificationValues = mutableListOf<Map<String, Any?>>()
    private val notificationErrors = mutableListOf<Map<String, Any?>>()
    private var notifyTargets = emptyList<BluetoothGattCharacteristic>()
    private var notifyIndex = 0
    private var cccdAction: CccdAction? = null
    private var listening = false

    fun startScan(timeoutMs: Long, completion: Completion) {
        stopScan("restarted")
        closeGatt()
        discoveries.clear()
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            completion.failure("bluetoothUnavailable", "Bluetooth kapalı veya BLE kullanılamıyor.")
            return
        }
        val scanner = currentAdapter.bluetoothLeScanner
        if (scanner == null) {
            completion.failure("bluetoothUnavailable", "BLE tarayıcı kullanılamıyor.")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { publishAdvertisement(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                main.post { results.forEach(::publishAdvertisement) }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    stopScan("failed")
                    emit(mapOf(
                        "type" to "bleDiagnosticScanFinished",
                        "reason" to "failed",
                        "error" to "Android scan error $errorCode",
                    ))
                }
            }
        }
        scannerCallback = callback
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (error: Throwable) {
            scannerCallback = null
            completion.failure("bleScanFailed", safeError(error))
            return
        }
        val timeout = Runnable { stopScan("timeout") }
        scanTimeout = timeout
        main.postDelayed(timeout, timeoutMs.coerceIn(1_000L, 60_000L))
        completion.success(mapOf("platform" to "android", "rawPacketAvailable" to true))
    }

    fun connectAndInspect(deviceId: String, completion: Completion) {
        stopScan("operationStarted")
        if (inspectCompletion != null || notificationCompletion != null) {
            completion.failure("operationInProgress", "Başka bir BLE tanılama işlemi sürüyor.")
            return
        }
        val device = discoveries[deviceId]
        if (device == null) {
            completion.failure("staleScanDevice", "Tanılama tarama sonucu artık geçerli değil.")
            return
        }
        closeGatt()
        resetReport()
        inspectCompletion = completion
        connectedId = deviceId
        val session = activeSession
        val callback = createGattCallback(session)
        debug("connect deviceId=$deviceId")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, callback)
            }
            scheduleOperationTimeout("connect", 12_000L) {
                failInspect("bleConnectionTimeout", "BLE bağlantısı 12 saniyede kurulamadı.")
            }
        } catch (error: Throwable) {
            failInspect("bleConnectionFailed", safeError(error))
        }
    }

    fun listenForNotifications(completion: Completion) {
        if (inspectCompletion != null || notificationCompletion != null) {
            completion.failure("operationInProgress", "Başka bir BLE tanılama işlemi sürüyor.")
            return
        }
        val activeGatt = gatt
        if (activeGatt == null || connectedId == null || !connected) {
            completion.failure("bleNotConnected", "Önce cihazı bağlayıp servisleri keşfedin.")
            return
        }
        notifyTargets = activeGatt.services.flatMap { service ->
            service.characteristics.filter {
                hasProperty(it, BluetoothGattCharacteristic.PROPERTY_NOTIFY) ||
                    hasProperty(it, BluetoothGattCharacteristic.PROPERTY_INDICATE)
            }
        }
        notificationValues.clear()
        notificationErrors.clear()
        notificationCompletion = completion
        notifyIndex = 0
        listening = false
        if (notifyTargets.isEmpty()) {
            finishNotifications()
            return
        }
        debug("notification configuration starts targets=${notifyTargets.size}; CCCD writes are required")
        enableNextNotification()
    }

    fun stop(completion: Completion? = null) {
        stopScan("stopped")
        cancelOperationTimeout()
        inspectCompletion?.failure("diagnosticStopped", "BLE tanılama oturumu sonlandırıldı.")
        notificationCompletion?.failure("diagnosticStopped", "BLE tanılama oturumu sonlandırıldı.")
        inspectCompletion = null
        notificationCompletion = null
        listening = false
        closeGatt()
        completion?.success(null)
    }

    private fun publishAdvertisement(result: ScanResult) {
        val record = result.scanRecord
        discoveries[result.device.address] = result.device
        val manufacturers = mutableListOf<Map<String, Any?>>()
        record?.manufacturerSpecificData?.let { values ->
            for (index in 0 until values.size()) {
                val id = values.keyAt(index)
                val payload = values.valueAt(index) ?: byteArrayOf()
                val raw = byteArrayOf((id and 0xff).toByte(), ((id shr 8) and 0xff).toByte()) + payload
                manufacturers += mapOf(
                    "id" to id,
                    "idHex" to "0x%04X".format(id),
                    "payloadHex" to hex(payload),
                    "rawHex" to hex(raw),
                )
            }
        }
        val serviceData = record?.serviceData?.map { (uuid, value) ->
            mapOf("uuid" to uuid.uuid.toString(), "hex" to hex(value ?: byteArrayOf()))
        } ?: emptyList()
        val txPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }
        val event = mapOf(
            "type" to "bleDiagnosticAdvertisement",
            "timestamp" to now(),
            "deviceId" to result.device.address,
            "platformIdKind" to "androidBluetoothAddress",
            "platformAddress" to result.device.address,
            "name" to (record?.deviceName ?: runCatching { result.device.name }.getOrNull()),
            "localName" to record?.deviceName,
            "rssi" to result.rssi,
            "txPower" to txPower,
            "connectable" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null,
            "serviceUuids" to (record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()),
            "manufacturerData" to manufacturers,
            "serviceData" to serviceData,
            "rawPacketAvailable" to (record != null),
            "rawPacketHex" to record?.bytes?.let(::hex),
            "rawPacketUnavailableReason" to if (record == null) "Android ScanRecord sağlanmadı." else null,
        )
        debug("advertisement $event")
        emit(event)
    }

    private fun stopScan(reason: String) {
        scanTimeout?.let(main::removeCallbacks)
        scanTimeout = null
        val callback = scannerCallback ?: return
        scannerCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        emit(mapOf("type" to "bleDiagnosticScanFinished", "reason" to reason))
    }

    private fun createGattCallback(session: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (isActive(session, activeGatt)) {
                    handleConnectionState(session, activeGatt, status, newState)
                } else {
                    debug("stale callback ignored session=$session event=connectionState")
                }
            }
        }

        override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
            main.post {
                if (isActive(session, activeGatt)) {
                    handleServicesDiscovered(session, activeGatt, status)
                } else {
                    debug("stale callback ignored session=$session event=servicesDiscovered")
                }
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (isActive(session, activeGatt)) {
                    handleCharacteristicRead(characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }
        }

        override fun onCharacteristicRead(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            main.post {
                if (isActive(session, activeGatt)) handleCharacteristicRead(characteristic, value, status)
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onDescriptorRead(
            activeGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            main.post {
                if (isActive(session, activeGatt)) {
                    handleDescriptorRead(descriptor, descriptor.value ?: byteArrayOf(), status)
                }
            }
        }

        override fun onDescriptorRead(
            activeGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            main.post {
                if (isActive(session, activeGatt)) handleDescriptorRead(descriptor, value, status)
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            main.post {
                if (isActive(session, activeGatt)) {
                    recordNotification(characteristic, characteristic.value ?: byteArrayOf())
                }
            }
        }

        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            main.post {
                if (isActive(session, activeGatt)) recordNotification(characteristic, value)
            }
        }

        override fun onDescriptorWrite(
            activeGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            main.post {
                if (isActive(session, activeGatt)) handleCccdWrite(descriptor, status)
            }
        }
    }

    private fun handleConnectionState(
        session: Long,
        activeGatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            connected = true
            if (inspectCompletion == null || discoveryStarted) {
                debug("duplicate connected ignored session=$session discoveryStarted=$discoveryStarted")
                return
            }
            discoveryStarted = true
            debug("connected session=$session deviceId=$connectedId")
            cancelOperationTimeout()
            val started = runCatching { activeGatt.discoverServices() }.getOrDefault(false)
            if (!started) {
                failInspect("serviceDiscoveryFailed", "Servis keşfi başlatılamadı.")
            } else {
                scheduleOperationTimeout("discoverServices", 12_000L) {
                    failInspect("serviceDiscoveryTimeout", "Servis keşfi 12 saniyede tamamlanmadı.")
                }
            }
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            connected = false
            val message = "BLE bağlantısı kapandı (GATT status=$status)."
            if (inspectCompletion != null) failInspect("bleDisconnected", message)
            if (notificationCompletion != null) failNotifications("bleDisconnected", message)
        }
    }

    private fun handleServicesDiscovered(session: Long, activeGatt: BluetoothGatt, status: Int) {
        if (inspectCompletion == null || discoveryCompleted) {
            debug("duplicate servicesDiscovered ignored session=$session completed=$discoveryCompleted")
            return
        }
        discoveryCompleted = true
        cancelOperationTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failInspect("serviceDiscoveryFailed", "Servis keşfi GATT status=$status ile başarısız oldu.")
            return
        }
        servicesInventory = activeGatt.services.map { service ->
            mapOf(
                "serviceUuid" to service.uuid.toString(),
                "characteristics" to service.characteristics.map { characteristic ->
                    mapOf(
                        "characteristicUuid" to characteristic.uuid.toString(),
                        "properties" to propertyNames(characteristic),
                        "descriptorUuids" to characteristic.descriptors.map { it.uuid.toString() },
                    )
                },
            )
        }
        debug("inventory $servicesInventory")
        activeGatt.services.forEach { service ->
            service.characteristics.filter { hasProperty(it, BluetoothGattCharacteristic.PROPERTY_READ) }
                .forEach { readQueue.add(ReadTarget.Characteristic(service.uuid, it)) }
        }
        activeGatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                characteristic.descriptors.forEach {
                    readQueue.add(ReadTarget.Descriptor(service.uuid, characteristic.uuid, it))
                }
            }
        }
        readNext()
    }

    private fun readNext() {
        cancelOperationTimeout()
        val activeGatt = gatt
        if (activeGatt == null) {
            failInspect("bleDisconnected", "BLE bağlantısı okuma sırasında kapandı.")
            return
        }
        val target = readQueue.pollFirst()
        if (target == null) {
            currentRead = null
            val completion = inspectCompletion ?: return
            inspectCompletion = null
            completion.success(mapOf(
                "platform" to "android",
                "deviceId" to connectedId,
                "services" to servicesInventory,
                "values" to readValues.toList(),
                "errors" to errors.toList(),
                "note" to "Yalnız standart READ characteristic ve descriptor okumaları yapıldı; özel komut gönderilmedi.",
            ))
            return
        }
        currentRead = target
        val started = when (target) {
            is ReadTarget.Characteristic -> activeGatt.readCharacteristic(target.value)
            is ReadTarget.Descriptor -> activeGatt.readDescriptor(target.value)
        }
        if (!started) {
            recordReadError(target, "İşletim sistemi GATT okumasını başlatmayı reddetti.")
            currentRead = null
            readNext()
            return
        }
        scheduleOperationTimeout("read", 7_000L) {
            val timedOut = currentRead ?: return@scheduleOperationTimeout
            recordReadError(timedOut, "Okuma 7 saniyede yanıt vermedi.")
            currentRead = null
            readNext()
        }
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        val target = currentRead as? ReadTarget.Characteristic ?: return
        if (target.value !== characteristic) return
        cancelOperationTimeout()
        if (status == BluetoothGatt.GATT_SUCCESS) {
            recordValue("characteristic", target.serviceUuid, characteristic.uuid, null, value)
        } else {
            recordReadError(target, "Characteristic okunamadı (GATT status=$status).")
        }
        currentRead = null
        readNext()
    }

    private fun handleDescriptorRead(descriptor: BluetoothGattDescriptor, value: ByteArray, status: Int) {
        val target = currentRead as? ReadTarget.Descriptor ?: return
        if (target.value !== descriptor) return
        cancelOperationTimeout()
        if (status == BluetoothGatt.GATT_SUCCESS) {
            recordValue("descriptor", target.serviceUuid, target.characteristicUuid, descriptor.uuid, value)
        } else {
            recordReadError(target, "Descriptor okunamadı (GATT status=$status).")
        }
        currentRead = null
        readNext()
    }

    private fun recordValue(
        sourceType: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID?,
        value: ByteArray,
    ) {
        val result = encodedValue(sourceType, serviceUuid, characteristicUuid, descriptorUuid, value)
        readValues += result
        debug("read $result")
    }

    private fun recordReadError(target: ReadTarget, message: String) {
        val result = when (target) {
            is ReadTarget.Characteristic -> errorRecord(
                "readCharacteristic", target.serviceUuid, target.value.uuid, null, message,
            )
            is ReadTarget.Descriptor -> errorRecord(
                "readDescriptor", target.serviceUuid, target.characteristicUuid, target.value.uuid, message,
            )
        }
        errors += result
        debug("read error $result")
    }

    private fun enableNextNotification() {
        cancelOperationTimeout()
        val activeGatt = gatt ?: return failNotifications("bleDisconnected", "BLE bağlantısı kapandı.")
        if (notifyIndex >= notifyTargets.size) {
            listening = true
            emit(mapOf("type" to "bleDiagnosticListenStarted", "durationSeconds" to 20))
            operationTimeout = Runnable {
                listening = false
                notifyIndex = 0
                disableNextNotification()
            }.also { main.postDelayed(it, 20_000L) }
            return
        }
        val characteristic = notifyTargets[notifyIndex]
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            notificationErrors += errorRecord(
                "enableNotification", characteristic.service.uuid, characteristic.uuid, null,
                "CCCD bulunamadığı için bildirim aboneliği kurulamadı.",
            )
            notifyIndex++
            enableNextNotification()
            return
        }
        if (!activeGatt.setCharacteristicNotification(characteristic, true)) {
            notificationErrors += errorRecord(
                "enableNotification", characteristic.service.uuid, characteristic.uuid, cccd.uuid,
                "Yerel bildirim kaydı reddedildi.",
            )
            notifyIndex++
            enableNextNotification()
            return
        }
        val value = if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY)) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        cccdAction = CccdAction.ENABLE
        if (!writeDescriptor(activeGatt, cccd, value)) {
            cccdAction = null
            notificationErrors += errorRecord(
                "writeCccdEnable", characteristic.service.uuid, characteristic.uuid, cccd.uuid,
                "CCCD etkinleştirme yazması başlatılamadı.",
            )
            notifyIndex++
            enableNextNotification()
            return
        }
        scheduleOperationTimeout("enableNotification", 7_000L) {
            cccdAction = null
            notificationErrors += errorRecord(
                "writeCccdEnable", characteristic.service.uuid, characteristic.uuid, cccd.uuid,
                "CCCD etkinleştirme yazması zaman aşımına uğradı.",
            )
            notifyIndex++
            enableNextNotification()
        }
    }

    private fun disableNextNotification() {
        cancelOperationTimeout()
        val activeGatt = gatt
        if (activeGatt == null || notifyIndex >= notifyTargets.size) {
            finishNotifications()
            return
        }
        val characteristic = notifyTargets[notifyIndex]
        activeGatt.setCharacteristicNotification(characteristic, false)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            notifyIndex++
            disableNextNotification()
            return
        }
        cccdAction = CccdAction.DISABLE
        if (!writeDescriptor(activeGatt, cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
            cccdAction = null
            notificationErrors += errorRecord(
                "writeCccdDisable", characteristic.service.uuid, characteristic.uuid, cccd.uuid,
                "CCCD temizleme yazması başlatılamadı.",
            )
            notifyIndex++
            disableNextNotification()
            return
        }
        scheduleOperationTimeout("disableNotification", 7_000L) {
            cccdAction = null
            notificationErrors += errorRecord(
                "writeCccdDisable", characteristic.service.uuid, characteristic.uuid, cccd.uuid,
                "CCCD temizleme yazması zaman aşımına uğradı.",
            )
            notifyIndex++
            disableNextNotification()
        }
    }

    private fun handleCccdWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        val action = cccdAction ?: return
        if (descriptor.uuid != CCCD_UUID) return
        cancelOperationTimeout()
        cccdAction = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            notificationErrors += errorRecord(
                if (action == CccdAction.ENABLE) "writeCccdEnable" else "writeCccdDisable",
                descriptor.characteristic.service.uuid,
                descriptor.characteristic.uuid,
                descriptor.uuid,
                "CCCD yazması başarısız oldu (GATT status=$status).",
            )
        }
        notifyIndex++
        if (action == CccdAction.ENABLE) enableNextNotification() else disableNextNotification()
    }

    private fun recordNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (!listening || notificationCompletion == null) return
        val result = encodedValue(
            "notification", characteristic.service.uuid, characteristic.uuid, null, value,
        )
        notificationValues += result
        debug("notification $result")
        emit(mapOf("type" to "bleDiagnosticNotification", "value" to result))
    }

    private fun finishNotifications() {
        cancelOperationTimeout()
        listening = false
        cccdAction = null
        val completion = notificationCompletion ?: return
        notificationCompletion = null
        completion.success(mapOf(
            "platform" to "android",
            "durationSeconds" to if (notifyTargets.isEmpty()) 0 else 20,
            "configurationWritesPerformed" to notifyTargets.isNotEmpty(),
            "configurationMethod" to "Android CCCD enable/disable descriptor writes",
            "values" to notificationValues.toList(),
            "dataReceived" to notificationValues.isNotEmpty(),
            "message" to if (notificationValues.isEmpty()) "Veri gelmedi" else null,
            "errors" to notificationErrors.toList(),
        ))
    }

    private fun failInspect(code: String, message: String) {
        cancelOperationTimeout()
        val completion = inspectCompletion ?: return
        inspectCompletion = null
        currentRead = null
        readQueue.clear()
        completion.failure(code, message, mapOf("errors" to errors.toList()))
        closeGatt()
    }

    private fun failNotifications(code: String, message: String) {
        cancelOperationTimeout()
        val completion = notificationCompletion ?: return
        notificationCompletion = null
        listening = false
        cccdAction = null
        completion.failure(code, message, mapOf("values" to notificationValues.toList()))
        closeGatt()
    }

    private fun resetReport() {
        cancelOperationTimeout()
        servicesInventory = emptyList()
        readQueue.clear()
        currentRead = null
        readValues.clear()
        errors.clear()
        notificationValues.clear()
        notificationErrors.clear()
        notifyTargets = emptyList()
        notifyIndex = 0
        cccdAction = null
        listening = false
        discoveryStarted = false
        discoveryCompleted = false
    }

    private fun closeGatt() {
        // Invalidate queued callbacks before disconnect/close can dispatch them.
        activeSession++
        val activeGatt = gatt
        gatt = null
        connectedId = null
        connected = false
        if (activeGatt != null) {
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
    }

    private fun isActive(session: Long, callbackGatt: BluetoothGatt): Boolean =
        session == activeSession && callbackGatt === gatt

    private fun scheduleOperationTimeout(label: String, delayMs: Long, action: () -> Unit) {
        cancelOperationTimeout()
        debug("timeout scheduled operation=$label delayMs=$delayMs")
        operationTimeout = Runnable(action).also { main.postDelayed(it, delayMs) }
    }

    private fun cancelOperationTimeout() {
        operationTimeout?.let(main::removeCallbacks)
        operationTimeout = null
    }

    private fun writeDescriptor(
        activeGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        activeGatt.writeDescriptor(descriptor)
    }

    private fun propertyNames(characteristic: BluetoothGattCharacteristic): List<String> = buildList {
        if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)) add("READ")
        if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE)) add("WRITE")
        if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
            add("WRITE_WITHOUT_RESPONSE")
        }
        if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY)) add("NOTIFY")
        if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE)) add("INDICATE")
    }

    private fun hasProperty(characteristic: BluetoothGattCharacteristic, property: Int) =
        characteristic.properties and property != 0

    private fun encodedValue(
        sourceType: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        descriptorUuid: UUID?,
        value: ByteArray,
    ): Map<String, Any?> = mapOf(
        "timestamp" to now(),
        "sourceType" to sourceType,
        "serviceUuid" to serviceUuid.toString(),
        "characteristicUuid" to characteristicUuid.toString(),
        "descriptorUuid" to descriptorUuid?.toString(),
        "length" to value.size,
        "hex" to hex(value),
        "base64" to Base64.encodeToString(value, Base64.NO_WRAP),
        "utf8" to readableUtf8(value),
    )

    private fun errorRecord(
        operation: String,
        serviceUuid: UUID?,
        characteristicUuid: UUID?,
        descriptorUuid: UUID?,
        message: String,
    ): Map<String, Any?> = mapOf(
        "timestamp" to now(),
        "operation" to operation,
        "serviceUuid" to serviceUuid?.toString(),
        "characteristicUuid" to characteristicUuid?.toString(),
        "descriptorUuid" to descriptorUuid?.toString(),
        "message" to message,
    )

    private fun readableUtf8(value: ByteArray): String? {
        if (value.isEmpty()) return ""
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString()
        }.getOrNull() ?: return null
        return text.takeIf { candidate ->
            candidate.codePoints().allMatch { code ->
                !Character.isISOControl(code) || code == '\n'.code || code == '\r'.code || code == '\t'.code
            }
        }
    }

    private fun hex(value: ByteArray) = value.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
    private fun now() = Instant.now().toString()
    private fun safeError(error: Throwable) = error.message?.take(240) ?: error.javaClass.simpleName
    private fun debug(message: String) { if (debugLogs) Log.d(LOG_TAG, message) }

    internal interface Completion {
        fun success(value: Any?)
        fun failure(code: String, message: String, details: Any? = null)
    }

    private sealed interface ReadTarget {
        val serviceUuid: UUID

        data class Characteristic(
            override val serviceUuid: UUID,
            val value: BluetoothGattCharacteristic,
        ) : ReadTarget

        data class Descriptor(
            override val serviceUuid: UUID,
            val characteristicUuid: UUID,
            val value: BluetoothGattDescriptor,
        ) : ReadTarget
    }

    private enum class CccdAction { ENABLE, DISABLE }

    companion object {
        private const val LOG_TAG = "BleDiagnostics"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
