package com.ydoc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ydoc.app.model.LinkPreview

/**
 * 链接预览卡片。失败态（error != null 或 title/description 都空）降级成 chip 样式，
 * 但仍然可点——用户至少能打开原 URL。
 *
 * 排版原则：把图片留作最上方的可选 banner，下面的文字段保证高度稳定，
 * 卡片整体有弱边框 + 轻圆角，和主笔记卡的视觉分层区分开，不喧宾夺主。
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasAnyText = preview.title.isNotBlank() ||
        preview.summary.isNotBlank() ||
        preview.description.isNotBlank()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (preview.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(4.dp))
            }

            // 标题：没有 og:title 就退到 URL 本身，至少有东西可显示。
            Text(
                text = preview.title.ifBlank { preview.url },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val body = preview.summary.ifBlank { preview.description }
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }

            Text(
                text = preview.siteName.ifBlank { preview.url },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )

            preview.error?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    text = "预览失败：${err.take(80)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!hasAnyText && preview.error == null && preview.fetchedAt == 0L) {
                Text(
                    text = "正在抓取预览…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
