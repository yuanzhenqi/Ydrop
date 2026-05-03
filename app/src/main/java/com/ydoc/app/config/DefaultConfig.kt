package com.ydoc.app.config

/**
 * 首次启动时用于填充 Settings 的默认占位值。
 *
 * 真正的连接信息请在「设置 → 同步 / 转写 / AI」里填入，不要硬编码到这里后提交。
 * 若需要在本机定制默认值，改动后请勿 commit（可将本文件加入本地 .git/info/exclude，
 * 或执行 `git update-index --skip-worktree app/src/main/java/com/ydoc/app/config/DefaultConfig.kt`）。
 *
 * 各字段非空时的副作用：
 * - RELAY_BASE_URL + RELAY_TOKEN 都非空 → 首次启动 RelayConfig.enabled = true
 * - VOLC_APP_ID + VOLC_ACCESS_TOKEN 都非空 → 首次启动 VolcengineConfig.enabled = true
 * - WEBDAV_BASE_URL 非空 → 首次安装自动把 WebDAV 目标置为 enabled = true
 * - AI_BASE_URL 非空 → AI 默认 enabled = true
 */
object DefaultConfig {
    // ── 中转服务 ──
    const val RELAY_BASE_URL = ""
    const val RELAY_TOKEN = ""

    // ── 火山 / 豆包转写 ──
    const val VOLC_APP_ID = ""
    const val VOLC_ACCESS_TOKEN = ""
    const val VOLC_RESOURCE_ID = "volc.bigasr.auc"

    // ── WebDAV 同步 ──
    const val WEBDAV_BASE_URL = ""
    const val WEBDAV_USERNAME = ""
    const val WEBDAV_PASSWORD = ""
    const val WEBDAV_FOLDER = ""

    // ── AI 整理 ──
    const val AI_BASE_URL = ""
    const val AI_TOKEN = ""
    const val AI_MODEL = ""
}
