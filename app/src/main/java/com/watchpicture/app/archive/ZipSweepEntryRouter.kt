package com.watchpicture.app.archive

/**
 * ZIP 批量扫描的单条目取数路由。
 *
 * 直通路径只接受 STORED 条目：mmap 切片是只读零拷贝视图，整包扫描零堆增长；
 * DEFLATE 条目的解压缓冲由 native 引擎持有到 [NativeZipSession.close]，整包直通
 * 会无界增长，因此保持池化 java.util.zip 流式路径。
 */
internal sealed interface ZipSweepRoute {
    /** STORED 且已命中：走扫描自持会话的 [NativeZipSession.extractToDirectBuffer]。 */
    data class NativeDirectBuffer(val fileIndex: Int) : ZipSweepRoute

    /** 其余情况：走池化 java.util.zip 条目流。 */
    data object PooledStream : ZipSweepRoute
}

internal object ZipSweepEntryRouter {

    fun route(session: NativeZipSession?, entryName: String): ZipSweepRoute {
        session ?: return ZipSweepRoute.PooledStream
        return route(session.findEntry(entryName))
    }

    internal fun route(entry: NativeZipEntry?): ZipSweepRoute {
        // NativeZipEntry 不暴露压缩方式字段：size == compressedSize > 0 即视作 STORED。
        // 加密条目（AES/ZipCrypto）必然引入额外头部字节，size 恒不相等，天然落入流式路径。
        if (entry == null || entry.isDirectory) return ZipSweepRoute.PooledStream
        return if (entry.size > 0L && entry.size == entry.compressedSize) {
            ZipSweepRoute.NativeDirectBuffer(entry.index)
        } else {
            ZipSweepRoute.PooledStream
        }
    }
}
