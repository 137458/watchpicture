package com.watchpicture.app.archive

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
