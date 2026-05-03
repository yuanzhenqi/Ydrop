package com.ydoc.app.model

import kotlinx.serialization.Serializable

/**
 * 笔记附件。目前只支持图片（type=IMAGE），后续可扩 FILE。
 *
 * OCR + Vision AI 两路识别的结果都留在这里：
 * - `ocrText`：本地 ML Kit 识别的纯文本，离线就能有；
 * - `aiDescription`：relay vision 返回的自然语言描述；
 * - `aiStructuredJson`：vision 返回的结构化字段（keywords/actionable_items/dates），用 JSON 字符串存，避免再套一层 kotlinx.serialization schema。
 *
 * `remoteUrl` 是 relay 存完图后返回的静态 URL，可选——用于云端再调用或跨端展示。
 */
@Serializable
data class NoteAttachment(
    val id: String,
    val type: String = "IMAGE",
    val localPath: String,
    val publicUri: String? = null,
    val remoteUrl: String? = null,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val thumbPath: String? = null,
    val ocrText: String = "",
    val aiDescription: String = "",
    val aiStructuredJson: String = "{}",
    val analyzeError: String? = null,
    val createdAt: Long = 0,
    /**
     * Vision 分析结果的"完成时间戳"。无论成功、永久失败，还是 relay 未配置的 early return，
     * 只要 ImageAnalyzeWorker 跑完一次就填当前时间。
     * UI 用它判断"还在转圈"——避免之前 ocr/desc/error 三件都空时永久转圈的 bug。
     * 旧 JSON 里没这个字段默认为 0，等下次 Worker 跑过会补上。
     */
    val analyzedAt: Long = 0,
)
