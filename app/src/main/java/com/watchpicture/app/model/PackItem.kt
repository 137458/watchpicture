package com.watchpicture.app.model

import kotlinx.serialization.Serializable

/**
 * Unified representation of a picture pack, which can be either a Directory or a ZIP/CBZ archive.
 */
@Serializable
sealed interface PackItem {
    val id: String
    val name: String
    val uriString: String
    val directPath: String?
    val itemCount: Int
    val isEncrypted: Boolean
    val fileSize: Long
    val lastModified: Long
    val coverImage: PackImage?
    val formatLabel: String
}

@Serializable
data class DirectoryPack(
    override val id: String,
    override val name: String,
    override val uriString: String,
    override val directPath: String?,
    override val itemCount: Int,
    override val isEncrypted: Boolean = false,
    override val fileSize: Long = 0L,
    override val lastModified: Long = 0L,
    override val coverImage: PackImage? = null
) : PackItem {
    override val formatLabel: String = "DIR"
}

@Serializable
data class ZipPack(
    override val id: String,
    override val name: String,
    override val uriString: String,
    override val directPath: String?,
    override val itemCount: Int,
    override val isEncrypted: Boolean,
    override val fileSize: Long = 0L,
    override val lastModified: Long = 0L,
    override val coverImage: PackImage? = null,
    val isCbz: Boolean = false
) : PackItem {
    override val formatLabel: String = if (isCbz) "CBZ" else "ZIP"
}
