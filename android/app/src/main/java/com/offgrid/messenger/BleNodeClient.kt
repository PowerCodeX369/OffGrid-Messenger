package com.offgrid.messenger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.min

/** One trusted BLE peripheral at a time, active-app reconnect with bounded backoff.
 * Writes are serialized: Android permits only one in-flight GATT operation.
 * BLE authentication is HMAC over a fresh random challenge from the ESP32. */
@SuppressLint("MissingPermission")
class BleNodeClient(
    private val context: Context,
    private val keyProvider: () -> ByteArray?,
    private val onStatus: (String, Boolean) -> Unit,
    private val onLine: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b22d904-7bd5-48ba-b641-4e8026030b01")
        val WRITE_UUID: UUID = UUID.fromString("7b22d904-7bd5-48ba-b641-4e8026030b02")
        val NOTIFY_UUID: UUID = UUID.fromString("7b22d904-7bd5-48ba-b641-4e8026030b03")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val writerQueue = ArrayDeque<ByteArray>()
    private val partial = StringBuilder()
    private var desiredMac: String? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var connected = false
    private var authenticated = false
    private var writing = false
    private var reconnectAttempt = 0
    private var negotiatedMtu = 23
    private var awaitingChallenge = false
    private var stopped = true
    private var writeTimeout: Runnable? = null
    private val retry = Runnable { if (!stopped) openGatt() }
    val ready: Boolean get() = connected && authenticated

    fun connect(address: String) {
        desiredMac = address
        stopped = false
        reconnectAttempt = 0
        closeGatt()
        openGatt()
    }
    fun stop() {
        stopped = true
        main.removeCallbacks(retry)
        closeGatt()
        onStatus("Disconnected", false)
    }
    private fun closeGatt() {
        val old = gatt
        gatt = null
        connected = false; authenticated = false; writing = false
        writeCharacteristic = null; notifyCharacteristic = null
        writerQueue.clear(); partial.setLength(0)
        writeTimeout?.let(main::removeCallbacks);writeTimeout = null
        try { old?.disconnect() } catch (_: Exception) {}
        try { old?.close() } catch (_: Exception) {}
    }
    private fun openGatt() {
        if (stopped) return
        main.removeCallbacks(retry)
        val address = desiredMac ?: return
        try {
            val adapter: BluetoothAdapter = manager.adapter ?: throw IllegalStateException("Bluetooth unavailable")
            if (!adapter.isEnabled) { scheduleRetry("Enable Bluetooth"); return }
            val device = adapter.getRemoteDevice(address)
            onStatus("Connecting to $address", false)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (ex: Exception) { scheduleRetry("BLE: ${ex.message ?: "connection error"}") }
    }
    private fun scheduleRetry(status: String) {
        if (stopped) return
        closeGatt()
        onStatus("$status · reconnecting", false)
        val delay = min(30_000L, 1_500L shl min(4, reconnectAttempt++))
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    /** ASCII command line; split into MTU-sized GATT writes. */
    fun send(command: String) {
        if (!ready || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return
        if (command.length > 508) return
        queueRaw("$command\n")
    }
    private fun queueRaw(line: String) {
        main.post {
            if (gatt == null) return@post
            val bytes = line.toByteArray(StandardCharsets.US_ASCII)
            val chunkSize = min(180, maxOf(20, negotiatedMtu - 3))
            for (start in bytes.indices step chunkSize)
                writerQueue.addLast(bytes.copyOfRange(start, min(bytes.size, start + chunkSize)))
            writeNext()
        }
    }
    private fun writeNext() {
        if (writing) return
        val bytes = writerQueue.firstOrNull() ?: return
        val ch = writeCharacteristic ?: return
        val active = gatt ?: return
        writing = true
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                active.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                active.writeCharacteristic(ch)
            }
            if (!accepted) { writing = false; scheduleRetry("BLE write refused"); return }
            val onTimeout = Runnable { if (writing) scheduleRetry("BLE write timed out") }
            writeTimeout = onTimeout
            main.postDelayed(onTimeout, 10_000)
        } catch (ex: Exception) { writing = false; scheduleRetry("BLE write failed") }
    }
    private fun receive(bytes: ByteArray) {
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c == '\n') {
                val line = partial.toString().trimEnd('\r')
                partial.setLength(0)
                if (line.isNotEmpty()) handleLine(line)
            } else if ((b.toInt() and 255) in 32..126 && partial.length < 2048) partial.append(c)
            else partial.setLength(0)
        }
    }
    private fun handleLine(line: String) {
        if (line.startsWith("CHAL|")) {
            val key = keyProvider() ?: run { onStatus("Pairing key required", false); return }
            try {
                awaitingChallenge = false
                queueRaw("AUTH|${OgmCodec.proof(key, line.substringAfter('|'))}\n")
            } catch (_: Exception) { onStatus("Invalid BLE challenge", false) }
            finally { key.fill(0) }
            return
        }
        if (line == "AUTH_OK") {
            authenticated = true; reconnectAttempt = 0
            onStatus("Connected · authenticated", true)
            send("STATUS")
            return
        }
        if (line.startsWith("AUTH_ERR") || line == "AUTH_REQUIRED") {
            onStatus("Authentication failed · check pairing key", false)
            return
        }
        if (authenticated) onLine(line)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (client !== gatt || stopped) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true; reconnectAttempt = 0
                    onStatus("Discovering BLE bridge", false)
                    if (!client.discoverServices()) scheduleRetry("Service discovery unavailable")
                } else scheduleRetry("ESP32 unavailable")
            }
        }
        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (client === gatt && status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            main.post {
                if (client !== gatt || status != BluetoothGatt.GATT_SUCCESS) { scheduleRetry("BLE service discovery failed"); return@post }
                val service: BluetoothGattService = client.getService(SERVICE_UUID) ?: run {
                    scheduleRetry("This ESP32 does not run the Phase 2.0 BLE firmware"); return@post
                }
                writeCharacteristic = service.getCharacteristic(WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID)
                val n = notifyCharacteristic
                if (writeCharacteristic == null || n == null || !client.setCharacteristicNotification(n, true)) {
                    scheduleRetry("OffGrid BLE characteristics unavailable"); return@post
                }
                val cccd = n.getDescriptor(CCCD) ?: run { scheduleRetry("Notification descriptor unavailable");return@post }
                awaitingChallenge = true
                val enabled = if (Build.VERSION.SDK_INT >= 33) {
                    client.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    client.writeDescriptor(cccd)
                }
                if (!enabled) scheduleRetry("Cannot subscribe to BLE notifications")
                else onStatus("Authenticating node", false)
            }
        }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (client !== gatt || status != BluetoothGatt.GATT_SUCCESS) return@post
                if (awaitingChallenge) {
                    // Ask for a fresh challenge only if the automatic subscription challenge is lost.
                    main.postDelayed({ if (client === gatt && awaitingChallenge && !authenticated) queueRaw("AUTH?\n") }, 1800)
                }
            }
        }
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (client === gatt && characteristic.uuid == NOTIFY_UUID) receive(value) }
        }
        @Deprecated("Required for BLE notifications on Android 8-12")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value?.clone() ?: return
                main.post { if (client === gatt && characteristic.uuid == NOTIFY_UUID) receive(bytes) }
            }
        }
        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (client !== gatt) return@post
                writeTimeout?.let(main::removeCallbacks);writeTimeout = null
                if (status != BluetoothGatt.GATT_SUCCESS) { scheduleRetry("BLE write error $status");return@post }
                if (writerQueue.isNotEmpty()) writerQueue.removeFirst()
                writing = false;writeNext()
            }
        }
    }
}
