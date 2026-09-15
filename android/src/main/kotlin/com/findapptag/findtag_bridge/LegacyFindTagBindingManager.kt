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
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Narrow legacy binding adapter for devices that answer the login command without
 * the V2-binding capability bit. It implements only the command sequence observed
 * in the licensed FindTag application; it is not a general-purpose BLE writer.
 */
@SuppressLint("MissingPermission")
internal class LegacyFindTagBindingManager(context: Context) {
    data class BindingInfo(
        val primaryDeviceKey: String,
        val keyCount: Int,
        val capabilities: Int,
    )

    interface Completion {
        fun success(info: BindingInfo)
        fun failure(code: String, message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val debugLogs = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var session = 0L
    private var completion: Completion? = null
    private var persist: ((BindingInfo) -> String?)? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null
    private var connected = false
    private var discoveryStarted = false
    private var servicesHandled = false
    private var notificationsReady = false
    private var operationTimeout: Runnable? = null
    private var settleRunnable: Runnable? = null
    private var expectedCommand: Int? = null
    private var responseHandler: ((ByteArray) -> Unit)? = null
    private val receiveBuffer = mutableListOf<Byte>()

    fun bind(
        platformAddress: String,
        persist: (BindingInfo) -> String?,
        completion: Completion,
    ) = onMain {
        if (this.completion != null) {
            completion.failure("operationInProgress", "Eski cihaz bağlama işlemi zaten sürüyor.")
            return@onMain
        }
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            completion.failure("bluetoothUnavailable", "Bluetooth kapalı veya BLE kullanılamıyor.")
            return@onMain
        }
        if (!BluetoothAdapter.checkBluetoothAddress(platformAddress)) {
            completion.failure("invalidArgument", "Android Bluetooth adresi geçersiz.")
            return@onMain
        }

        closeGatt()
        resetState()
        this.completion = completion
        this.persist = persist
        val activeSession = session
        debug("legacy fallback scheduled address=$platformAddress session=$activeSession")

        // The public SDK closes its GATT before reporting bindingUnsupported.
        // Give Android's Bluetooth stack a short, bounded interval to settle.
        settleRunnable = Runnable {
            settleRunnable = null
            if (activeSession != session || this.completion == null) return@Runnable
            val device = runCatching { currentAdapter.getRemoteDevice(platformAddress) }.getOrElse {
                fail("legacyConnectionFailed", safeError(it))
                return@Runnable
            }
            connect(device, activeSession)
        }.also { main.postDelayed(it, SDK_GATT_SETTLE_MS) }
    }

    fun stop() = onMain {
        if (completion != null) {
            fail("legacyBindingCancelled", "Eski cihaz bağlama işlemi iptal edildi.")
        } else {
            closeGatt()
            resetState()
        }
    }

    private fun connect(device: BluetoothDevice, activeSession: Long) {
        debug("legacy connect starts session=$activeSession")
        val callback = createGattCallback(activeSession)
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, callback)
            }
            if (gatt == null) {
                fail("legacyConnectionFailed", "Eski cihaz için GATT oturumu oluşturulamadı.")
                return
            }
            scheduleTimeout("connect", CONNECTION_TIMEOUT_MS) {
                fail("legacyConnectionTimeout", "Eski cihaz bağlantısı zaman aşımına uğradı.")
            }
        } catch (error: SecurityException) {
            fail("permissionDenied", "Bluetooth bağlantı izni verilmemiş.")
        } catch (error: Throwable) {
            fail("legacyConnectionFailed", safeError(error))
        }
    }

    private fun createGattCallback(activeSession: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (!isActive(activeSession, activeGatt)) return@post
                handleConnectionState(activeGatt, status, newState)
            }
        }

        override fun onMtuChanged(activeGatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (!isActive(activeSession, activeGatt)) return@post
                debug("legacy mtu callback mtu=$mtu status=$status")
                startServiceDiscovery(activeGatt)
            }
        }

        override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
            main.post {
                if (!isActive(activeSession, activeGatt)) return@post
                handleServicesDiscovered(activeGatt, status)
            }
        }

        override fun onDescriptorWrite(
            activeGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            main.post {
                if (!isActive(activeSession, activeGatt) || descriptor !== cccd) return@post
                if (notificationsReady) {
                    debug("duplicate legacy notification descriptor callback ignored")
                    return@post
                }
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("legacyNotifyEnableFailed", "Yanıt bildirimleri açılamadı (GATT status=$status).")
                    return@post
                }
                notificationsReady = true
                debug("legacy notifications ready")
                scheduleLoginAfterSettle()
            }
        }

        override fun onCharacteristicWrite(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (!isActive(activeSession, activeGatt) || characteristic !== writeCharacteristic) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // The official AAR deliberately ignores this callback for its
                    // WRITE_TYPE_NO_RESPONSE command path. Delivery is decided by
                    // the validated protocol response (or its bounded timeout).
                    debug("legacy write callback status=$status; awaiting protocol response")
                }
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            main.post {
                if (isActive(activeSession, activeGatt) && characteristic === notifyCharacteristic) {
                    @Suppress("DEPRECATION")
                    consumeNotification(characteristic.value ?: byteArrayOf())
                }
            }
        }

        override fun onCharacteristicChanged(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            main.post {
                if (isActive(activeSession, activeGatt) && characteristic === notifyCharacteristic) {
                    consumeNotification(value)
                }
            }
        }
    }

    private fun handleConnectionState(activeGatt: BluetoothGatt, status: Int, newState: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            if (connected) {
                debug("duplicate legacy connected callback ignored")
                return
            }
            connected = true
            cancelTimeout()
            debug("legacy connected")
            val requested = runCatching { activeGatt.requestMtu(200) }.getOrDefault(false)
            if (requested) {
                scheduleTimeout("mtu", MTU_TIMEOUT_MS) { startServiceDiscovery(activeGatt) }
            } else {
                startServiceDiscovery(activeGatt)
            }
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            fail("legacyDisconnected", "Eski cihaz GATT bağlantısı kapandı (status=$status).")
        }
    }

    private fun startServiceDiscovery(activeGatt: BluetoothGatt) {
        if (discoveryStarted) {
            debug("duplicate legacy service discovery request ignored")
            return
        }
        discoveryStarted = true
        cancelTimeout()
        val started = runCatching { activeGatt.discoverServices() }.getOrDefault(false)
        if (!started) {
            fail("legacyServiceDiscoveryFailed", "Eski cihaz servis keşfi başlatılamadı.")
            return
        }
        scheduleTimeout("discoverServices", DISCOVERY_TIMEOUT_MS) {
            fail("legacyServiceDiscoveryTimeout", "Eski cihaz servis keşfi zaman aşımına uğradı.")
        }
    }

    private fun handleServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
        if (servicesHandled) {
            debug("duplicate legacy services callback ignored")
            return
        }
        servicesHandled = true
        cancelTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("legacyServiceDiscoveryFailed", "Servis keşfi başarısız oldu (GATT status=$status).")
            return
        }
        val service = activeGatt.getService(SERVICE_UUID)
        val writer = service?.getCharacteristic(WRITE_UUID)
        val notifier = service?.getCharacteristic(NOTIFY_UUID)
        if (service == null || writer == null || notifier == null) {
            fail("legacyServiceMissing", "FindTag komut servisi veya characteristic'leri bulunamadı.")
            return
        }
        val canWrite = hasProperty(writer, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        if (!canWrite || !hasProperty(notifier, BluetoothGattCharacteristic.PROPERTY_NOTIFY)) {
            fail(
                "legacyCharacteristicUnsupported",
                "FindTag WRITE_WITHOUT_RESPONSE/bildirim özellikleri desteklenmiyor.",
            )
            return
        }
        val notificationDescriptor = notifier.getDescriptor(CCCD_UUID)
        if (notificationDescriptor == null) {
            fail("legacyNotifyEnableFailed", "FindTag bildirim descriptor'ı bulunamadı.")
            return
        }
        writeCharacteristic = writer
        notifyCharacteristic = notifier
        cccd = notificationDescriptor
        if (!activeGatt.setCharacteristicNotification(notifier, true)) {
            fail("legacyNotifyEnableFailed", "FindTag bildirim kaydı başlatılamadı.")
            return
        }
        if (!writeDescriptor(activeGatt, notificationDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            fail("legacyNotifyEnableFailed", "FindTag bildirim descriptor yazması başlatılamadı.")
            return
        }
        scheduleTimeout("enableNotifications", GATT_OPERATION_TIMEOUT_MS) {
            fail("legacyNotifyEnableTimeout", "FindTag bildirimleri açılamadan zaman aşımı oluştu.")
        }
    }

    private fun scheduleLoginAfterSettle() {
        val activeSession = session
        settleRunnable?.let(main::removeCallbacks)
        settleRunnable = Runnable {
            settleRunnable = null
            if (activeSession != session || completion == null) return@Runnable
            sendCommand(COMMAND_LOGIN, byteArrayOf(0x55, 0x55), RESPONSE_LOGIN) { payload ->
                handleLoginResponse(payload)
            }
        }.also { main.postDelayed(it, PROTOCOL_SETTLE_MS) }
    }

    private fun handleLoginResponse(payload: ByteArray) {
        if (payload.isEmpty()) {
            fail("frameFormatError", "FindTag login yanıt payload'ı eksik.")
            return
        }
        val raw = payload[0].toInt() and 0xff
        val loginAccepted = raw and CAPABILITY_LOGIN != 0
        val supportsV2Binding = raw and CAPABILITY_V2_BINDING != 0
        debug(
            "legacy login response capabilities=0x%02X loginAccepted=$loginAccepted supportsV2=$supportsV2Binding"
                .format(raw),
        )
        if (!loginAccepted) {
            fail("deviceRejected", "Eski cihaz login isteğini kabul etmedi.")
            return
        }
        if (supportsV2Binding) {
            fail("legacyFallbackNotApplicable", "Cihaz V2 bağlamayı destekliyor; legacy fallback uygulanmadı.")
            return
        }
        sendCommand(COMMAND_READ_KEYS, byteArrayOf(), RESPONSE_READ_KEYS) { keysPayload ->
            handleKeyResponse(raw, keysPayload)
        }
    }

    private fun handleKeyResponse(capabilities: Int, payload: ByteArray) {
        if (payload.isEmpty()) {
            fail("keyMissing", "Eski cihaz anahtar yanıtı boş.")
            return
        }
        if (payload.size % KEY_RECORD_SIZE != 0) {
            fail(
                "frameFormatError",
                "Eski cihaz anahtar payload'ı 28 baytlık kayıtlara hizalı değil (${payload.size} bayt).",
            )
            return
        }
        val keys = payload.asList().chunked(KEY_RECORD_SIZE).map { values ->
            val record = values.toByteArray()
            val keyBytes = if (record.copyOfRange(0, 8).all { (it.toInt() and 0xff) == 0xff }) {
                record.copyOfRange(8, KEY_RECORD_SIZE)
            } else {
                record
            }
            keyHex(keyBytes)
        }
        val info = BindingInfo(keys.first(), keys.size, capabilities)
        val keyFormats = keys.joinToString(",") { "${it.length / 2}-byte" }
        debug("legacy keys parsed count=${info.keyCount} formats=$keyFormats; values redacted")
        val persistence = persist
        if (persistence == null) {
            fail("localStorageError", "Eski cihaz bağlama kayıt işlemi yapılandırılmamış.")
            return
        }
        val persistenceError = try {
            persistence(info)
        } catch (error: Throwable) {
            safeError(error)
        }
        if (persistenceError != null) {
            fail("localStorageError", persistenceError)
            return
        }
        sendBindingResult(info)
    }

    private fun sendBindingResult(info: BindingInfo) {
        val activeGatt = gatt
        val writer = writeCharacteristic
        if (activeGatt == null || writer == null || !notificationsReady) {
            fail("legacyDisconnected", "FindTag bind-result komutu için aktif GATT oturumu yok.")
            return
        }
        if (expectedCommand != null) {
            fail("responseMismatch", "Başka bir FindTag komutu hâlâ yanıt bekliyor.")
            return
        }
        val frame = buildFrame(COMMAND_BIND_RESULT, byteArrayOf())
        debug("legacy tx command=0x04 expected=none frame=${hex(frame)}")
        val started = writeCharacteristic(
            activeGatt,
            writer,
            frame,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
        if (!started) {
            fail("legacyWriteFailed", "FindTag bind-result yazması başlatılamadı.")
            return
        }

        // V1_0828 treats command 0x04 as fire-and-forget and reports success
        // after its 15 ms write queue delay plus a 2 s device-settle delay.
        val activeSession = session
        settleRunnable?.let(main::removeCallbacks)
        settleRunnable = Runnable {
            settleRunnable = null
            if (activeSession != session || completion == null) return@Runnable
            debug("legacy binding result sent settleMs=$BIND_RESULT_SETTLE_MS")
            succeed(info)
        }.also { main.postDelayed(it, BIND_RESULT_SETTLE_MS) }
    }

    private fun sendCommand(
        command: Int,
        payload: ByteArray,
        expected: Int,
        onResponse: (ByteArray) -> Unit,
    ) {
        val activeGatt = gatt
        val writer = writeCharacteristic
        if (activeGatt == null || writer == null || !notificationsReady) {
            fail("legacyDisconnected", "FindTag komutu için aktif GATT oturumu yok.")
            return
        }
        if (expectedCommand != null) {
            fail("responseMismatch", "Başka bir FindTag komutu hâlâ yanıt bekliyor.")
            return
        }
        val frame = buildFrame(command, payload)
        expectedCommand = expected
        responseHandler = onResponse
        debug(
            "legacy tx command=0x%02X expected=0x%02X frame=%s"
                .format(command, expected, hex(frame)),
        )
        // The public V1_0828 AAR uses WRITE_TYPE_NO_RESPONSE unconditionally for
        // this command characteristic, even when PROPERTY_WRITE is also exposed.
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = writeCharacteristic(activeGatt, writer, frame, writeType)
        if (!started) {
            expectedCommand = null
            responseHandler = null
            fail("legacyWriteFailed", "FindTag komut yazması başlatılamadı.")
            return
        }
        scheduleTimeout("response-0x%02X".format(expected), RESPONSE_TIMEOUT_MS) {
            expectedCommand = null
            responseHandler = null
            fail("commandTimeout", "FindTag 0x%02X yanıtı zaman aşımına uğradı.".format(expected))
        }
    }

    private fun consumeNotification(value: ByteArray) {
        if (value.isEmpty()) return
        value.forEach { receiveBuffer.add(it) }
        while (true) {
            val syncIndex = receiveBuffer.indexOfFirst { (it.toInt() and 0xff) == FRAME_START }
            if (syncIndex < 0) {
                receiveBuffer.clear()
                return
            }
            repeat(syncIndex) { receiveBuffer.removeAt(0) }
            if (receiveBuffer.size < FRAME_HEADER_SIZE) return
            val payloadLength = ((receiveBuffer[2].toInt() and 0xff) shl 8) or
                (receiveBuffer[3].toInt() and 0xff)
            if (payloadLength > MAX_PAYLOAD_SIZE) {
                fail("frameFormatError", "FindTag yanıt payload'ı protokol sınırını aşıyor.")
                return
            }
            val frameLength = payloadLength + FRAME_OVERHEAD
            if (receiveBuffer.size < frameLength) return
            val frame = ByteArray(frameLength) { receiveBuffer[it] }
            repeat(frameLength) { receiveBuffer.removeAt(0) }
            val checksum = frame.dropLast(1).fold(0) { sum, byte ->
                (sum + (byte.toInt() and 0xff)) and 0xff
            }
            if (checksum != (frame.last().toInt() and 0xff)) {
                fail("checksumError", "FindTag yanıt checksum doğrulaması başarısız.")
                return
            }
            val command = frame[1].toInt() and 0xff
            val expected = expectedCommand
            debug(
                if (command == RESPONSE_READ_KEYS) {
                    "legacy rx command=0x83 payloadBytes=$payloadLength; key bytes redacted"
                } else {
                    "legacy rx command=0x%02X frame=%s".format(command, hex(frame))
                },
            )
            if (expected == null) {
                debug("unsolicited legacy response ignored command=0x%02X".format(command))
                continue
            }
            if (command != expected) {
                fail(
                    "responseMismatch",
                    "Beklenen FindTag yanıtı 0x%02X, gelen 0x%02X.".format(expected, command),
                )
                return
            }
            val handler = responseHandler
            expectedCommand = null
            responseHandler = null
            cancelTimeout()
            handler?.invoke(frame.copyOfRange(FRAME_HEADER_SIZE, FRAME_HEADER_SIZE + payloadLength))
            if (completion == null) return
        }
    }

    private fun succeed(info: BindingInfo) {
        val callback = completion ?: return
        completion = null
        persist = null
        cancelTimeout()
        closeGatt()
        resetState()
        callback.success(info)
    }

    private fun fail(code: String, message: String) {
        val callback = completion ?: return
        debug("legacy bind failed code=$code message=$message")
        completion = null
        persist = null
        cancelTimeout()
        closeGatt()
        resetState()
        callback.failure(code, message)
    }

    private fun resetState() {
        cancelTimeout()
        settleRunnable?.let(main::removeCallbacks)
        settleRunnable = null
        writeCharacteristic = null
        notifyCharacteristic = null
        cccd = null
        connected = false
        discoveryStarted = false
        servicesHandled = false
        notificationsReady = false
        expectedCommand = null
        responseHandler = null
        receiveBuffer.clear()
    }

    private fun closeGatt() {
        session++
        val activeGatt = gatt
        gatt = null
        if (activeGatt != null) {
            runCatching { activeGatt.disconnect() }
            runCatching { activeGatt.close() }
        }
    }

    private fun isActive(activeSession: Long, activeGatt: BluetoothGatt): Boolean =
        activeSession == session && activeGatt === gatt && completion != null

    private fun scheduleTimeout(label: String, delayMs: Long, action: () -> Unit) {
        cancelTimeout()
        debug("legacy timeout scheduled operation=$label delayMs=$delayMs")
        operationTimeout = Runnable(action).also { main.postDelayed(it, delayMs) }
    }

    private fun cancelTimeout() {
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

    private fun writeCharacteristic(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activeGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        characteristic.writeType = writeType
        @Suppress("DEPRECATION")
        characteristic.value = value
        @Suppress("DEPRECATION")
        activeGatt.writeCharacteristic(characteristic)
    }

    private fun buildFrame(command: Int, payload: ByteArray): ByteArray {
        require(command in 1..0x7f) { "FindTag request command out of range" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "FindTag payload exceeds protocol limit" }
        val frame = ByteArray(payload.size + FRAME_OVERHEAD)
        frame[0] = FRAME_START.toByte()
        frame[1] = command.toByte()
        frame[2] = ((payload.size ushr 8) and 0xff).toByte()
        frame[3] = (payload.size and 0xff).toByte()
        payload.copyInto(frame, FRAME_HEADER_SIZE)
        frame[frame.lastIndex] = frame.dropLast(1).fold(0) { sum, byte ->
            (sum + (byte.toInt() and 0xff)) and 0xff
        }.toByte()
        return frame
    }

    private fun hasProperty(characteristic: BluetoothGattCharacteristic, property: Int): Boolean =
        characteristic.properties and property != 0

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post(action)
    }

    private fun debug(message: String) {
        if (debugLogs) Log.d(LOG_TAG, message)
    }

    private fun safeError(error: Throwable): String =
        error.message?.take(200) ?: error.javaClass.simpleName

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
        "%02X".format(it.toInt() and 0xff)
    }

    private fun keyHex(bytes: ByteArray): String = bytes.joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    private companion object {
        const val LOG_TAG = "TagLegacy"
        const val FRAME_START = 0x55
        const val FRAME_HEADER_SIZE = 4
        const val FRAME_OVERHEAD = 5
        const val MAX_PAYLOAD_SIZE = 512
        const val KEY_RECORD_SIZE = 28
        const val CAPABILITY_LOGIN = 0x01
        const val CAPABILITY_V2_BINDING = 0x04
        const val COMMAND_LOGIN = 0x01
        const val RESPONSE_LOGIN = 0x81
        const val COMMAND_READ_KEYS = 0x03
        const val RESPONSE_READ_KEYS = 0x83
        const val COMMAND_BIND_RESULT = 0x04
        const val SDK_GATT_SETTLE_MS = 750L
        const val PROTOCOL_SETTLE_MS = 1_000L
        const val BIND_RESULT_SETTLE_MS = 2_015L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val MTU_TIMEOUT_MS = 5_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val GATT_OPERATION_TIMEOUT_MS = 8_000L
        const val RESPONSE_TIMEOUT_MS = 8_000L
        val SERVICE_UUID: UUID = UUID.fromString("1236fccd-d966-1222-8333-bef9c223df6a")
        val WRITE_UUID: UUID = UUID.fromString("1236fccf-d966-1222-8333-bef9c223df6a")
        val NOTIFY_UUID: UUID = UUID.fromString("1236fcce-d966-1222-8333-bef9c223df6a")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
