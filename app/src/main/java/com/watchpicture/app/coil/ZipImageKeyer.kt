package com.watchpicture.app.coil

import coil3.key.Keyer
import coil3.request.Options

/**
 * Keyer for ZipImageSource to enable reliable Coil 3 memory caching.
 */
class ZipImageKeyer : Keyer<ZipImageSource> {
    override fun key(data: ZipImageSource, options: Options): String {
        return "zip://${data.zipFile.absolutePath}#${data.entryName}"
    }
}
