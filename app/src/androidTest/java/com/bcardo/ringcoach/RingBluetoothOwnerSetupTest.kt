package com.bcardo.ringcoach

import android.Manifest
import android.app.UiAutomation
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** Explicit owner-only setup/verification. Normal runs skip; never toggles the accessibility reader. */
@RunWith(AndroidJUnit4::class)
class RingBluetoothOwnerSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun optIn() = assumeTrue(args.getString("ring_owner_setup") == "true")

    @Test fun queueAdviceForAlreadyImportedSleep() {
        optIn()
        assumeTrue(args.getString("queue_saved_sleep_advice") == "true")
        RingBluetoothStore(context).use { store ->
            assertTrue(store.enabled())
            assertTrue(store.snapshot(LocalDate.now()).optInt("target_date_session_count") > 0)
            store.queueMorning(LocalDate.now())
        }
        RingMorningJob.schedule(context)
    }

    @Test fun requestCollectionPermissionsAndStart() {
        optIn()
        assumeTrue(args.getString("activate_collection") == "true")
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            val activity = instrumentation.startActivitySync(Intent(context, PermissionsRationaleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.requestPermissions(needed.toTypedArray(), 9301) }
            repeat(45) {
                    val root = ui.rootInActiveWindow
                    val allow = root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_button")?.firstOrNull()
                        ?: root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_foreground_only_button")?.firstOrNull()
                        ?: root?.findAccessibilityNodeInfosByViewId("com.google.android.permissioncontroller:id/permission_allow_button")?.firstOrNull()
                    allow?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return@repeat
                Thread.sleep(1000)
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
        assertTrue("Bluetooth permission required", RingBluetoothService.permitted(context))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        RingBluetoothStore(context).use { it.setEnabled(true) }
        RingBluetoothService.resume(context)
        var subscribed = false
        repeat(45) {
            if (!subscribed) { Thread.sleep(1000); subscribed = RingBluetoothStore(context).use { it.status().optBoolean("subscribed") } }
        }
        assertTrue("Collector did not subscribe; check status without changing pairing", subscribed)
        assertTrue("Reader enablement was lost", SamsungHealthScreenReader.configured(context))
    }

    @android.annotation.SuppressLint("MissingPermission")
    @Test fun verifyAndOptionallyImportPreservedResearchCapture() {
        optIn()
        assumeTrue(args.getString("verify_preserved_capture") == "true")
        val file = File(context.filesDir, "ring-research-import.jsonl")
        assertTrue("Private preserved capture required", file.isFile)
        val assemblers = mutableMapOf<Int, RingBulkAssembler>()
        val results = mutableListOf<Pair<Instant, RingTransfer>>()
        file.forEachLine { line ->
            val row = JSONObject(line)
            if (row.optString("kind") == "notification") {
                val hex = row.getString("hex")
                val frame = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (frame.size >= 3 && frame[0].toInt() == 10 && frame[1].toInt() == 10 && frame[2].toInt() and 63 == 63) {
                    assemblers.getOrPut(row.getInt("connection")) { RingBulkAssembler() }.feed(frame)?.let {
                        results.add(Instant.parse(row.getString("at")) to RingProtocol.decode(it))
                    }
                }
            }
        }
        assertEquals(10, results.size)
        assertEquals(921, results.sumOf { it.second.points.count { p -> p.metric == "hrv_rmssd" } })
        assertEquals(414, results.sumOf { it.second.points.count { p -> p.metric == "respiration" } })
        if (args.getString("import_preserved_capture") == "true") {
            assertTrue(RingBluetoothService.permitted(context))
            val rings = context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.filter { it.name?.contains("Galaxy Ring", true) == true }
            assertEquals(1, rings.size)
            val source = MessageDigest.getInstance("SHA-256").digest(rings.single().address.toByteArray()).joinToString("") { "%02x".format(it) }
            RingBluetoothStore(context).use { store ->
                results.forEach { (at, transfer) -> store.ingest(transfer, source, at, origin = "research_capture_import") }
                assertTrue(store.snapshot(LocalDate.now()).optInt("target_date_session_count") > 0)
                if (args.getString("queue_morning_advice") == "true") store.queueMorning(LocalDate.now())
            }
            RingMorningJob.schedule(context)
            file.delete()
        }
    }
}
