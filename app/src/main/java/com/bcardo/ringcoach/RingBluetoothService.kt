package com.bcardo.ringcoach

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Continuous opt-in passive subscription. No health queries, ACKs, profile writes or rekeying. */
@android.annotation.SuppressLint("MissingPermission")
class RingBluetoothService : Service() {
    private val thread = HandlerThread("ring-bluetooth")
    private lateinit var worker: Handler
    private lateinit var store: RingBluetoothStore
    private var gatt: BluetoothGatt? = null
    private var generation = 0
    private var active = false
    private var promoted = false
    private var attempt = 0
    private var source = ""
    private var assembler = RingBulkAssembler()
    private var report = JSONObject()
    private val dataService = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
    private val notifyId = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val reconnect = Runnable { connect() }
    private val timeout = Runnable { fail("connection_timeout") }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!active) return
            if (!permitted(this@RingBluetoothService) || !store.enabled()) { end("permission_or_opt_in_removed"); return }
            save(); worker.postDelayed(this, 60000)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        store = RingBluetoothStore(this)
        if (!store.enabled() || !permitted(this)) {
            store.status(store.status().put("state", "stopped").put("connected", false).put("subscribed", false)
                .put("reason", "permission_or_opt_in_required"))
            stopSelf(); return
        }
        thread.start(); worker = Handler(thread.looper)
        // Promote immediately, before any Bluetooth or database IO.
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,
            "Ring overnight collection", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(NOTIFICATION, notification("Starting Ring collection")); promoted = true }
        catch (_: SecurityException) {
            report = store.status().put("state", "stopped").put("connected", false).put("subscribed", false)
                .put("reason", "foreground_permission_required")
            store.status(report); stopSelf(); return
        }
        worker.post {
            if (!store.enabled() || !permitted(this)) { end("permission_or_opt_in_required"); return@post }
            active = true
            report = store.status().put("state", "starting").put("connected", false).put("subscribed", false)
                .put("started_at", Instant.now().toString()).put("protocol_writes", "subscription_only")
            worker.post(heartbeat); connect()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) worker.post { store.setEnabled(false); end("owner_stopped") }
        return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RingBluetoothService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("RingCoach sleep collection").setContentText(text).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop collection", stop).build()).setOngoing(true).build()
    }
    private fun save() {
        report.put("heartbeat_at", Instant.now().toString())
        store.status(report)
    }
    private fun state(value: String) {
        report.put("state", value); save()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
            notification(if (value == "subscribed") "Listening for Ring sleep data" else "Waiting to reconnect to Ring"))
    }
    private fun closeLink() {
        generation++
        worker.removeCallbacks(timeout)
        assembler.reset()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) { }
        gatt = null
    }
    private fun end(reason: String) {
        active = false; worker.removeCallbacksAndMessages(null); closeLink()
        report.put("connected", false).put("subscribed", false).put("state", "stopped").put("reason", reason)
        save(); stopSelf()
    }
    private fun fail(reason: String) {
        if (!active) return
        closeLink()
        report.put("connected", false).put("subscribed", false).put("reason", reason)
        state("reconnecting")
        worker.removeCallbacks(reconnect)
        val seconds = minOf(300, 30 * (1 shl minOf(attempt, 3)))
        worker.postDelayed(reconnect, seconds * 1000L)
    }
    private fun connect() {
        if (!active) return
        if (!store.enabled() || !permitted(this)) { end("permission_or_opt_in_removed"); return }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) { fail("bluetooth_off"); return }
            val rings = adapter.bondedDevices.filter { it.name?.contains("Galaxy Ring", true) == true }
            if (rings.size != 1) { fail("bonded_ring_not_unique"); return }
            closeLink()
            val ring = rings.single()
            source = MessageDigest.getInstance("SHA-256").digest(ring.address.toByteArray()).joinToString("") { "%02x".format(it) }
            val current = generation
            attempt++
            report.put("connection_attempts", report.optInt("connection_attempts") + 1).put("reason", JSONObject.NULL)
                .put("connected", false).put("subscribed", false)
            state("connecting")
            gatt = ring.connectGatt(this, false, object : BluetoothGattCallback() {
                private var discoveryStarted = false
                private var subscriptionStarted = false
                private var subscriptionComplete = false
                private fun discover(link: BluetoothGatt) {
                    if (discoveryStarted) return
                    discoveryStarted = true
                    if (!link.discoverServices()) fail("discovery_start_failed")
                }
                private fun subscribe(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, retries: Int = 5) {
                    if (!active || current != generation || subscriptionComplete) return
                    val result = link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    report.put("subscription_request_status", result)
                    if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && retries > 0) {
                        report.put("subscription_busy_retries", report.optInt("subscription_busy_retries") + 1)
                        worker.postDelayed({
                            if (active && current == generation && !subscriptionComplete) {
                                if (!permitted(this@RingBluetoothService)) end("bluetooth_permission_removed")
                                else try { subscribe(link, descriptor, retries - 1) }
                                catch (_: SecurityException) { end("bluetooth_permission_removed") }
                                catch (_: Exception) { fail("subscription_write_failed") }
                            }
                        }, 1000)
                    } else if (result != BluetoothStatusCodes.SUCCESS) fail("subscription_write_failed")
                    save()
                }
                fun post(action: (BluetoothGatt) -> Unit) { worker.post {
                    if (!active || current != generation) return@post
                    if (!permitted(this@RingBluetoothService)) { end("bluetooth_permission_removed"); return@post }
                    try { gatt?.let(action) } catch (_: SecurityException) { end("bluetooth_permission_removed") }
                    catch (_: Exception) { fail("gatt_operation_failed") }
                } }
                override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) = post {
                    report.put("last_connection_status", status)
                    if (status == 0 && newState == BluetoothProfile.STATE_CONNECTED) {
                        report.put("connected", true); state("discovering")
                        if (!it.requestMtu(517)) discover(it)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) fail("link_disconnected")
                }
                override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) = post {
                    report.put("mtu", mtu)
                    discover(it)
                }
                override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = post {
                    if (subscriptionStarted || subscriptionComplete) return@post
                    val characteristic = it.getService(dataService)?.getCharacteristic(notifyId)
                    val descriptor = characteristic?.getDescriptor(cccd)
                    if (status != 0 || characteristic == null || descriptor == null || !it.setCharacteristicNotification(characteristic, true)) {
                        fail("subscription_setup_failed"); return@post
                    }
                    subscriptionStarted = true
                    subscribe(it, descriptor)
                }
                override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post {
                    if (descriptor.uuid != cccd) return@post
                    report.put("subscription_status", status)
                    if (status != 0) fail("subscription_failed")
                    else {
                        subscriptionComplete = true
                        attempt = 0; worker.removeCallbacks(timeout)
                        report.put("subscribed", true).put("subscribed_at", Instant.now().toString()); state("subscribed")
                    }
                }
                override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    if (characteristic.service.uuid != dataService || characteristic.uuid != notifyId) return
                    val copy = value.copyOf()
                    post { receive(copy) }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            worker.postDelayed(timeout, 60000)
        } catch (_: SecurityException) { end("bluetooth_permission_removed") }
        catch (_: Exception) { fail("connection_setup_failed") }
    }
    private fun receive(frame: ByteArray) {
        val at = Instant.now()
        report.put("packet_count", report.optLong("packet_count") + 1).put("last_packet_at", at.toString())
        if (frame.size >= 3 && frame[0].toInt() == 10 && frame[1].toInt() == 10) {
            report.put("health_packet_count", report.optLong("health_packet_count") + 1)
            if (frame[2].toInt() and 63 == 63) try {
                val message = assembler.feed(frame)
                if (message != null) {
                    val transfer = RingProtocol.decode(message)
                    val queued = store.ingest(transfer, source, at)
                    report.put("decoded_transfers", report.optLong("decoded_transfers") + 1)
                        .put("last_transfer_at", at.toString()).put("last_error", JSONObject.NULL)
                    if (queued.isNotEmpty()) RingMorningJob.schedule(this)
                }
            } catch (_: IllegalArgumentException) {
                report.put("decode_errors", report.optLong("decode_errors") + 1).put("last_error", "unsupported_or_invalid_transfer")
            }
        }
        save()
    }
    override fun onDestroy() {
        if (::worker.isInitialized) worker.post {
            active = false; worker.removeCallbacksAndMessages(null); closeLink()
            report.put("connected", false).put("subscribed", false).put("state", "stopped")
            save(); store.close(); thread.quitSafely()
        } else if (::store.isInitialized) store.close()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.bcardo.ringcoach.STOP_RING_COLLECTION"
        private const val CHANNEL = "ring-overnight"
        private const val NOTIFICATION = 9301
        fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        fun resume(context: Context) {
            val enabled = RingBluetoothStore(context).use { it.enabled() }
            if (!enabled || !permitted(context)) return
            try { context.startForegroundService(Intent(context, RingBluetoothService::class.java)) }
            catch (_: Exception) { RingBluetoothStore(context).use { store ->
                store.status(store.status().put("state", "open_app_to_resume").put("connected", false).put("subscribed", false))
            } }
        }
    }
}

class RingBluetoothBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            RingBluetoothService.resume(context)
            RingMorningJob.schedule(context)
        }
    }
}
