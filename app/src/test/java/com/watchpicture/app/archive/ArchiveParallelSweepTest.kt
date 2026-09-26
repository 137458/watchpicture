package com.watchpicture.app.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1 条目级并行提取：ZIP 条目流各自持有独立句柄（native ZipFile 由 libcore 对共享 RAF
 * 串行化读，AES/ZipCrypto 路径每流独享 RandomAccessFile），缓存层为条纹锁原子写，
 * 可安全并行批量提取。7z 固实包仍保持单线程顺序扫描，不在本测试范围。
 */
class ArchiveParallelSweepTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun entryBytes(index: Int): ByteArray =
        "page-$index-content-${(0 until 48).joinToString("") { "-" }}".toByteArray()

    private fun createZip(names: List<String>, password: String? = null): File {
        val target = tempFolder.newFile("pack-${System.nanoTime()}.zip")
        ZipFile(target).use { zip ->
            if (password != null) zip.setPassword(password.toCharArray())
            for ((index, name) in names.withIndex()) {
                val params = ZipParameters()
                params.fileNameInZip = name
                if (password != null) {
                    params.isEncryptFiles = true
                    params.encryptionMethod = EncryptionMethod.AES
                    params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
                zip.addStream(ByteArrayInputStream(entryBytes(index)), params)
            }
        }
        return target
    }

    private class SweepContext(
        val coordinator: ArchiveExtractionCoordinator,
        val thumbCache: ThumbnailDiskCache,
        val power: PowerThermalManager,
        val zip: File,
        val names: List<String>
    )

    private fun buildSweepContext(
        entryCount: Int,
        withExtraEntry: Boolean = false,
        password: String? = null
    ): SweepContext {
        val diskCache = ArchiveDiskCache(tempFolder.newFolder("archive_cache_${System.nanoTime()}"))
        val thumbCache = ThumbnailDiskCache(
            tempFolder.newFolder("thumb_cache_${System.nanoTime()}"),
            maxSizeBytes = 64 * 1024 * 1024
        )
        val power = PowerThermalManager()
        val coordinator = ArchiveExtractionCoordinator(ZipArchiveManager(), diskCache, power)
        val names = (0 until entryCount).map { "img/page%02d.jpg".format(it) }
        val allNames = names + if (withExtraEntry) listOf("img/extra.jpg") else emptyList()
        val zip = createZip(allNames, password)
        return SweepContext(coordinator, thumbCache, power, zip, names)
    }

    @Test
    fun `并行批量处理达到预期并发度且处理全部条目`() = runBlocking {
        val processed = Collections.synchronizedList(mutableListOf<Int>())
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val items = (0 until 32).toList()

        runParallelBatch(items, workerCount = 4, dispatcher = Dispatchers.Default) { item ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { prev -> maxOf(prev, now) }
            delay(20)
            processed.add(item)
            inFlight.decrementAndGet()
        }

        assertEquals(items.size, processed.size)
        assertEquals(items.toSet(), processed.toSet())
        assertTrue("期望实际并发 ≥2，实测 $maxInFlight", maxInFlight.get() >= 2)
    }

    @Test
    fun `并行批量处理取消后不再处理剩余条目`() = runBlocking {
        val processed = AtomicInteger(0)
        val job = launch {
            runParallelBatch((0 until 200).toList(), workerCount = 2, dispatcher = Dispatchers.Default) {
                delay(10)
                processed.incrementAndGet()
            }
        }
        delay(120)
        job.cancelAndJoin()
        assertTrue("取消后应停止分发剩余条目，实际已处理 ${processed.get()}", processed.get() < 200)
    }

    @Test
    fun `ZIP 批量缩略图扫描完成全部条目且字节正确`() = runBlocking {
        val context = buildSweepContext(entryCount = 8)

        context.coordinator.startBatchThumbnailSweep(
            file = context.zip,
            entryNames = context.names,
            targetSizePx = 360,
            password = null,
            thumbnailDiskCache = context.thumbCache
        )

        for ((index, name) in context.names.withIndex()) {
            val thumb = context.thumbCache.get(context.zip, name, 360, password = null)
            assertNotNull("缺少缩略图 $name", thumb)
            // JVM 上 BitmapFactory 为 stub，缩略图缓存回退为原始字节镜像
            assertArrayEquals(entryBytes(index), thumb!!.readBytes())
        }
    }

    @Test
    fun `加密 ZIP 批量扫描并行完成全部条目`() = runBlocking {
        val password = "sweep-pw"
        val context = buildSweepContext(entryCount = 6, password = password)

        context.coordinator.startBatchThumbnailSweep(
            file = context.zip,
            entryNames = context.names,
            targetSizePx = 360,
            password = password,
            thumbnailDiskCache = context.thumbCache
        )

        for ((index, name) in context.names.withIndex()) {
            val thumb = context.thumbCache.get(context.zip, name, 360, password = password)
            assertNotNull("缺少缩略图 $name", thumb)
            assertArrayEquals(entryBytes(index), thumb!!.readBytes())
        }
    }

    @Test
    fun `扫描失败隔离：单条目缺失不影响其余条目`() = runBlocking {
        val context = buildSweepContext(entryCount = 4)
        val brokenTargets = context.names + listOf("img/missing.jpg")

        context.coordinator.startBatchThumbnailSweep(
            file = context.zip,
            entryNames = brokenTargets,
            targetSizePx = 360,
            password = null,
            thumbnailDiskCache = context.thumbCache
        )

        for ((index, name) in context.names.withIndex()) {
            val thumb = context.thumbCache.get(context.zip, name, 360, password = null)
            assertNotNull("缺失条目应被隔离，$name 不应受影响", thumb)
            assertArrayEquals(entryBytes(index), thumb!!.readBytes())
        }
    }

    @Test
    fun `热节流时跳过 ZIP 批量扫描`() = runBlocking {
        val context = buildSweepContext(entryCount = 4)
        context.power.setManualThrottleLevel(PowerThermalManager.ThrottleLevel.THROTTLED)

        context.coordinator.startBatchThumbnailSweep(
            file = context.zip,
            entryNames = context.names,
            targetSizePx = 360,
            password = null,
            thumbnailDiskCache = context.thumbCache
        )

        for (name in context.names) {
            assertEquals("热节流下不应生成 $name", null, context.thumbCache.get(context.zip, name, 360, password = null))
        }
    }

    @Test
    fun `批量扫描让路前台交互请求不互锁`() = runBlocking {
        val context = buildSweepContext(entryCount = 8, withExtraEntry = true)
        val sweepJob = launch {
            context.coordinator.startBatchThumbnailSweep(
                file = context.zip,
                entryNames = context.names,
                targetSizePx = 360,
                password = null,
                thumbnailDiskCache = context.thumbCache
            )
        }

        // 等扫描真正开始产出（限时避免红灯时挂死）
        val deadline = System.currentTimeMillis() + 10_000
        while (context.thumbCache.get(context.zip, context.names.first(), 360, password = null) == null) {
            if (System.currentTimeMillis() > deadline) fail("批量扫描未在限时内产出首个缩略图")
            delay(10)
        }

        // 前台交互请求：扫描让路等待期间必须能直接完成，不得与扫描互锁
        val extracted = try {
            withTimeout(15_000) {
                context.coordinator.extractHighPriority(context.zip, "img/extra.jpg", password = null)
            }
        } catch (_: TimeoutCancellationException) {
            fail("前台交互请求被批量扫描互锁阻塞")
            null
        }
        assertNotNull(extracted)
        assertArrayEquals(entryBytes(8), extracted!!.readBytes())

        context.coordinator.resumeBackgroundSweep()
        sweepJob.join()

        for ((index, name) in context.names.withIndex()) {
            assertNotNull("恢复后应完成 $name", context.thumbCache.get(context.zip, name, 360, password = null))
        }
    }
}
