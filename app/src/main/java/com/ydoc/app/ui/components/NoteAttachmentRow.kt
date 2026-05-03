package com.ydoc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ydoc.app.model.NoteAttachment
import java.io.File

/**
 * 笔记附件的横向缩略图行。
 * - 缩略图优先用 `thumbPath`，没有就回退 `localPath`（原图），最后回退 `remoteUrl`。
 * - 点击缩略图 => onOpen(attachment)：由调用方决定是全屏预览还是跳系统看图。
 * - 点击 × => onRemove(attachment)：从笔记里移除（不删本地原图，保持可恢复）。
 * - AI 分析中（没 ocr 且没 desc 且没 error）显示转圈指示。
 *
 * `showRemove` 控制 × 按钮是否出现——编辑态显示，查看态隐藏。
 */
@Composable
fun NoteAttachmentRow(
    attachments: List<NoteAttachment>,
    onOpen: (NoteAttachment) -> Unit,
    onRemove: ((NoteAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    var expandedForOcr by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attachments.forEach { att ->
                AttachmentThumb(
                    attachment = att,
                    onClick = { onOpen(att) },
                    onLongClick = { expandedForOcr = if (expandedForOcr == att.id) null else att.id },
                    onRemove = onRemove?.let { remove -> { remove(att) } },
                )
            }
        }

        // 长按后展开的 OCR / AI 描述面板
        attachments.firstOrNull { it.id == expandedForOcr }?.let { att ->
            AttachmentContextPanel(attachment = att, onClose = { expandedForOcr = null })
        }
    }
}

@Composable
private fun AttachmentThumb(
    attachment: NoteAttachment,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val model = attachment.thumbPath?.let { File(it) }
        ?: attachment.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: attachment.remoteUrl

    // 只看 analyzedAt：Worker 跑完（成功 / 永久失败 / relay 未配 / 本地文件丢失）都会填时间戳。
    // 之前 "ocr空 AND desc空 AND error null" 的条件在"图里没文字 + vision 返回空 description"时
    // 永远为 true，导致缩略图右下角永久转圈。
    val analyzing = attachment.analyzedAt == 0L && attachment.analyzeError == null

    Box(modifier = Modifier.size(96.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .size(96.dp)
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
        if (analyzing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(999.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        // 展示 OCR/AI 文字的长按提示通过点击图下方按钮触发；为了简化这里用"…"按钮替代长按手势
        IconButton(
            onClick = onLongClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(24.dp),
        ) {
            Text(
                "i",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentContextPanel(
    attachment: NoteAttachment,
    onClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "附件识别",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "收起", modifier = Modifier.size(14.dp))
                }
            }
            if (attachment.aiDescription.isNotBlank()) {
                Text("AI 描述", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    attachment.aiDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (attachment.ocrText.isNotBlank()) {
                Text("OCR 文字", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    attachment.ocrText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            attachment.analyzeError?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    "识别失败：${err.take(120)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (attachment.aiDescription.isBlank() &&
                attachment.ocrText.isBlank() &&
                attachment.analyzeError == null
            ) {
                Text(
                    "正在识别…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
