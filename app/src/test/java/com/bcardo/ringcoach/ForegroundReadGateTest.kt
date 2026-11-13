package com.bcardo.ringcoach

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForegroundReadGateTest {
    @Test fun backgroundStartWaitsForReturnWithoutReadingOrDroppingTheJob() = runBlocking {
        val gate = ForegroundReadGate()
        var reads = 0
        var waiting = false
        val task = async(start = CoroutineStart.UNDISPATCHED) { gate.read(onWaiting = { waiting = it }) { reads++; "evidence" } }
        assertTrue(waiting)
        assertEquals(0, reads)
        assertFalse(task.isCompleted)
        gate.update(true)
        assertEquals("evidence", task.await())
        assertEquals(1, reads)
        assertFalse(waiting)
    }

    @Test fun switchingAwayAndBackDuringAReadDiscardsThatReadEvenIfAlreadyResumed() = runBlocking {
        val gate = ForegroundReadGate()
        gate.update(true)
        var reads = 0
        val release = CompletableDeferred<Unit>()
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            gate.read {
                reads++
                if (reads == 1) { release.await(); "background-denied" } else "fresh evidence"
            }
        }
        gate.update(false)
        gate.update(true)
        release.complete(Unit)
        assertEquals("fresh evidence", task.await())
        assertEquals(2, reads)
    }

    @Test fun permissionErrorWhileVisibleIsPreservedRatherThanRetriedForever() = runBlocking {
        val gate = ForegroundReadGate()
        gate.update(true)
        var reads = 0
        try { gate.read { reads++; throw SecurityException("genuine denial") }; fail("Expected genuine denial") }
        catch (_: SecurityException) { }
        assertEquals(1, reads)
    }

    @Test fun cancellationClearsWaitingState() = runBlocking {
        val gate = ForegroundReadGate()
        var waiting = false
        val task = async(start = CoroutineStart.UNDISPATCHED) { gate.read(onWaiting = { waiting = it }) { "data" } }
        assertTrue(waiting)
        task.cancel(); task.join()
        assertFalse(waiting)
    }
}
