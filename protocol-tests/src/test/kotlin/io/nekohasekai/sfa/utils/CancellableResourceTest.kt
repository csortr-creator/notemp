package io.nekohasekai.sfa.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CancellableResourceTest {
    @Test fun cancellationClosesNativeResourceAndUnblocksBeforeReturning() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val job = async {
            withCancellationClose(close = { closed.countDown() }) {
                entered.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                returned.set(true)
                "discarded"
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(returned.get())
            assertTrue(job.isCancelled)
        } finally { closed.countDown(); job.cancelAndJoin() }
    }
    @Test fun successClosesResourceAfterOperation() = runBlocking {
        val closed = AtomicBoolean(false)
        val value = withCancellationClose({ closed.set(true) }) { assertFalse(closed.get()); 42 }
        assertEquals(42, value)
        assertTrue(closed.get())
    }
    @Test fun exceptionStillClosesResource() = runBlocking {
        val closed = AtomicBoolean(false)
        try { withCancellationClose({ closed.set(true) }) { error("failed") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertTrue(closed.get())
    }
}
