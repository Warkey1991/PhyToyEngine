package com.phytoy.sample

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Submission returns immediately even while a preceding lifecycle task is blocked. */
internal class SerialLifecycleQueue(
    private val executor: Executor,
    private val onFailure: (Throwable) -> Unit,
) {
    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }
}

/** Main may cancel; installation/release belongs exclusively to the serial queue. */
internal class QueueOwnedResource<T : Any>(private val release: (T) -> Unit) {
    @Volatile private var cancelled = false
    @Volatile var value: T? = null
        private set

    val isCancelled: Boolean get() = cancelled

    fun cancel() { cancelled = true }

    fun mayPublish(resource: T): Boolean = !cancelled && value === resource

    fun install(resource: T): Boolean {
        check(value == null) { "Release the previous queued resource before replacement" }
        if (cancelled) {
            release(resource)
            return false
        }
        value = resource
        return true
    }

    fun clear() {
        val previous = value
        value = null
        if (previous != null) release(previous)
    }
}

/** Keep Vulkan creation/destruction ordered across Activity recreation. */
internal object CameraLifecycleQueue {
    private val queue = SerialLifecycleQueue(Executors.newSingleThreadExecutor { task ->
        Thread(task, "PhyToyLifecycle")
    }) { error -> Log.e("PhyToySample", "Camera lifecycle operation failed", error) }

    fun execute(task: () -> Unit) = queue.execute(task)
}
