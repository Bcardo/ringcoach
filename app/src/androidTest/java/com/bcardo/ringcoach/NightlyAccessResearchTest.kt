package com.bcardo.ringcoach

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, read-only capability probe. Never subscribes, writes commands, or changes pairing. */
@RunWith(AndroidJUnit4::class)
class NightlyAccessResearchTest {
    @Test fun dumpScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenResearch") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try { automation.waitForIdle(500, 5000) } catch (_: Exception) { }
        val items = org.json.JSONArray()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            val rect = android.graphics.Rect().also(node::getBoundsInScreen)
            items.put(org.json.JSONObject().put("id", node.viewIdResourceName)
                .put("clickable", node.isClickable).put("scrollable", node.isScrollable).put("depth", depth).put("bounds", rect.toShortString()))
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { visit(it, 0) }
        java.io.File(instrumentation.targetContext.filesDir, "research-screen.json").writeText(items.toString(2))
        println("RING_RESEARCH screen_nodes=${items.length()}")
        if (InstrumentationRegistry.getArguments().getString("scroll") == "true") {
            fun scroll(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                return false
            }
            println("RING_RESEARCH scrolled=${automation.rootInActiveWindow?.let(::scroll)}")
        }
        val label = InstrumentationRegistry.getArguments().getString("clickLabel")
        val id = InstrumentationRegistry.getArguments().getString("clickId")
        if (label != null || id != null) {
            fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if ((label != null && (node.text?.toString() == label || node.contentDescription?.toString()?.trim() == label)) ||
                    (id != null && node.viewIdResourceName == id)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
                return null
            }
            var target = automation.rootInActiveWindow?.let(::find)
            while (target != null && !target.isClickable) target = target.parent
            val repetitions = InstrumentationRegistry.getArguments().getString("clickCount")?.toIntOrNull()?.coerceIn(1, 15) ?: 1
            repeat(repetitions) { println("RING_RESEARCH clicked=${target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)}") }
        }
    }

    @Test fun inspectAvailableInterfaces() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ringResearch") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try { automation.waitForIdle(500, 5000) } catch (_: Exception) { }
        val root = automation.rootInActiveWindow
        println("RING_RESEARCH accessibility_root=${root != null} windows=${automation.windows.size}")
        root?.let {
            val labels = mutableListOf<String>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                node.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            visit(it)
            println("RING_RESEARCH active_package=${it.packageName} label_count=${labels.size}")
        }
        val context = instrumentation.targetContext
        assumeTrue("Research requires existing owner-granted Bluetooth access",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        var connection: BluetoothGatt? = null
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val rings = adapter.bondedDevices.filter { it.name?.contains("Galaxy Ring", true) == true }
            println("RING_RESEARCH bonded_ring_count=${rings.size}")
            if (rings.size != 1) return
            val done = CountDownLatch(1)
            val subscribed = CountDownLatch(1)
            val messages = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
                    println("RING_RESEARCH connection_status=$status state=$state")
                    if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) done.countDown()
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    println("RING_RESEARCH discovery_status=$status services=${gatt.services.size}")
                    gatt.services.forEach { service ->
                        println("RING_RESEARCH service=${service.uuid} chars=${service.characteristics.map { "${it.uuid}:${it.properties}" }}")
                    }
                    val characteristic = gatt.getService(java.util.UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb"))
                        ?.getCharacteristic(java.util.UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b"))
                    val cccd = characteristic?.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (characteristic != null && cccd != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        gatt.writeDescriptor(cccd, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else { subscribed.countDown(); done.countDown() }
                }
                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                    println("RING_RESEARCH subscription_status=$status")
                    val characteristic = gatt.getService(java.util.UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb"))
                        ?.getCharacteristic(java.util.UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d"))
                    if (status == 0 && characteristic != null) {
                        // Known read-only battery query verifies notification delivery; no health ACK or deletion.
                        gatt.writeCharacteristic(characteristic, byteArrayOf(11, 11, 2), android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    }
                    subscribed.countDown()
                }
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
                    val channel = if (value.size >= 2 && value[0] == value[1]) (value[0].toInt() and 255).toString() else "other"
                    messages.computeIfAbsent(channel) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                }
            }
            connection = rings.single().connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            println("RING_RESEARCH subscribed=${subscribed.await(15, TimeUnit.SECONDS)}")
            done.await(15, TimeUnit.SECONDS)
            println("RING_RESEARCH notification_channels=${messages.mapValues { it.value.get() }}")
        } finally {
            connection?.disconnect()
            connection?.close()
        }
    }
}
