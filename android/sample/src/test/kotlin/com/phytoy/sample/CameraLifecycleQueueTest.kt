package com.phytoy.sample

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraLifecycleQueueTest {
    @Test
    fun cancellationAfterCreatePreventsPublicationWhileCleanupIsPending() {
        val releases = AtomicInteger()
        val owner = QueueOwnedResource<Any> { releases.incrementAndGet() }
        val created = Any()
        assertTrue(owner.install(created))
        assertTrue(owner.mayPublish(created))
        owner.cancel()
        assertFalse(owner.mayPublish(created))
        assertSame(created, owner.value)
        owner.clear()
        owner.clear()
        assertEquals(1, releases.get())
        assertNull(owner.value)
    }

    @Test(timeout = 10_000L)
    fun blockedEngineWorkDoesNotBlockSubmission() {
        val worker = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val failures = LinkedBlockingQueue<Throwable>()
        val queue = SerialLifecycleQueue(worker) { failures.add(it) }
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val nextRan = CountDownLatch(1)
        try {
            queue.execute { started.countDown(); unblock.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val submission = caller.submit<Boolean> {
                queue.execute { nextRan.countDown() }
                true
            }
            assertTrue(submission.get(2, TimeUnit.SECONDS))
            assertEquals(1L, nextRan.count)
            unblock.countDown()
            assertTrue(nextRan.await(2, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
        } finally {
            unblock.countDown()
            caller.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun oldGenerationFinishesCloseBeforeNextGenerationCreates() {
        val worker = Executors.newSingleThreadExecutor()
        val failures = LinkedBlockingQueue<Throwable>()
        val queue = SerialLifecycleQueue(worker) { failures.add(it) }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val closeStarted = CountDownLatch(1)
        val unblockClose = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val old = QueueOwnedResource<String> {
            events.add("old close started")
            closeStarted.countDown()
            unblockClose.await()
            events.add("old close finished")
        }
        val next = QueueOwnedResource<String> { }
        try {
            queue.execute { old.install("old engine"); old.clear() }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            queue.execute { next.install("new engine"); events.add("new engine created"); completed.countDown() }
            assertEquals(listOf("old close started"), events.toList())
            unblockClose.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("old close started", "old close finished", "new engine created"), events.toList())
            assertTrue(failures.isEmpty())
        } finally {
            unblockClose.countDown()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun cancelledInFlightCreateIsRejectedAndReleasedOnce() {
        val worker = Executors.newSingleThreadExecutor()
        val failures = LinkedBlockingQueue<Throwable>()
        val queue = SerialLifecycleQueue(worker) { failures.add(it) }
        val createStarted = CountDownLatch(1)
        val finishCreate = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val releases = AtomicInteger()
        val admitted = AtomicBoolean(false)
        val owner = QueueOwnedResource<String> { releases.incrementAndGet() }
        try {
            queue.execute {
                createStarted.countDown()
                finishCreate.await()
                admitted.set(owner.install("late engine"))
            }
            assertTrue(createStarted.await(2, TimeUnit.SECONDS))
            owner.cancel()
            queue.execute { owner.clear(); owner.clear(); disposed.countDown() }
            finishCreate.countDown()
            assertTrue(disposed.await(2, TimeUnit.SECONDS))
            assertFalse(admitted.get())
            assertTrue(owner.isCancelled)
            assertNull(owner.value)
            assertEquals(1, releases.get())
            assertTrue(failures.isEmpty())
        } finally {
            finishCreate.countDown()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 10_000L)
    fun fallbackReplacementAndRepeatedRetirementReleaseEachEngineOnce() {
        val worker = Executors.newSingleThreadExecutor()
        val failures = LinkedBlockingQueue<Throwable>()
        val queue = SerialLifecycleQueue(worker) { failures.add(it) }
        val released = Collections.synchronizedList(mutableListOf<String>())
        val completed = CountDownLatch(1)
        val owner = QueueOwnedResource<String> { released.add(it) }
        try {
            queue.execute {
                assertTrue(owner.install("rejected still size"))
                owner.clear()
                assertTrue(owner.install("accepted smaller still size"))
                owner.cancel()
                owner.clear()
                owner.clear()
                completed.countDown()
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("rejected still size", "accepted smaller still size"), released.toList())
            assertTrue(failures.isEmpty())
        } finally {
            worker.shutdownNow()
        }
    }
}
