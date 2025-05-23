package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * The result of .limitedParallelism(x) call, a dispatcher
 * that wraps the given dispatcher, but limits the parallelism level, while
 * trying to emulate fairness.
 *
 * ### Implementation details
 *
 * By design, 'LimitedDispatcher' never [dispatches][CoroutineDispatcher.dispatch] originally sent tasks
 * to the underlying dispatcher. Instead, it maintains its own queue of tasks sent to this dispatcher and
 * dispatches at most [parallelism] "worker-loop" tasks that poll the underlying queue and cooperatively preempt
 * in order to avoid starvation of the underlying dispatcher.
 *
 * Such behavior is crucial to be compatible with any underlying dispatcher implementation without
 * direct cooperation.
 */
internal class LimitedDispatcher(
    private val dispatcher: CoroutineDispatcher,
    private val parallelism: Int,
    private val name: String?
) : CoroutineDispatcher(), Delay by (dispatcher as? Delay ?: DefaultDelay) {

    // Atomic is necessary here for the sake of K/N memory ordering,
    // there is no need in atomic operations for this property
    private val runningWorkers = atomic(0)

    private val queue = LockFreeTaskQueue<Runnable>(singleConsumer = false)

    // A separate object that we can synchronize on for K/N
    private val workerAllocationLock = SynchronizedObject()

    override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= this.parallelism) return namedOrThis(name)
        return super.limitedParallelism(parallelism, name)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchInternal(block) { worker ->
            dispatcher.safeDispatch(this, worker)
        }
    }

    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        dispatchInternal(block) { worker ->
            dispatcher.dispatchYield(this, worker)
        }
    }

    /**
     * Tries to dispatch the given [block].
     * If there are not enough workers, it starts a new one via [startWorker].
     */
    private inline fun dispatchInternal(block: Runnable, startWorker: (Worker) -> Unit) {
        // Add task to queue so running workers will be able to see that
        queue.addLast(block)
        if (runningWorkers.value >= parallelism) return
        // allocation may fail if some workers were launched in parallel or a worker temporarily decreased
        // `runningWorkers` when they observed an empty queue.
        if (!tryAllocateWorker()) return
        val task = obtainTaskOrDeallocateWorker() ?: return
        try {
            startWorker(Worker(task))
        } catch (e: Throwable) {
            /* If we failed to start a worker, we should decrement the counter.
            The queue is in an inconsistent state--it's non-empty despite the target parallelism not having been
            reached--but at least a properly functioning worker will have a chance to correct this if some future
            dispatch does succeed.
            If we don't decrement the counter, it will be impossible to ever reach the target parallelism again. */
            runningWorkers.decrementAndGet()
            throw e
        }
    }

    /**
     * Tries to obtain the permit to start a new worker.
     */
    private fun tryAllocateWorker(): Boolean {
        synchronized(workerAllocationLock) {
            if (runningWorkers.value >= parallelism) return false
            runningWorkers.incrementAndGet()
            return true
        }
    }

    /**
     * Obtains the next task from the queue, or logically deallocates the worker if the queue is empty.
     */
    private fun obtainTaskOrDeallocateWorker(): Runnable? {
        while (true) {
            when (val nextTask = queue.removeFirstOrNull()) {
                null -> synchronized(workerAllocationLock) {
                    runningWorkers.decrementAndGet()
                    if (queue.size == 0) return null
                    runningWorkers.incrementAndGet()
                }
                else -> return nextTask
            }
        }
    }

    override fun toString() = name ?: "$dispatcher.limitedParallelism($parallelism)"

    /**
     * A worker that polls the queue and runs tasks until there are no more of them.
     *
     * It always stores the next task to run. This is done in order to prevent the possibility of the fairness
     * re-dispatch happening when there are no more tasks in the queue. This is important because, after all the
     * actual tasks are done, nothing prevents the user from closing the dispatcher and making it incorrect to
     * perform any more dispatches.
     */
    private inner class Worker(private var currentTask: Runnable) : Runnable {
        override fun run() {
            try {
                var fairnessCounter = 0
                while (true) {
                    try {
                        currentTask.run()
                    } catch (e: Throwable) {
                        handleCoroutineException(EmptyCoroutineContext, e)
                    }
                    currentTask = obtainTaskOrDeallocateWorker() ?: return
                    // 16 is our out-of-thin-air constant to emulate fairness. Used in JS dispatchers as well
                    if (++fairnessCounter >= 16 && dispatcher.safeIsDispatchNeeded(this@LimitedDispatcher)) {
                        // Do "yield" to let other views execute their runnable as well
                        // Note that we do not decrement 'runningWorkers' as we are still committed to our part of work
                        dispatcher.safeDispatch(this@LimitedDispatcher, this)
                        return
                    }
                }
            } catch (e: Throwable) {
                // If the worker failed, we should deallocate its slot
                synchronized(workerAllocationLock) {
                    runningWorkers.decrementAndGet()
                }
                throw e
            }
        }
    }
}

internal fun Int.checkParallelism() = require(this >= 1) { "Expected positive parallelism level, but got $this" }

internal fun CoroutineDispatcher.namedOrThis(name: String?): CoroutineDispatcher {
    if (name != null) return NamedDispatcher(this, name)
    return this
}
