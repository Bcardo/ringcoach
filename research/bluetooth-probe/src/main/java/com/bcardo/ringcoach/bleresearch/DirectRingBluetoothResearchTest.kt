package com.bcardo.ringcoach.bleresearch

import android.Manifest
import android.bluetooth.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Opt-in owner-device research. Keeps the existing bond and never ACKs/deletes health history. */
class DirectRingBluetoothResearchTest {
    @Test fun inspectBondedDataPath() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("direct_ring_research") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Self-instrumentation runs under this standalone research app's real permission and UID.
        val context = instrumentation.context
        assertTrue("Grant Bluetooth access to the research test package first",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val folder = File(context.filesDir, "direct-ring-research").apply { mkdirs() }
        val frames = File(folder, "frames.jsonl").apply { writeText("") }
        val report = JSONObject().put("started_at", Instant.now().toString()).put("pairing_changed", false)
            .put("health_ack_sent", false)
        val stages = JSONArray()
        val channels = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val events = LinkedBlockingQueue<Pair<String, Int>>()
        val incoming = LinkedBlockingQueue<ByteArray>()
        var connection: BluetoothGatt? = null
        fun stage(name: String, status: Int) { stages.put(JSONObject().put("stage", name).put("status", status)) }
        fun await(name: String, seconds: Long = 15): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                val event = events.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
                if (event.first == name) { stage(name, event.second); return event.second }
            }
            stage(name, -1); return -1
        }
        fun record(kind: String, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val dataPipe = characteristic.service.uuid == UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
            val channel = if (dataPipe && value.size >= 2 && value[0] == value[1]) "data_${value[0].toInt() and 255}"
                else if (dataPipe && value.firstOrNull() == 0xa7.toByte()) "data_hello" else if (dataPipe) "data_other" else "standard_hr"
            if (kind == "notification") channels.computeIfAbsent(channel) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            if (kind == "notification" && dataPipe) incoming.offer(value.copyOf())
            synchronized(frames) { frames.appendText(JSONObject().put("at", Instant.now().toString()).put("kind", kind)
                .put("service", characteristic.service.uuid.toString()).put("characteristic", characteristic.uuid.toString())
                .put("hex", value.joinToString("") { "%02x".format(it.toInt() and 255) }).toString() + "\n") }
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val rings = adapter.bondedDevices.filter { it.name?.contains("Galaxy Ring", true) == true }
            report.put("bonded_ring_count", rings.size)
            assertTrue("Exactly one bonded Galaxy Ring required", rings.size == 1)
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
                    if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED)
                        events.offer("connection" to if (state == BluetoothProfile.STATE_CONNECTED) status else if (status != 0) status else -2)
                }
                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { report.put("mtu", mtu); events.offer("mtu" to status) }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { events.offer("discovery" to status) }
                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { events.offer("subscribe" to status) }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (status == 0) record("read", characteristic, value)
                    events.offer("read" to status)
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { events.offer("write" to status) }
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    record("notification", characteristic, value)
                }
            }
            val gatt = rings.single().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            connection = gatt
            assertTrue("Bonded connection failed", await("connection", 25) == 0)
            if (gatt.requestMtu(517)) await("mtu")
            assertTrue("Discovery did not start", gatt.discoverServices())
            assertTrue("Discovery failed", await("discovery") == 0)
            report.put("service_count", gatt.services.size)
            val service = requireNotNull(gatt.getService(UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")))
            val notify = requireNotNull(service.getCharacteristic(UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")))
            val write = requireNotNull(service.getCharacteristic(UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")))
            report.put("data_notify_properties", notify.properties).put("data_write_properties", write.properties)
            fun subscribe(characteristic: BluetoothGattCharacteristic) {
                assertTrue("Local notification registration failed", gatt.setCharacteristicNotification(characteristic, true))
                val descriptor = requireNotNull(characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
                assertTrue("CCCD write rejected", gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS)
                assertTrue("CCCD write failed", await("subscribe") == 0)
            }
            subscribe(notify)
            if (gatt.readCharacteristic(notify)) await("read")
            val hr = gatt.getService(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
                ?.getCharacteristic(UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"))
            report.put("standard_hr_present", hr != null)
            hr?.let(::subscribe)
            // This phone's current companion uses message 2 and gets a 0x42 response.
            // The older public probe used message 3, which was silent on this firmware.
            val batteryQuery = byteArrayOf(11, 11, 2)
            report.put("battery_query_message_id", 2)
            record("write", write, batteryQuery)
            stage("battery_query_accepted", gatt.writeCharacteristic(write, batteryQuery, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
            await("write", 5)
            if (args.getString("request_health") == "true") {
                fun le(value: Long, size: Int) = ByteArray(size) { index -> (value shr (index * 8)).toByte() }
                val sequence = (Instant.now().epochSecond % 60000) + 1
                val since = Instant.now().minusSeconds(2 * 86400).epochSecond - 631152000L
                val request = byteArrayOf(10, 10, 1, 3, 0xe0.toByte()) + le(sequence, 2) +
                    "050d011b02492703043304".chunked(2).map { it.toInt(16).toByte() }.toByteArray() + le(since, 4)
                record("write", write, request)
                stage("health_request_accepted", gatt.writeCharacteristic(write, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
                await("write", 5)
                report.put("health_data_request_sent", true).put("requested_history_days", 2)
            }
            val duration = args.getString("observe_seconds")?.toLongOrNull()?.coerceIn(5, 45) ?: 30
            var handshakeSent = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(duration)
            fun command(value: ByteArray) {
                record("write", write, value)
                assertTrue("Session write rejected", gatt.writeCharacteristic(write, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS)
                assertTrue("Session write failed", await("write", 5) == 0)
            }
            fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            while (System.nanoTime() < deadline) {
                val frame = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (args.getString("respond_to_hello") == "true" && !handshakeSent && frame.firstOrNull() == 0xa7.toByte()) {
                    handshakeSent = true
                    // Generic host identity and session setup verified in this phone's normal sync.
                    // No account association, health preferences, key exchange, or health ACK is sent.
                    command(hex("a700010101012500f201d4030a53414d53554e47") + ByteArray(148))
                    command(hex("010183014f000200030a") + Instant.now().epochSecond.toString().toByteArray(Charsets.US_ASCII) + hex("040140380501"))
                    command(hex("0101010402050010"))
                    command(hex("0101020101"))
                    command(batteryQuery)
                }
            }
            report.put("responded_to_hello", handshakeSent)
            report.put("observation_seconds", duration)
        } finally {
            connection?.disconnect(); connection?.close()
            report.put("stages", stages).put("notification_channels", JSONObject(channels.mapValues { it.value.get() }))
                .put("finished_at", Instant.now().toString())
            File(folder, "report.json").writeText(report.toString(2))
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "DIRECT_RING_REPORT $report\n") })
        }
    }
}
