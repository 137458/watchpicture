package com.watchpicture.app.model

import android.net.Uri
import com.watchpicture.app.archive.ZipArchiveManager
import com.watchpicture.app.coil.ZipImageSource
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

/**
 * Resolves the appropriate Coil image model (File, ZipImageSource, or Uri)
 * for thumbnail preview and fullscreen viewing.
 */
fun PackImage.toImageModel(
    sessionPassword: String? = null,
    passwordStore: com.watchpicture.app.security.SessionPasswordStore? = null
): Any? {
    val store = passwordStore
        ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore }.getOrNull()
    val resolvedPassword = sessionPassword
        ?: store?.get(packId)
        ?: directFilePath?.let { store?.get(it) }
        ?: store?.lastUsedPassword

    val direct = directFilePath?.let { File(it) }
    if (direct != null && direct.exists()) {
        return when {
            direct.isDirectory -> File(direct, entryPath)
            ZipArchiveManager.isImageFile(direct.name) -> direct
            else -> ZipImageSource(
                zipFile = direct,
                entryName = entryPath,
                password = resolvedPassword
            )
        }
    }
    return fileUri?.let { Uri.parse(it) }
}
