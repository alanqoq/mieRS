package com.mieai.qqbot.plugin.miers

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class MiersRequestQueueTest {
    @Test
    fun `capacity includes running and waiting tasks and rejects a full queue`() {
        val started = CountDownLatch(1)
        val release = CompletableFuture<Unit>()
        MiersRequestQueue(maxQueue = 2).use { queue ->
            assertTrue(queue.submit {
                started.countDown()
                release
            })
            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(queue.submit { CompletableFuture.completedFuture(Unit) })
            assertEquals(2, queue.size)
            assertFalse(queue.submit { CompletableFuture.completedFuture(Unit) })

            release.complete(Unit)
            awaitCondition { queue.size == 0 }
            assertTrue(queue.submit { CompletableFuture.completedFuture(Unit) })
        }
    }

    @Test
    fun `tasks run in fifo order and next task waits for stage completion`() {
        val firstStarted = CountDownLatch(1)
        val allCompleted = CountDownLatch(2)
        val firstRelease = CompletableFuture<Unit>()
        val order = CopyOnWriteArrayList<Int>()

        MiersRequestQueue(maxQueue = 3).use { queue ->
            assertTrue(queue.submit {
                order += 1
                firstStarted.countDown()
                firstRelease
            })
            assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(queue.submit {
                order += 2
                allCompleted.countDown()
                CompletableFuture.completedFuture(Unit)
            })
            assertTrue(queue.submit {
                order += 3
                allCompleted.countDown()
                CompletableFuture.completedFuture(Unit)
            })

            assertEquals(listOf(1), order.toList())
            firstRelease.complete(Unit)
            assertTrue(allCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), order.toList())
        }
    }

    @Test
    fun `synchronous and asynchronous failures release capacity for later tasks`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val completed = CountDownLatch(1)

        MiersRequestQueue(maxQueue = 1, failureHandler = { failures += it }).use { queue ->
            assertTrue(queue.submit {
                throw IllegalStateException("synchronous failure")
            })
            awaitCondition { failures.size == 1 && queue.size == 0 }

            assertTrue(queue.submit {
                CompletableFuture.failedFuture<Unit>(IllegalArgumentException("asynchronous failure"))
            })
            awaitCondition { failures.size == 2 && queue.size == 0 }

            assertTrue(queue.submit {
                completed.countDown()
                CompletableFuture.completedFuture(Unit)
            })
            assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `close cancels active work is idempotent and rejects later submissions`() {
        val started = CountDownLatch(1)
        val running = CompletableFuture<Unit>()
        val waitingInvoked = AtomicBoolean(false)
        val queue = MiersRequestQueue(maxQueue = 2)

        try {
            assertTrue(queue.submit {
                started.countDown()
                running
            })
            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(queue.submit {
                waitingInvoked.set(true)
                CompletableFuture.completedFuture(Unit)
            })

            queue.close()
            queue.close()

            assertTrue(queue.isClosed)
            assertEquals(0, queue.size)
            assertTrue(running.isCancelled)
            assertFalse(waitingInvoked.get())
            assertFalse(queue.submit { CompletableFuture.completedFuture(Unit) })
        } finally {
            queue.close()
        }
    }

    @Test
    fun `max queue must be positive`() {
        assertFailsWith<IllegalArgumentException> { MiersRequestQueue(0) }
        assertFailsWith<IllegalArgumentException> { MiersRequestQueue(-1) }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("condition was not met within ${TIMEOUT_SECONDS}s")
            Thread.sleep(5)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}
