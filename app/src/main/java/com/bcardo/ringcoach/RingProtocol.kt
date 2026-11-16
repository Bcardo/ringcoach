package com.bcardo.ringcoach

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent strict parser. Never emits protocol commands or guesses features from byte matches. */
internal data class RingPoint(val metric: String, val start: Long, val end: Long, val value: Double, val extra: Double? = null)
internal data class RingTransfer(val points: List<RingPoint>, val features: Set<Int>)

internal class RingBulkAssembler {
    private var expected = 0L
    private var window = -1
    private var output = ByteArrayOutputStream()
    fun reset() { expected = 0; window = -1; output = ByteArrayOutputStream() }
    fun feed(frame: ByteArray): ByteArray? {
        try {
            require(frame.size >= 8 && frame[0].toInt() == 10 && frame[1].toInt() == 10 && frame[2].toInt() and 63 == 63)
            val flag = frame[3].toInt() and 3
            val nextWindow = (frame[3].toInt() shr 2) and 1
            val index = ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(flag in 0..2)
            val body = frame.copyOfRange(8, frame.size)
            if (flag == 0) {
                require(index == 0L)
                if (expected == 1L && window == nextWindow && output.toByteArray().contentEquals(body)) return null
                reset(); window = nextWindow
            } else if (flag == 2 && index == 0L && window == -1) window = nextWindow
            require(nextWindow == window && index == expected && output.size() + body.size <= 1024 * 1024)
            output.write(body); expected++
            if (flag != 2) return null
            return output.toByteArray().also { reset() }
        } catch (e: IllegalArgumentException) { reset(); throw e }
    }
}

internal object RingProtocol {
    const val EPOCH = 631152000000L
    private class Reader(val bytes: ByteArray) {
        var pos = 0
        val left get() = bytes.size - pos
        fun take(n: Int): ByteArray { require(n >= 0 && n <= left); return bytes.copyOfRange(pos, pos + n).also { pos += n } }
        fun u8() = take(1)[0].toInt() and 255
        fun u16() = ByteBuffer.wrap(take(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        fun u32() = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun float(): Double = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float.toDouble().also { require(it.isFinite()) }
        fun block(): Reader { val n = u32(); require(n <= left.toLong()); return Reader(take(n.toInt())) }
        fun time(): Long = u32().let { if (it == 0L) 0L else EPOCH + it * 1000 }
    }

    fun decode(message: ByteArray): RingTransfer {
        val r = Reader(message)
        require(r.u8() in 1..2 && r.u8() and 63 == 2)
        val count = r.u8()
        val points = mutableListOf<RingPoint>()
        val features = mutableSetOf<Int>()
        repeat(count) {
            val tag = r.u8()
            when (tag) {
                224 -> r.take(2)
                5 -> { r.take(r.u8()); features.add(tag) }
                16 -> {
                    features.add(tag)
                    do {
                        val more = r.u8(); require(more in 0..1)
                        val fields = Reader(readSleep(r))
                        points.addAll(parseFields(tag, fields))
                    } while (more == 1)
                }
                10, 12, 19, 20, 136, 148, 149, 150, 225, 226, 227 -> {
                    features.add(tag)
                    do {
                        val more = r.u8(); require(more in 0..1)
                        val body = r.block()
                        if (tag in setOf(10, 19, 148, 149, 150, 225)) points.addAll(parseFields(tag, body))
                        // Other length-delimited bodies are captured as feature counts only.
                    } while (more == 1)
                }
                else -> throw IllegalArgumentException("unsupported_feature")
            }
        }
        require(r.left == 0)
        return RingTransfer(points, features)
    }

    private fun readSleep(r: Reader): ByteArray {
        val start = r.pos
        val seen = mutableSetOf<Int>()
        repeat(10) {
            val tag = r.u8(); require(seen.add(tag))
            require(tag in setOf(1, 2, 3, 5, 80, 81, 87, 88, 89, 117))
            r.take(if (tag == 117) 16 else if (tag == 81) 1 else 4)
        }
        return r.bytes.copyOfRange(start, r.pos)
    }

    private fun parseFields(feature: Int, r: Reader): List<RingPoint> {
        var start = 0L
        var end = 0L
        var scalar: Double? = null
        val out = mutableListOf<RingPoint>()
        val seen = mutableSetOf<Int>()
        while (r.left > 0) {
            val tag = r.u8(); require(seen.add(tag))
            when {
                tag == 117 -> r.take(16)
                tag == 1 -> { start = r.time() }
                tag == 2 -> { end = r.time() }
                tag in setOf(3, 5) -> r.take(4)
                feature == 16 && tag in setOf(80, 87, 88, 89) -> r.take(4)
                feature == 16 && tag == 81 -> r.take(1)
                feature in setOf(148, 149, 150) && tag == 16 -> {
                    val block = r.block()
                    val width = if (feature == 150) 16 else 12
                    require(block.left % width == 0)
                    while (block.left > 0) {
                        val first = block.float()
                        val second = if (feature == 150) block.float() else null
                        val a = block.time(); val b = block.time()
                        if (feature == 150) {
                            require(first >= 0 && second!! >= 0)
                            out.add(RingPoint("hrv_rmssd", a, b, second, first))
                        } else out.add(RingPoint(if (feature == 148) "movement" else "respiration", a, b, first))
                    }
                }
                feature == 10 && tag in 32..35 -> { val v = r.u8().toDouble(); if (tag == 32) scalar = v }
                feature == 10 && tag == 37 -> r.take(2)
                feature == 10 && tag == 36 -> {
                    val block = r.block(); require(block.left % 4 == 0 && start > 0)
                    while (block.left > 0) {
                        val a = start + block.u8() * 60000L
                        val v = block.u8().toDouble(); block.take(2)
                        out.add(RingPoint("heart_rate", a, a + 59000, v))
                    }
                }
                feature == 19 && tag in setOf(96, 102, 103) -> { val v = r.u8().toDouble(); if (tag == 96) scalar = v }
                feature == 19 && tag == 97 -> r.take(4)
                feature == 19 && tag == 98 -> {
                    val block = r.block(); require(block.left % 6 == 0 && start > 0)
                    while (block.left > 0) {
                        val a = start + block.u8() * 60000L
                        val v = block.u8().toDouble(); block.take(4)
                        out.add(RingPoint("stress", a, a + 59000, v))
                    }
                }
                feature == 225 && tag == 16 -> r.take(1)
                feature == 225 && tag in setOf(165, 166) -> {
                    val block = r.block(); val width = if (tag == 165) 16 else 11
                    require(block.left % width == 0)
                    while (block.left > 0) {
                        val a = block.time(); val b = block.time()
                        if (tag == 165) out.add(RingPoint("skin_temperature", a, b, block.float(), block.float()))
                        else { block.u8(); block.u16() } // Candidate RRI is not a validated metric.
                    }
                }
                else -> throw IllegalArgumentException("unsupported_field")
            }
        }
        if (feature == 16) out.add(RingPoint("sleep_session", start, end, (end - start) / 60000.0))
        if (out.isEmpty() && scalar != null) out.add(RingPoint(if (feature == 10) "heart_rate_summary" else "stress", start, end, scalar))
        require(out.all { it.start > EPOCH && it.end >= it.start && it.value.isFinite() && (it.extra == null || it.extra.isFinite()) })
        return out
    }
}
