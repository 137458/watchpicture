package com.watchpicture.app.coil

import coil3.key.Keyer
import coil3.request.Options

/**
 * Keyer for ZipImageSource to enable reliable Coil 3 memory caching.
 */
class ZipImageKeyer : Keyer<ZipImageSource> {
    override fun key(data: ZipImageSource, options: Options): String {
        val pwdHash = data.password?.hashCode()?.toString(16) ?: "none"
        val thumbSuffix = if (data.isThumbnail) "#thumb#sz=${data.targetSizePx}" else "#full"
        // Include the archive's file version (lastModified) so that replacing the archive on disk
        // invalidates stale memory-cache entries, matching the disk-cache key's versioning.
        return "zip://${data.zipFile.absolutePath}#mod=${data.zipFile.lastModified()}#${data.entryName}#pwd=$pwdHash$thumbSuffix"
    }
}
