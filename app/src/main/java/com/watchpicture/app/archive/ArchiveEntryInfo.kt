package com.watchpicture.app.archive

data class ArchiveEntryInfo(
    val name: String,
    val uncompressedSize: Long = 0L,
    val isEncrypted: Boolean = false
)
