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
import android.os.SystemClock
import android.util.Log
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
    private val onLine: (String) -> Unit,
    private val onDiagnostic: (String) -> Unit = {}
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
    private var connectTimeout: Runnable? = null
    private var mtuTimeout: Runnable? = null
    private var notificationsStarted = false
    private var mtuPending = false
    private var highPriority = false
    private var idlePriorityTimeout: Runnable? = null
    private var chunkStartedAtMs = 0L
    private var lastChunkSize = 0
    private fun trace(message: String) {
        Log.d("OffGridBLE", message)
        onDiagnostic(message)
    }
    private val retry = Runnable { if (!stopped) openGatt() }
    val ready: Boolean get() = connected && authenticated

    fun connect(address: String) {
        trace("Connect requested to BLE MAC $address")
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
        connectTimeout?.let(main::removeCallbacks);connectTimeout = null
        mtuTimeout?.let(main::removeCallbacks);mtuTimeout = null
        idlePriorityTimeout?.let(main::removeCallbacks);idlePriorityTimeout = null
        notificationsStarted = false
        mtuPending = false
        highPriority = false
        negotiatedMtu = 23
        lastChunkSize = 0
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
            trace("Calling connectGatt(LE) for $address")
            val created = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (created == null) { scheduleRetry("connectGatt returned null"); return }
            gatt = created
            val timeout = Runnable {
                if (created === gatt && !connected) scheduleRetry("GATT connection timed out")
            }
            connectTimeout = timeout
            main.postDelayed(timeout, 15_000L)
        } catch (ex: Exception) {
            trace("connectGatt exception: ${ex.javaClass.simpleName}: ${ex.message}")
            scheduleRetry("BLE: ${ex.message ?: "connection error"}")
        }
    }
    private fun scheduleRetry(status: String) {
        if (stopped) return
        trace("Retry: $status")
        closeGatt()
        val delay = min(30_000L, 1_500L shl min(4, reconnectAttempt++))
        onStatus("$status · retry in ${delay / 1000}s", false)
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }


    /** Android's default MTU is 23 (20 payload bytes). Request 247 BEFORE the
     * CCCD subscription so the single in-flight GATT operation rule is kept.
     * A failed request uses the original MTU=23 path without changing the protocol. */
    private fun negotiateMtuThenSubscribe(client: BluetoothGatt) {
        if (client !== gatt || stopped || notificationsStarted) return
        if (negotiatedMtu > 23) {
            trace("Existing BLE MTU=$negotiatedMtu; no additional MTU request needed")
            subscribeNotifications(client)
            return
        }
        mtuPending = true
        val started = try { client.requestMtu(247) } catch (ex: Exception) {
            trace("MTU request exception: ${ex.javaClass.simpleName}")
            false
        }
        if (!started) {
            mtuPending = false
            trace("MTU request unavailable; using 23-byte default")
            subscribeNotifications(client)
            return
        }
        trace("Requesting BLE ATT MTU (Android 14+ may request 517; peripheral caps it)")
        val fallback = Runnable {
            if (client === gatt && mtuPending && !notificationsStarted) {
                mtuPending = false
                trace("MTU callback timeout; continuing with negotiated MTU=$negotiatedMtu")
                subscribeNotifications(client)
            }
        }
        mtuTimeout = fallback
        main.postDelayed(fallback, 3_000L)
    }

    private fun subscribeNotifications(client: BluetoothGatt) {
        if (client !== gatt || stopped || notificationsStarted) return
        val n = notifyCharacteristic ?: run { scheduleRetry("Notification characteristic missing"); return }
        if (!client.setCharacteristicNotification(n, true)) {
            scheduleRetry("Cannot enable BLE notifications"); return
        }
        val descriptor = n.getDescriptor(CCCD) ?: run {
            scheduleRetry("Notification descriptor unavailable"); return
        }
        awaitingChallenge = true
        val enabled = try {
            if (Build.VERSION.SDK_INT >= 33) {
                client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                client.writeDescriptor(descriptor)
            }
        } catch (ex: Exception) {
            trace("CCCD write exception: ${ex.javaClass.simpleName}")
            false
        }
        if (!enabled) { scheduleRetry("Cannot subscribe to BLE notifications"); return }
        notificationsStarted = true
        trace("Subscribing to notifications at MTU=$negotiatedMtu, payload=${min(180,maxOf(20,negotiatedMtu-3))}")
        onStatus("Authenticating node", false)
    }

    /** Priority is a hint to the BLE controller, not a change to encryption or
     * delivery semantics. Drop back to BALANCED after the active chat burst. */
    private fun preferFastConnection() {
        val active = gatt ?: return
        if (!highPriority) {
            try {
                highPriority = active.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (highPriority) trace("Requested high-priority BLE connection")
            } catch (ex: Exception) {
                trace("High-priority BLE request unavailable: ${ex.javaClass.simpleName}")
            }
        }
        idlePriorityTimeout?.let(main::removeCallbacks)
        val idle = object : Runnable {
            override fun run() {
                if (active !== gatt || stopped) return
                if (writing || writerQueue.isNotEmpty()) {
                    main.postDelayed(this, 2_000L)
                    return
                }
                try { active.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
                catch (_: Exception) {}
                highPriority = false
                idlePriorityTimeout = null
                Log.d("OffGridBLE", "BLE connection priority restored to balanced")
            }
        }
        idlePriorityTimeout = idle
        main.postDelayed(idle, 20_000L)
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
            // Commands, not secrets or ciphertext, are logged for troubleshooting.
            val commandType = line.substringBefore(' ').substringBefore('|').trim()
            trace("TX $commandType: ${bytes.size} bytes / ${(bytes.size+chunkSize-1)/chunkSize} GATT writes (MTU=$negotiatedMtu)")
            preferFastConnection()
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
        chunkStartedAtMs = SystemClock.elapsedRealtime()
        lastChunkSize = bytes.size
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
                if (line.isNotEmpty()) {
                    val kind = line.substringBefore('|')
                    Log.d("OffGridBLE", "RX_LINE type=$kind bytes=${line.length} at=${SystemClock.elapsedRealtime()}ms")
                    handleLine(line)
                }
            } else if ((b.toInt() and 255) in 32..126 && partial.length < 2048) partial.append(c)
            else partial.setLength(0)
        }
    }
    private fun handleLine(line: String) {
        if (line.startsWith("CHAL|")) {
            trace("Received authentication challenge")
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
            trace("Authentication succeeded")
            onStatus("Connected · authenticated", true)
            send("STATUS")
            return
        }
        if (line.startsWith("AUTH_ERR") || line == "AUTH_REQUIRED") {
            trace("Authentication rejected by node: ${line.substringBefore('|')}")
            onStatus("Authentication failed · check pairing key", false)
            return
        }
        if (authenticated) onLine(line)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (client !== gatt || stopped) return@post
                trace("GATT state: status=$status newState=$newState")
                connectTimeout?.let(main::removeCallbacks);connectTimeout = null
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                    preferFastConnection()
                    onStatus("Discovering BLE bridge", false)
                    if (!client.discoverServices()) scheduleRetry("Service discovery unavailable")
                    else trace("Discovering services")
                } else scheduleRetry("GATT disconnected (status=$status state=$newState)")
            }
        }
        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (client !== gatt || stopped) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) negotiatedMtu = mtu
                trace("MTU negotiation completed: status=$status mtu=$negotiatedMtu, max payload=${min(180, maxOf(20, negotiatedMtu-3))}")
                if (mtuPending) {
                    mtuPending = false
                    mtuTimeout?.let(main::removeCallbacks);mtuTimeout = null
                    subscribeNotifications(client)
                }
            }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            main.post {
                if (client !== gatt) return@post
                trace("Service discovery callback: status=$status, services=${client.services.size}")
                if (status != BluetoothGatt.GATT_SUCCESS) { scheduleRetry("BLE service discovery failed: $status"); return@post }
                val service: BluetoothGattService = client.getService(SERVICE_UUID) ?: run {
                    scheduleRetry("This ESP32 does not run the Phase 2.0 BLE firmware"); return@post
                }
                writeCharacteristic = service.getCharacteristic(WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID)
                if (writeCharacteristic == null || notifyCharacteristic == null) {
                    scheduleRetry("OffGrid BLE characteristics unavailable"); return@post
                }
                // One GATT operation at a time: wait for onMtuChanged before CCCD.
                negotiateMtuThenSubscribe(client)
            }
        }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (client !== gatt) return@post
                trace("CCCD descriptor write status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) { scheduleRetry("Notification subscription failed: $status"); return@post }
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
                val duration = SystemClock.elapsedRealtime() - chunkStartedAtMs
                Log.d("OffGridBLE", "TX_CHUNK bytes=$lastChunkSize mtu=$negotiatedMtu latency_ms=$duration status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) { trace("Characteristic write failed: status=$status"); scheduleRetry("BLE write error $status");return@post }
                if (writerQueue.isNotEmpty()) writerQueue.removeFirst()
                writing = false;writeNext()
            }
        }
    }
}
