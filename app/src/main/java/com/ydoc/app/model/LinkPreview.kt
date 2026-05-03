package com.ydoc.app.model

import kotlinx.serialization.Serializable

/**
 * 笔记里识别到的链接 + 拉回来的预览信息。
 * 一条 note 可能有多个 URL，所以笔记存 `List<LinkPreview>`。
 * fetchedAt=0 表示还没被 LinkPreviewWorker 抓过。
 * error != null 表示抓取失败（比如 404、超时、被墙）；UI 降级成 chip 提示，但仍可点开原 URL。
 */
@Serializable
data class LinkPreview(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val siteName: String = "",
    val summary: String = "",
    val fetchedAt: Long = 0,
    val error: String? = null,
)
