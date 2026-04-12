package com.ydoc.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ydrop 间距系统 — 4dp 为基础单位，对齐 Material3 的 8dp 网格。
 *
 * 使用方式：
 *   val s = LocalYdropSpacing.current
 *   Modifier.padding(horizontal = s.lg, vertical = s.md)
 *
 * 命名：
 * - xxs 2dp  极小缝隙（分隔线周围）
 * - xs  4dp  图标与文字之间
 * - sm  8dp  紧凑列表行内距
 * - md  12dp 常规卡片内距
 * - lg  16dp 主屏左右外边距
 * - xl  20dp 卡片之间
 * - xxl 24dp 章节之间
 * - xxxl 32dp 大区块分隔
 */
@Immutable
data class YdropSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
)

val LocalYdropSpacing = compositionLocalOf { YdropSpacing() }
