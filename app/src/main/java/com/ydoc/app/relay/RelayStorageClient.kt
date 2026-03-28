package com.ydoc.app.relay

import com.ydoc.app.model.RelayConfig
import java.io.File

interface RelayStorageClient {
    suspend fun upload(file: File, config: RelayConfig): RelayUploadResult
    suspend fun delete(fileId: String, config: RelayConfig)
    suspend fun test(config: RelayConfig)
}

data class RelayUploadResult(
    val fileId: String,
    val url: String,
    val expiresAt: Long?,
)
