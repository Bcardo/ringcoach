package com.bcardo.ringcoach

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RingProtocolTest {
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
    private fun field(tag: Int, value: Int) = byteArrayOf(tag.toByte()) + ints(value)
    private fun record(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte(), 0) + ints(body.size) + body
    private fun frame(index: Int, flag: Int, body: ByteArray, window: Int = 0) = byteArrayOf(10, 10, 63, (8 or flag or (window shl 2)).toByte()) + ints(index) + body
    private fun hrv(): ByteArray = field(16, 16) + ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(70.5f).putFloat(61.25f).putInt(100000).putInt(100300).array()
    private fun sleep(): ByteArray = field(1, 100000) + field(2, 126400) + field(3, 0) + field(5, 126500) +
        byteArrayOf(117) + ByteArray(16) + field(80, 0) + byteArrayOf(81, 1) + field(87, 60) + field(88, 120) + field(89, 30)

    @Test fun mixedOxygenSleepAndHrvReachesHrvAfterEarlierFeatures() {
        val msg = byteArrayOf(1, -126, 3) + record(226, byteArrayOf(1, 2, 3)) + byteArrayOf(16, 0) + sleep() + record(150, hrv())
        val decoded = RingProtocol.decode(msg)
        assertEquals(setOf(226, 16, 150), decoded.features)
        val point = decoded.points.single { it.metric == "hrv_rmssd" }
        assertEquals(61.25, point.value, 0.0001); assertEquals(70.5, point.extra!!, 0.0001)
        assertEquals(RingProtocol.EPOCH + 100000000L, point.start)
        assertEquals(1, decoded.points.count { it.metric == "sleep_session" })
        for (bad in listOf(msg.dropLast(1).toByteArray(), byteArrayOf(1, -126, 1, -103))) {
            assertThrows(IllegalArgumentException::class.java) { RingProtocol.decode(bad) }
        }
    }
    @Test fun fragmentationRequiresOrderedChunksAndSeparatesReconnects() {
        val bulk = RingBulkAssembler()
        val msg = byteArrayOf(1, -126, 1) + record(150, hrv())
        assertNull(bulk.feed(frame(0, 0, msg.copyOfRange(0, 10))))
        assertNull(bulk.feed(frame(0, 0, msg.copyOfRange(0, 10))))
        assertArrayEquals(msg, bulk.feed(frame(1, 2, msg.copyOfRange(10, msg.size))))
        assertNull(bulk.feed(frame(0, 0, byteArrayOf(1))))
        assertThrows(IllegalArgumentException::class.java) { bulk.feed(frame(2, 2, byteArrayOf(2))) }
        assertThrows(IllegalArgumentException::class.java) { bulk.feed(frame(1, 2, byteArrayOf(2))) }
        bulk.feed(frame(0, 0, byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { bulk.feed(frame(1, 2, byteArrayOf(2), 1)) }
    }
    @Test fun deviceInfoCannotManufactureHrvAndArraysMustBeAligned() {
        assertTrue(RingProtocol.decode(byteArrayOf(1, -126, 1, 5, 2, -106, 0)).points.isEmpty())
        val malformed = byteArrayOf(1, -126, 1) + record(150, field(16, 15) + ByteArray(15))
        assertThrows(IllegalArgumentException::class.java) { RingProtocol.decode(malformed) }
        val nan = field(16, 16) + ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(1f).putInt(1).putInt(2).array()
        assertThrows(IllegalArgumentException::class.java) { RingProtocol.decode(byteArrayOf(1, -126, 1) + record(150, nan)) }
    }
    @Test fun heartRateMinuteBinsUseStartOffsetAndTemperatureKeepsAmbientSeparate() {
        val body = field(1, 100000) + field(2, 100180) + field(36, 4) + byteArrayOf(2, 66, 60, 70)
        val hr = RingProtocol.decode(byteArrayOf(1, -126, 1) + record(10, body)).points.single()
        assertEquals(RingProtocol.EPOCH + 100120000L, hr.start); assertEquals(66.0, hr.value, 0.0)
        val temp = field(165, 16) + ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(100000).putInt(100060).putFloat(32.5f).putFloat(24.25f).array()
        val p = RingProtocol.decode(byteArrayOf(1, -126, 1) + record(225, temp)).points.single()
        assertEquals(32.5, p.value, 0.0); assertEquals(24.25, p.extra!!, 0.0)
    }
}
