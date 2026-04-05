package com.ydoc.app.ai

import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiAnalyzeResponse
import com.ydoc.app.model.AiConfig

interface AiClient {
    suspend fun analyze(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse
    suspend fun test(config: AiConfig)
}
