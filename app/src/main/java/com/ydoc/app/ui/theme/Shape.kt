package com.ydoc.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Ydrop 圆角系统 — Material3 Expressive 方向。
 *
 * Expressive 比 Baseline 圆角更大胆，整体往"柔和、亲切"的感觉走：
 * - extraSmall 4dp：Chip、Badge、小 Tag
 * - small 12dp：按钮、输入框、提示卡
 * - medium 20dp：便签卡、设置行、对话框容器
 * - large 28dp：主屏大卡片（HeroCaptureCard、快速记录卡）
 * - extraLarge 36dp：全屏 Sheet、进度/空态插画容器
 */
val YdropShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
