package com.mieai.qqbot.plugin.miers

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A binding-local, FIFO request queue with one daemon worker.
 *
 * The capacity includes the task currently being processed and all tasks waiting
 * in the deque. A task keeps its slot until its returned completion stage finishes.
 */
class MiersRequestQueue(
    val maxQueue: Int,
    private val failureHandler: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private val pending = ArrayDeque<QueuedTask>()
    private val worker: Thread

    @Volatile
    private var closed = false
    private var active: ActiveTask? = null

    init {
        require(maxQueue > 0) { "maxQueue must be positive" }
        worker = Thread(::runWorker, "miers-request-queue").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
            start()
        }
    }

    /** Number of slots currently occupied by running and waiting tasks. */
    val size: Int
        get() = stateLock.withLock {
            pending.size + if (active != null) 1 else 0
        }

    /** True after close has begun and no further tasks can be submitted. */
    val isClosed: Boolean
        get() = closed

    /**
     * Enqueues [task] without waiting for capacity. Returns false when the queue
     * is full or has been closed. The task is invoked only by the worker thread.
     */
    fun submit(task: () -> CompletionStage<*>): Boolean {
        stateLock.withLock {
            if (closed || pending.size + (if (active != null) 1 else 0) >= maxQueue) return false
            pending.addLast(QueuedTask(task))
            stateChanged.signalAll()
            return true
        }
    }

    override fun close() {
        val future: CompletableFuture<*>?
        stateLock.withLock {
            if (closed) return
            closed = true
            pending.clear()
            future = active?.future
            active = null
            worker.interrupt()
            stateChanged.signalAll()
        }

        cancel(future)
        if (Thread.currentThread() !== worker) {
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runWorker() {
        try {
            while (true) {
                val next = stateLock.withLock {
                    while (pending.isEmpty() && !closed) stateChanged.await()
                    if (closed) {
                        null
                    } else {
                        ActiveTask(pending.removeFirst()).also { active = it }
                    }
                } ?: return

                runTask(next)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!isClosed) report(interrupted)
        } catch (failure: Throwable) {
            report(failure)
        } finally {
            val future = stateLock.withLock {
                closed = true
                pending.clear()
                active?.future.also { active = null }
            }
            cancel(future)
        }
    }

    private fun runTask(task: ActiveTask) {
        try {
            val stage = task.action()
            val future = try {
                stage.toCompletableFuture()
            } catch (failure: Throwable) {
                report(failure)
                return
            }
            task.future = future

            if (isClosed) {
                cancel(future)
                return
            }

            try {
                future.get()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!isClosed) report(interrupted)
            } catch (failure: CancellationException) {
                if (!isClosed) report(failure)
            } catch (failure: ExecutionException) {
                report(failure.cause ?: failure)
            } catch (failure: CompletionException) {
                report(failure.cause ?: failure)
            } catch (failure: Throwable) {
                report(failure)
            }
        } catch (failure: Throwable) {
            report(failure)
        } finally {
            stateLock.withLock {
                if (active === task) active = null
                stateChanged.signalAll()
            }
        }
    }

    private fun cancel(future: CompletableFuture<*>?) {
        if (future == null) return
        try {
            future.cancel(true)
        } catch (failure: Throwable) {
            report(failure)
        }
    }

    private fun report(failure: Throwable) {
        try {
            failureHandler(failure)
        } catch (_: Throwable) {
            // A logging callback must never terminate the queue worker.
        }
    }

    private class QueuedTask(val action: () -> CompletionStage<*>)

    private class ActiveTask(queued: QueuedTask) {
        val action: () -> CompletionStage<*> = queued.action

        @Volatile
        var future: CompletableFuture<*>? = null
    }

    private companion object {
        const val CLOSE_JOIN_MILLIS = 1_000L
    }
}
