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
        return "zip://${data.zipFile.absolutePath}#${data.entryName}#pwd=$pwdHash$thumbSuffix"
    }
}
