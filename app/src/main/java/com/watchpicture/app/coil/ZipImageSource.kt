package com.watchpicture.app.coil

import java.io.File

/**
 * Custom data source model for Coil 3 to identify and fetch an image entry directly from a ZIP file.
 */
data class ZipImageSource(
    val zipFile: File,
    val entryName: String,
    val password: String? = null,
    val isThumbnail: Boolean = false,
    val targetSizePx: Int = 360
)
