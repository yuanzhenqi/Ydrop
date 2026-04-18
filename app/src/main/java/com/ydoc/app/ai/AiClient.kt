package com.ydoc.app.ai

import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiAnalyzeResponse
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.BatchOrganizeRequest
import com.ydoc.app.model.BatchOrganizeResponse

interface AiClient {
    suspend fun analyze(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse
    suspend fun test(config: AiConfig)

    /**
     * 批量整理：把本地笔记列表作为输入，在 Android 端按 endpointMode 直接调 AI provider
     * （OpenAI / Anthropic / Relay 自定义 `/ai/analyze-note`），不依赖 relay_service 特殊端点。
     */
    suspend fun batchOrganize(request: BatchOrganizeRequest, config: AiConfig): BatchOrganizeResponse
}
