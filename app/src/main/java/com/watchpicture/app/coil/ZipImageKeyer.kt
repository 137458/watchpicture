package com.watchpicture.app.coil

import coil3.key.Keyer
import coil3.request.Options

/**
 * Keyer for ZipImageSource to enable reliable Coil 3 memory caching.
 */
class ZipImageKeyer : Keyer<ZipImageSource> {
    override fun key(data: ZipImageSource, options: Options): String {
        // 与 ZipImageFetcher 同口径:data 未携带密码时回填会话密码,否则同一解密
        // 结果会以 pwd=none 与 pwd=实际键占据两个内存缓存槽
        val effectivePassword = data.password
            ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore.get(data.zipFile.absolutePath) }.getOrNull()
        return keyOf(data, effectivePassword)
    }

    /** 纯键计算,密码口径由调用方决定(便于单测)。 */
    internal fun keyOf(data: ZipImageSource, effectivePassword: String?): String {
        val pwdHash = effectivePassword?.hashCode()?.toString(16) ?: "none"
        val thumbSuffix = if (data.isThumbnail) "#thumb#sz=${data.targetSizePx}" else "#full"
        // Include the archive's file version (lastModified) so that replacing the archive on disk
        // invalidates stale memory-cache entries, matching the disk-cache key's versioning.
        return "zip://${data.zipFile.absolutePath}#mod=${data.zipFile.lastModified()}#${data.entryName}#pwd=$pwdHash$thumbSuffix"
    }
}
