@file:JvmName("TestBuildersKt")
@file:JvmMultifileClass

package kotlinx.coroutines.test

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A test result.
 *
 * - On JVM and Native, this resolves to [Unit], representing the fact that tests are run in a blocking manner on these
 *   platforms: a call to a function returning a [TestResult] will simply execute the test inside it.
 * - On JS, this is a `Promise`, which reflects the fact that the test-running function does not wait for a test to
 *   finish. The JS test frameworks typically support returning `Promise` from a test and will correctly handle it.
 *
 * Because of the behavior on JS, extra care must be taken when writing multiplatform tests to avoid losing test errors:
 * - Don't do anything after running the functions returning a [TestResult]. On JS, this code will execute *before* the
 *   test finishes.
 * - As a corollary, don't run functions returning a [TestResult] more than once per test. The only valid thing to do
 *   with a [TestResult] is to immediately `return` it from a test.
 * - Don't nest functions returning a [TestResult].
 */
public expect class TestResult

/**
 * Executes [testBody] as a test in a new coroutine, returning [TestResult].
 *
 * On JVM and Native, this function behaves similarly to `runBlocking`, with the difference that the code that it runs
 * will skip delays. This allows to use [delay] in tests without causing them to take more time than necessary.
 * On JS, this function creates a `Promise` that executes the test body with the delay-skipping behavior.
 *
 * ```
 * @Test
 * fun exampleTest() = runTest {
 *     val deferred = async {
 *         delay(1.seconds)
 *         async {
 *             delay(1.seconds)
 *         }.await()
 *     }
 *
 *     deferred.await() // result available immediately
 * }
 * ```
 *
 * The platform difference entails that, in order to use this function correctly in common code, one must always
 * immediately return the produced [TestResult] from the test method, without doing anything else afterwards. See
 * [TestResult] for details on this.
 *
 * The test is run on a single thread, unless other [CoroutineDispatcher] are used for child coroutines.
 * Because of this, child coroutines are not executed in parallel to the test body.
 * In order for the spawned-off asynchronous code to actually be executed, one must either [yield] or suspend the
 * test body some other way, or use commands that control scheduling (see [TestCoroutineScheduler]).
 *
 * ```
 * @Test
 * fun exampleWaitingForAsyncTasks1() = runTest {
 *     // 1
 *     val job = launch {
 *         // 3
 *     }
 *     // 2
 *     job.join() // the main test coroutine suspends here, so the child is executed
 *     // 4
 *     // use the results here
 * }
 *
 * @Test
 * fun exampleWaitingForAsyncTasks2() = runTest {
 *     // 1
 *     launch {
 *         // 3
 *     }
 *     // 2
 *     testScheduler.advanceUntilIdle() // runs the tasks until their queue is empty
 *     // 4
 *     // use the results here
 * }
 * ```
 *
 *
 * If the results of children coroutines computations are not needed in the runTest scope,
 * there is no need to wait for children coroutines to finish, they are awaited for automatically
 * by runTest parent coroutine.
 * ```
 * @Test
 * fun exampleWaitingForAsyncTasks3() = runTest {
 *     val x = 0
 *     val y = 1
 *     // 1
 *     launch {
 *         // 3
 *         assertEquals(0, x)
 *     }
 *     launch {
 *         // 4
 *         assertEquals(1, y)
 *     }
 *     // 2
 * }  // 5
 * ```
 *
 * ### Task scheduling
 *
 * Delay skipping is achieved by using virtual time.
 * If [Dispatchers.Main] is set to a [TestDispatcher] via [Dispatchers.setMain] before the test,
 * then its [TestCoroutineScheduler] is used;
 * otherwise, a new one is automatically created (or taken from [context] in some way) and can be used to control
 * the virtual time, advancing it, running the tasks scheduled at a specific time etc.
 * The scheduler can be accessed via [TestScope.testScheduler].
 *
 * Delays in code that runs inside dispatchers that don't use a [TestCoroutineScheduler] don't get skipped:
 * ```
 * @Test
 * fun exampleTest() = runTest {
 *     val elapsed = TimeSource.Monotonic.measureTime {
 *         val deferred = async {
 *             delay(1.seconds) // will be skipped
 *             withContext(Dispatchers.Default) {
 *                 delay(5.seconds) // Dispatchers.Default doesn't know about TestCoroutineScheduler
 *             }
 *         }
 *         deferred.await()
 *     }
 *     println(elapsed) // about five seconds
 * }
 * ```
 *
 * ### Failures
 *
 * #### Test body failures
 *
 * If the created coroutine completes with an exception, then this exception will be thrown at the end of the test.
 *
 * #### Timing out
 *
 * There's a built-in timeout of 60 seconds for the test body. If the test body doesn't complete within this time,
 * then the test fails with an [AssertionError]. The timeout can be changed for each test separately by setting the
 * [timeout] parameter.
 *
 * Additionally, setting the `kotlinx.coroutines.test.default_timeout` system property on the
 * JVM to any string that can be parsed using [Duration.parse] (like `1m`, `30s` or `1500ms`) will change the default
 * timeout to that value for all tests whose [timeout] is not set explicitly; setting it to anything else will throw an
 * exception every time [runTest] is invoked.
 *
 * On timeout, the test body is cancelled so that the test finishes. If the code inside the test body does not
 * respond to cancellation, the timeout will not be able to make the test execution stop.
 * In that case, the test will hang despite the attempt to terminate it.
 *
 * On the JVM, if `DebugProbes` from the `kotlinx-coroutines-debug` module are installed, the current dump of the
 * coroutines' stack is printed to the console on timeout before the test body is cancelled.
 *
 * #### Reported exceptions
 *
 * Unhandled exceptions will be thrown at the end of the test.
 * If uncaught exceptions happen after the test finishes, they are propagated in a platform-specific manner:
 * see [handleCoroutineException] for details.
 * If the test coroutine completes with an exception, the unhandled exceptions are suppressed by it.
 *
 * #### Uncompleted coroutines
 *
 * Otherwise, the test will hang until all the coroutines launched inside [testBody] complete.
 * This may be an issue when there are some coroutines that are not supposed to complete, like infinite loops that
 * perform some background work and are supposed to outlive the test.
 * In that case, [TestScope.backgroundScope] can be used to launch such coroutines.
 * They will be cancelled automatically when the test finishes.
 *
 * ### Configuration
 *
 * [context] can be used to affect the environment of the code under test. Beside just being passed to the coroutine
 * scope created for the test, [context] also can be used to change how the test is executed.
 * See the [TestScope] constructor function documentation for details.
 *
 * @throws IllegalArgumentException if the [context] is invalid. See the [TestScope] constructor docs for details.
 */
public fun runTest(
    context: CoroutineContext = EmptyCoroutineContext,
    timeout: Duration = DEFAULT_TIMEOUT.getOrThrow(),
    testBody: suspend TestScope.() -> Unit
): TestResult {
    check(context[RunningInRunTest] == null) {
        "Calls to `runTest` can't be nested. Please read the docs on `TestResult` for details."
    }
    return TestScope(context + RunningInRunTest).runTest(timeout, testBody)
}

/**
 * Executes [testBody] as a test in a new coroutine, returning [TestResult].
 *
 * On JVM and Native, this function behaves similarly to `runBlocking`, with the difference that the code that it runs
 * will skip delays. This allows to use [delay] in without causing the tests to take more time than necessary.
 * On JS, this function creates a `Promise` that executes the test body with the delay-skipping behavior.
 *
 * ```
 * @Test
 * fun exampleTest() = runTest {
 *     val deferred = async {
 *         delay(1.seconds)
 *         async {
 *             delay(1.seconds)
 *         }.await()
 *     }
 *
 *     deferred.await() // result available immediately
 * }
 * ```
 *
 * The platform difference entails that, in order to use this function correctly in common code, one must always
 * immediately return the produced [TestResult] from the test method, without doing anything else afterwards. See
 * [TestResult] for details on this.
 *
 * The test is run in a single thread, unless other [CoroutineDispatcher] are used for child coroutines.
 * Because of this, child coroutines are not executed in parallel to the test body.
 * In order for the spawned-off asynchronous code to actually be executed, one must either [yield] or suspend the
 * test body some other way, or use commands that control scheduling (see [TestCoroutineScheduler]).
 *
 * ```
 * @Test
 * fun exampleWaitingForAsyncTasks1() = runTest {
 *     // 1
 *     val job = launch {
 *         // 3
 *     }
 *     // 2
 *     job.join() // the main test coroutine suspends here, so the child is executed
 *     // 4
 * }
 *
 * @Test
 * fun exampleWaitingForAsyncTasks2() = runTest {
 *     // 1
 *     launch {
 *         // 3
 *     }
 *     // 2
 *     advanceUntilIdle() // runs the tasks until their queue is empty
 *     // 4
 * }
 * ```
 *
 * ### Task scheduling
 *
 * Delay-skipping is achieved by using virtual time.
 * If [Dispatchers.Main] is set to a [TestDispatcher] via [Dispatchers.setMain] before the test,
 * then its [TestCoroutineScheduler] is used;
 * otherwise, a new one is automatically created (or taken from [context] in some way) and can be used to control
 * the virtual time, advancing it, running the tasks scheduled at a specific time etc.
 * Some convenience methods are available on [TestScope] to control the scheduler.
 *
 * Delays in code that runs inside dispatchers that don't use a [TestCoroutineScheduler] don't get skipped:
 * ```
 * @Test
 * fun exampleTest() = runTest {
 *     val elapsed = TimeSource.Monotonic.measureTime {
 *         val deferred = async {
 *             delay(1.seconds) // will be skipped
 *             withContext(Dispatchers.Default) {
 *                 delay(5.seconds) // Dispatchers.Default doesn't know about TestCoroutineScheduler
 *             }
 *         }
 *         deferred.await()
 *     }
 *     println(elapsed) // about five seconds
 * }
 * ```
 *
 * ### Failures
 *
 * #### Test body failures
 *
 * If the created coroutine completes with an exception, then this exception will be thrown at the end of the test.
 *
 * #### Reported exceptions
 *
 * Unhandled exceptions will be thrown at the end of the test.
 * If the uncaught exceptions happen after the test finishes, the error is propagated in a platform-specific manner.
 * If the test coroutine completes with an exception, the unhandled exceptions are suppressed by it.
 *
 * #### Uncompleted coroutines
 *
 * This method requires that, after the test coroutine has completed, all the other coroutines launched inside
 * [testBody] also complete, or are cancelled.
 * Otherwise, the test will be failed (which, on JVM and Native, means that [runTest] itself will throw
 * [AssertionError], whereas on JS, the `Promise` will fail with it).
 *
 * In the general case, if there are active jobs, it's impossible to detect if they are going to complete eventually due
 * to the asynchronous nature of coroutines. In order to prevent tests hanging in this scenario, [runTest] will wait
 * for [dispatchTimeoutMs] from the moment when [TestCoroutineScheduler] becomes
 * idle before throwing [AssertionError]. If some dispatcher linked to [TestCoroutineScheduler] receives a
 * task during that time, the timer gets reset.
 *
 * ### Configuration
 *
 * [context] can be used to affect the environment of the code under test. Beside just being passed to the coroutine
 * scope created for the test, [context] also can be used to change how the test is executed.
 * See the [TestScope] constructor function documentation for details.
 *
 * @throws IllegalArgumentException if the [context] is invalid. See the [TestScope] constructor docs for details.
 */
@Deprecated(
    "Define a total timeout for the whole test instead of using dispatchTimeoutMs. " +
        "Warning: the proposed replacement is not identical as it uses 'dispatchTimeoutMs' as the timeout for the whole test!",
    ReplaceWith("runTest(context, timeout = dispatchTimeoutMs.milliseconds, testBody)",
        "kotlin.time.Duration.Companion.milliseconds"),
    DeprecationLevel.WARNING
) // Warning since 1.7.0, was experimental in 1.6.x
public fun runTest(
    context: CoroutineContext = EmptyCoroutineContext,
    dispatchTimeoutMs: Long,
    testBody: suspend TestScope.() -> Unit
): TestResult {
    if (context[RunningInRunTest] != null)
        throw IllegalStateException("Calls to `runTest` can't be nested. Please read the docs on `TestResult` for details.")
    @Suppress("DEPRECATION")
    return TestScope(context + RunningInRunTest).runTest(dispatchTimeoutMs = dispatchTimeoutMs, testBody)
}

/**
 * Performs [runTest] on an existing [TestScope]. See the documentation for [runTest] for details.
 */
public fun TestScope.runTest(
    timeout: Duration = DEFAULT_TIMEOUT.getOrThrow(),
    testBody: suspend TestScope.() -> Unit
): TestResult = asSpecificImplementation().let { scope ->
    scope.enter()
    createTestResult {
        val testBodyFinished = AtomicBoolean(false)
        /** TODO: moving this [AbstractCoroutine.start] call outside [createTestResult] fails on JS. */
        scope.start(CoroutineStart.UNDISPATCHED, scope) {
            /* we're using `UNDISPATCHED` to avoid the event loop, but we do want to set up the timeout machinery
            before any code executes, so we have to park here. */
            yield()
            try {
                testBody()
            } finally {
                testBodyFinished.value = true
            }
        }
        var timeoutError: Throwable? = null
        var cancellationException: CancellationException? = null
        val workRunner = launch(CoroutineName("kotlinx.coroutines.test runner")) {
            while (true) {
                val executedSomething = testScheduler.tryRunNextTaskUnless { !isActive }
                if (executedSomething) {
                    /** yield to check for cancellation. On JS, we can't use [ensureActive] here, as the cancellation
                     * procedure needs a chance to run concurrently. */
                    yield()
                } else {
                    // waiting for the next task to be scheduled, or for the test runner to be cancelled
                    testScheduler.receiveDispatchEvent()
                }
            }
        }
        try {
            withTimeout(timeout) {
                coroutineContext.job.invokeOnCompletion(onCancelling = true) { exception ->
                    if (exception is TimeoutCancellationException) {
                        dumpCoroutines()
                        val activeChildren = scope.children.filter(Job::isActive).toList()
                        val message = "After waiting for $timeout, " + when {
                            testBodyFinished.value && activeChildren.isNotEmpty() ->
                                "there were active child jobs: $activeChildren. " +
                                    "Use `TestScope.backgroundScope` " +
                                    "to launch the coroutines that need to be cancelled when the test body finishes"
                            testBodyFinished.value ->
                                "the test completed, but only after the timeout"
                            else ->
                                "the test body did not run to completion"
                        }
                        timeoutError = UncompletedCoroutinesError(message)
                        cancellationException = CancellationException("The test timed out")
                        (scope as Job).cancel(cancellationException!!)
                    }
                }
                scope.join()
                workRunner.cancelAndJoin()
            }
        } catch (_: TimeoutCancellationException) {
            scope.join()
            val completion = scope.getCompletionExceptionOrNull()
            if (completion != null && completion !== cancellationException) {
                timeoutError!!.addSuppressed(completion)
            }
            workRunner.cancelAndJoin()
        } finally {
            backgroundScope.cancel()
            testScheduler.advanceUntilIdleOr { false }
            val uncaughtExceptions = scope.leave()
            throwAll(timeoutError ?: scope.getCompletionExceptionOrNull(), uncaughtExceptions)
        }
    }
}

/**
 * Performs [runTest] on an existing [TestScope].
 *
 * In the general case, if there are active jobs, it's impossible to detect if they are going to complete eventually due
 * to the asynchronous nature of coroutines. In order to prevent tests hanging in this scenario, [runTest] will wait
 * for [dispatchTimeoutMs] from the moment when [TestCoroutineScheduler] becomes
 * idle before throwing [AssertionError]. If some dispatcher linked to [TestCoroutineScheduler] receives a
 * task during that time, the timer gets reset.
 */
@Deprecated(
    "Define a total timeout for the whole test instead of using dispatchTimeoutMs. " +
        "Warning: the proposed replacement is not identical as it uses 'dispatchTimeoutMs' as the timeout for the whole test!",
    ReplaceWith("this.runTest(timeout = dispatchTimeoutMs.milliseconds, testBody)",
        "kotlin.time.Duration.Companion.milliseconds"),
    DeprecationLevel.WARNING
) // Warning since 1.7.0, was experimental in 1.6.x
public fun TestScope.runTest(
    dispatchTimeoutMs: Long,
    testBody: suspend TestScope.() -> Unit
): TestResult = asSpecificImplementation().let {
    it.enter()
    @Suppress("DEPRECATION")
    createTestResult {
        runTestCoroutineLegacy(it, dispatchTimeoutMs.milliseconds, TestScopeImpl::tryGetCompletionCause, testBody) {
            backgroundScope.cancel()
            testScheduler.advanceUntilIdleOr { false }
            it.legacyLeave()
        }
    }
}

/**
 * Runs [testProcedure], creating a [TestResult].
 */
internal expect fun createTestResult(testProcedure: suspend CoroutineScope.() -> Unit): TestResult

/** A coroutine context element indicating that the coroutine is running inside `runTest`. */
internal object RunningInRunTest : CoroutineContext.Key<RunningInRunTest>, CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = this

    override fun toString(): String = "RunningInRunTest"
}

/** The default timeout to use when waiting for asynchronous completions of the coroutines managed by
 * a [TestCoroutineScheduler]. */
internal const val DEFAULT_DISPATCH_TIMEOUT_MS = 60_000L

/**
 * The default timeout to use when running a test.
 *
 * It's not just a [Duration] but a [Result] so that every access to [runTest]
 * throws the same clear exception if parsing the environment variable failed.
 * Otherwise, the parsing error would only be thrown in one tests, while the
 * other ones would get an incomprehensible `NoClassDefFoundError`.
 */
private val DEFAULT_TIMEOUT: Result<Duration> = runCatching {
    systemProperty("kotlinx.coroutines.test.default_timeout", Duration::parse, 60.seconds)
}

/**
 * Run the [body][testBody] of the [test coroutine][coroutine], waiting for asynchronous completions for at most
 * [dispatchTimeout] and performing the [cleanup] procedure at the end.
 *
 * [tryGetCompletionCause] is the [JobSupport.completionCause], which is passed explicitly because it is protected.
 *
 * The [cleanup] procedure may either throw [UncompletedCoroutinesError] to denote that child coroutines were leaked, or
 * return a list of uncaught exceptions that should be reported at the end of the test.
 */
@Deprecated("Used for support of legacy behavior")
internal suspend fun <T : AbstractCoroutine<Unit>> CoroutineScope.runTestCoroutineLegacy(
    coroutine: T,
    dispatchTimeout: Duration,
    tryGetCompletionCause: T.() -> Throwable?,
    testBody: suspend T.() -> Unit,
    cleanup: () -> List<Throwable>,
) {
    val scheduler = coroutine.coroutineContext[TestCoroutineScheduler]!!
    /** TODO: moving this [AbstractCoroutine.start] call outside [createTestResult] fails on JS. */
    coroutine.start(CoroutineStart.UNDISPATCHED, coroutine) {
        testBody()
    }
    /**
     * This is the legacy behavior, kept for now for compatibility only.
     *
     * The general procedure here is as follows:
     * 1. Try running the work that the scheduler knows about, both background and foreground.
     *
     * 2. Wait until we run out of foreground work to do. This could mean one of the following:
     *    - The main coroutine is already completed. This is checked separately; then we leave the procedure.
     *    - It's switched to another dispatcher that doesn't know about the [TestCoroutineScheduler].
     *    - Generally, it's waiting for something external (like a network request, or just an arbitrary callback).
     *    - The test simply hanged.
     *    - The main coroutine is waiting for some background work.
     *
     * 3. We await progress from things that are not the code under test:
     *    the background work that the scheduler knows about, the external callbacks,
     *    the work on dispatchers not linked to the scheduler, etc.
     *
     *    When we observe that the code under test can proceed, we go to step 1 again.
     *    If there is no activity for [dispatchTimeoutMs] milliseconds, we consider the test to have hanged.
     *
     *    The background work is not running on a dedicated thread.
     *    Instead, the test thread itself is used, by spawning a separate coroutine.
     */
    var completed = false
    while (!completed) {
        scheduler.advanceUntilIdle()
        if (coroutine.isCompleted) {
            /* don't even enter `withTimeout`; this allows to use a timeout of zero to check that there are no
           non-trivial dispatches. */
            completed = true
            continue
        }
        // in case progress depends on some background work, we need to keep spinning it.
        val backgroundWorkRunner = launch(CoroutineName("background work runner")) {
            while (true) {
                val executedSomething = scheduler.tryRunNextTaskUnless { !isActive }
                if (executedSomething) {
                    // yield so that the `select` below has a chance to finish successfully or time out
                    yield()
                } else {
                    // no more tasks, we should suspend until there are some more.
                    // this doesn't interfere with the `select` below, because different channels are used.
                    scheduler.receiveDispatchEvent()
                }
            }
        }
        try {
            select<Unit> {
                coroutine.onJoin {
                    // observe that someone completed the test coroutine and leave without waiting for the timeout
                    completed = true
                }
                scheduler.onDispatchEventForeground {
                    // we received knowledge that `scheduler` observed a dispatch event, so we reset the timeout
                }
                onTimeout(dispatchTimeout) {
                    throw handleTimeout(coroutine, dispatchTimeout, tryGetCompletionCause, cleanup)
                }
            }
        } finally {
            backgroundWorkRunner.cancelAndJoin()
        }
    }
    coroutine.getCompletionExceptionOrNull()?.let { exception ->
        val exceptions = try {
            cleanup()
        } catch (e: UncompletedCoroutinesError) {
            // it's normal that some jobs are not completed if the test body has failed, won't clutter the output
            emptyList()
        }
        throwAll(exception, exceptions)
    }
    throwAll(null, cleanup())
}

/**
 * Invoked on timeout in [runTest]. Just builds a nice [UncompletedCoroutinesError] and returns it.
 */
private inline fun <T : AbstractCoroutine<Unit>> handleTimeout(
    coroutine: T,
    dispatchTimeout: Duration,
    tryGetCompletionCause: T.() -> Throwable?,
    cleanup: () -> List<Throwable>,
): AssertionError {
    val uncaughtExceptions = try {
        cleanup()
    } catch (e: UncompletedCoroutinesError) {
        // we expect these and will instead throw a more informative exception.
        emptyList()
    }
    val activeChildren = coroutine.children.filter { it.isActive }.toList()
    val completionCause = if (coroutine.isCancelled) coroutine.tryGetCompletionCause() else null
    var message = "After waiting for $dispatchTimeout"
    if (completionCause == null)
        message += ", the test coroutine is not completing"
    if (activeChildren.isNotEmpty())
        message += ", there were active child jobs: $activeChildren"
    if (completionCause != null && activeChildren.isEmpty()) {
        message += if (coroutine.isCompleted)
            ", the test coroutine completed"
        else
            ", the test coroutine was not completed"
    }
    val error = UncompletedCoroutinesError(message)
    completionCause?.let { cause -> error.addSuppressed(cause) }
    uncaughtExceptions.forEach { error.addSuppressed(it) }
    return error
}

internal fun throwAll(head: Throwable?, other: List<Throwable>) {
    if (head != null) {
        other.forEach { head.addSuppressed(it) }
        throw head
    } else {
        with(other) {
            firstOrNull()?.apply {
                drop(1).forEach { addSuppressed(it) }
                throw this
            }
        }
    }
}

internal expect fun dumpCoroutines()

private fun <T: Any> systemProperty(
    name: String,
    parse: (String) -> T,
    default: T,
): T {
    val value = systemPropertyImpl(name) ?: return default
    return parse(value)
}

internal expect fun systemPropertyImpl(name: String): String?

@Deprecated(
    "This is for binary compatibility with the `runTest` overload that existed at some point",
    level = DeprecationLevel.HIDDEN
)
@JvmName("runTest\$default")
@Suppress("DEPRECATION", "UNUSED_PARAMETER")
public fun TestScope.runTestLegacy(
    dispatchTimeoutMs: Long,
    testBody: suspend TestScope.() -> Unit,
    marker: Int,
    unused2: Any?,
): TestResult = runTest(dispatchTimeoutMs = if (marker and 1 != 0) dispatchTimeoutMs else 60_000L, testBody)

// Remove after https://youtrack.jetbrains.com/issue/KT-62423/
private class AtomicBoolean(initial: Boolean) {
    private val container = atomic(initial)
    var value: Boolean
        get() = container.value
        set(value: Boolean) { container.value = value }
}
