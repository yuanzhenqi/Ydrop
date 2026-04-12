package com.ydoc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ydoc.app.model.AudioPlaybackUiState
import java.util.Locale

/**
 * Ydrop 通用 UI 组件。
 *
 * 从 YDocApp.kt 抽出的高频复用原子组件，所有组件都跟 Material3 Expressive 风格对齐。
 * 同包调用用 `internal` 暴露，下一轮按功能拆分后再考虑是否进一步组织。
 */

// ─────────────────────────────────────────────────────────────
// SegmentedPillGroup — 分段切换（设置页 tab、优先级选择等）
// ─────────────────────────────────────────────────────────────
@Composable
internal fun <T> SegmentedPillGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// CompactActionIcon — 紧凑的圆角图标按钮（便签卡片的快捷动作）
// ─────────────────────────────────────────────────────────────
@Composable
internal fun CompactActionIcon(
    icon: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.45f),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// AudioPlaybackBar — 语音卡片的进度条 + 时间显示
// ─────────────────────────────────────────────────────────────
@Composable
internal fun AudioPlaybackBar(
    playback: AudioPlaybackUiState,
    accent: Color,
    onSeekAudio: (Long) -> Unit,
) {
    var isDragging by remember(playback.currentNoteId) { mutableStateOf(false) }
    var sliderValue by remember(playback.currentNoteId) { mutableFloatStateOf(0f) }
    val durationMs = playback.durationMs.coerceAtLeast(1L)
    val progress = (playback.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    LaunchedEffect(
        playback.currentNoteId,
        playback.positionMs,
        playback.durationMs,
        playback.isPlaying,
        playback.isBuffering,
    ) {
        if (!isDragging) {
            sliderValue = progress
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = if (isDragging) sliderValue else progress,
            onValueChange = {
                isDragging = true
                sliderValue = it
            },
            onValueChangeFinished = {
                onSeekAudio((sliderValue * durationMs).toLong())
                isDragging = false
            },
            enabled = playback.canSeek,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDuration(playback.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(playback.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SettingsSectionHeader — 设置分组头（标题 + 描述 + 开关）
// ─────────────────────────────────────────────────────────────
@Composable
internal fun SettingsSectionHeader(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.width(56.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SettingsToggleRow — 设置内单行开关（AI 自动整理等）
// ─────────────────────────────────────────────────────────────
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier.width(56.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// StatusPill — 便签卡片上的分类 / 优先级标签
// ─────────────────────────────────────────────────────────────
@Composable
internal fun StatusPill(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// formatDuration — mm:ss 格式化（语音时长）
// ─────────────────────────────────────────────────────────────
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
