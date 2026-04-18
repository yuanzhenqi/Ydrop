package com.ydoc.app.config

/**
 * 首次启动时用于填充 Settings 的默认占位值。
 *
 * 真正的连接信息请在「设置 → 同步 / 转写」里填入，不要硬编码到这里后提交。
 * 若需要在本机定制默认值，改动后请勿 commit（可将本文件加入本地 .git/info/exclude）。
 */
object DefaultConfig {
    const val RELAY_BASE_URL = ""
    const val RELAY_TOKEN = ""
    const val VOLC_APP_ID = ""
    const val VOLC_ACCESS_TOKEN = ""
    const val VOLC_RESOURCE_ID = "volc.bigasr.auc"
}
