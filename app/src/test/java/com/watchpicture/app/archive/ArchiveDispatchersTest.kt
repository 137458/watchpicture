package com.watchpicture.app.archive

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArchiveDispatchersTest {

    @Test
    fun `decompressDispatcher executes tasks on named worker threads`() = runBlocking {
        var workerThreadName = ""
        withContext(ArchiveDispatchers.decompressDispatcher) {
            workerThreadName = Thread.currentThread().name
        }

        assertTrue(
            "Worker thread name should start with 'watchpic-decompress-', was: $workerThreadName",
            workerThreadName.startsWith("watchpic-decompress-")
        )
    }

    @Test
    fun `backgroundSweepDispatcher executes tasks on named sweep worker threads`() = runBlocking {
        var workerThreadName = ""
        withContext(ArchiveDispatchers.backgroundSweepDispatcher) {
            workerThreadName = Thread.currentThread().name
        }

        assertTrue(
            "Worker thread name should start with 'watchpic-sweep-', was: $workerThreadName",
            workerThreadName.startsWith("watchpic-sweep-")
        )
    }

    @Test
    fun `maintenanceScope isolates child failure so siblings still complete`() {
        val siblingDone = CountDownLatch(1)
        var siblingResult: String? = null

        ArchiveDispatchers.maintenanceScope.launch(ArchiveDispatchers.decompressDispatcher) {
            throw IllegalStateException("intentional failure for isolation test")
        }
        ArchiveDispatchers.maintenanceScope.launch(ArchiveDispatchers.decompressDispatcher) {
            // Give the failing sibling time to hit its exception handler first; under a
            // plain (non-supervisor) Job this coroutine would already be cancelled here.
            delay(300)
            siblingResult = "completed"
            siblingDone.countDown()
        }

        assertTrue(
            "Sibling coroutine must survive a failed sibling (SupervisorJob isolation)",
            siblingDone.await(10, TimeUnit.SECONDS)
        )
        assertEquals("completed", siblingResult)
    }
}
