package com.watchpicture.app.model

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Representation of an individual image item within a pack.
 */
@Serializable
data class PackImage(
    val packId: String,
    val entryPath: String,
    val displayName: String,
    val isEncrypted: Boolean = false,
    val fileUri: String? = null,
    val directFilePath: String? = null
)
