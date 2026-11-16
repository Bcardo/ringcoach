package com.bcardo.ringcoach.bleresearch

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Bounded passive collection only. No health requests, session rekeying, ACKs or deletion. */
@android.annotation.SuppressLint("MissingPermission") // Guard entry and callbacks; revocation ends recording.
class RingCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private lateinit var folder: File
    private lateinit var frames: File
    private var report = JSONObject()
    private var active = false
    private var connecting = false
    private var sequence = 0
    private var reason = "stopped"
    private val dataService = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
    private val notifyId = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
    private val retry = Runnable { connect() }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        folder = File(filesDir, "overnight").apply { mkdirs() }
        frames = File(folder, "capture-${System.currentTimeMillis()}.jsonl")
        val channel = NotificationChannel("ring-research", "Ring Bluetooth research", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, ProbeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        report = JSONObject().put("started_at", Instant.now().toString()).put("state", "recording")
            .put("capture_file", frames.name).put("packet_count", 0).put("health_packet_count", 0)
            .put("health_ack_sent", false).put("pairing_changed", false).put("connection_attempts", 0)
            .put("expires_at", Instant.now().plusSeconds(18 * 3600).toString())
        if (!hasBluetoothPermission()) { reason = "bluetooth_permission_missing"; stopSelf(); return }
        startForeground(1, Notification.Builder(this, "ring-research").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ring Bluetooth recording")
            .setContentText("Passive research capture; open to stop. Ends within 18 hours.")
            .setContentIntent(open).setOngoing(true).build())
        active = true
        save()
        handler.postDelayed({ reason = "expired"; stopSelf() }, 18 * 3600 * 1000L)
        connect()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun save() { File(folder, "status.json").writeText(report.toString(2)) }
    private fun hasBluetoothPermission() = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun connect() {
        if (!active || connecting) return
        if (!hasBluetoothPermission()) { reason = "bluetooth_permission_revoked"; stopSelf(); return }
        try {
            connecting = true
            gatt?.close(); gatt = null
            val rings = getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                .filter { it.name?.contains("Galaxy Ring", true) == true }
            if (rings.size != 1) { reason = "bonded_ring_not_unique"; stopSelf(); return }
            val thisSequence = ++sequence
            report.put("connection_attempts", report.getInt("connection_attempts") + 1).put("connected", false)
            gatt = rings.single().connectGatt(this, false, object : BluetoothGattCallback() {
                private fun post(action: (BluetoothGatt) -> Unit) {
                    handler.post {
                        if (active && thisSequence == sequence) {
                            if (!hasBluetoothPermission()) { reason = "bluetooth_permission_revoked"; stopSelf() }
                            else try { gatt?.let(action) }
                            catch (_: SecurityException) { reason = "bluetooth_permission_revoked"; stopSelf() }
                        }
                    }
                }
                override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, state: Int) = post {
                    report.put("last_connection_status", status)
                    if (status == 0 && state == BluetoothProfile.STATE_CONNECTED) {
                        report.put("connected", true)
                        if (!it.requestMtu(517)) it.discoverServices()
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                        report.put("connected", false); connecting = false
                        handler.removeCallbacks(retry); handler.postDelayed(retry, 30_000)
                    }
                    save()
                }
                override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) = post {
                    report.put("mtu", mtu); it.discoverServices()
                }
                override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) = post {
                    report.put("discovery_status", status)
                    val characteristic = it.getService(dataService)?.getCharacteristic(notifyId)
                    val descriptor = characteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (status == 0 && characteristic != null && descriptor != null && it.setCharacteristicNotification(characteristic, true)) {
                        report.put("subscription_request_status", it.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                    } else { reason = "subscription_setup_failed"; stopSelf() }
                    save()
                }
                override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post {
                    report.put("subscription_status", status)
                    if (status != 0) { reason = "subscription_failed"; stopSelf() }
                    save()
                }
                override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    if (characteristic.service.uuid != dataService || characteristic.uuid != notifyId) return
                    val copy = value.copyOf()
                    post {
                        if (frames.length() >= 32L * 1024 * 1024) { reason = "capture_size_limit"; stopSelf(); return@post }
                        val at = Instant.now().toString()
                        frames.appendText(JSONObject().put("at", at).put("kind", "notification").put("connection", thisSequence)
                            .put("hex", copy.joinToString("") { "%02x".format(it.toInt() and 255) }).toString() + "\n")
                        report.put("packet_count", report.getInt("packet_count") + 1).put("last_packet_at", at)
                        if (copy.size >= 2 && copy[0] == 10.toByte() && copy[1] == 10.toByte())
                            report.put("health_packet_count", report.getInt("health_packet_count") + 1)
                        save()
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            save()
            handler.postDelayed({
                if (active && thisSequence == sequence && !report.optBoolean("connected")) {
                    connecting = false; connect()
                }
            }, 30_000)
        } catch (exception: Exception) {
            report.put("error_class", exception.javaClass.simpleName)
            reason = "setup_error"; stopSelf()
        }
    }
    override fun onDestroy() {
        active = false; handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) { }
        gatt = null
        report.put("state", "stopped").put("reason", reason).put("ended_at", Instant.now().toString()).put("connected", false)
        save(); super.onDestroy()
    }
}
