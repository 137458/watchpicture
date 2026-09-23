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
    passwordStore: com.watchpicture.app.security.SessionPasswordStore? = null,
    isThumbnail: Boolean = false,
    targetSizePx: Int = 360
): Any? {
    val store = passwordStore
        ?: runCatching { com.watchpicture.app.WatchPictureApp.instance.sessionPasswordStore }.getOrNull()
    // Only the pack's own canonical password participates here. The global
    // lastUsedPassword fallback is deliberately excluded: it may belong to another
    // pack and would pollute cache keys (ZipImageKeyer / ArchiveDiskCache.computeKey).
    // It is used solely by PackViewModel's auto-unlock, which stores the verified
    // password under the pack id before this path is reached.
    val resolvedPassword = sessionPassword
        ?: store?.get(packId)
        ?: directFilePath?.let { store?.get(it) }

    val direct = directFilePath?.let { File(it) }
    if (direct != null && direct.exists()) {
        return when {
            direct.isDirectory -> {
                if (isThumbnail) {
                    ZipImageSource(
                        zipFile = direct,
                        entryName = entryPath,
                        password = null,
                        isThumbnail = true,
                        targetSizePx = targetSizePx
                    )
                } else {
                    File(direct, entryPath)
                }
            }
            ZipArchiveManager.isImageFile(direct.name) -> direct
            else -> ZipImageSource(
                zipFile = direct,
                entryName = entryPath,
                password = resolvedPassword,
                isThumbnail = isThumbnail,
                targetSizePx = targetSizePx
            )
        }
    }
    return fileUri?.let { Uri.parse(it) }
}
