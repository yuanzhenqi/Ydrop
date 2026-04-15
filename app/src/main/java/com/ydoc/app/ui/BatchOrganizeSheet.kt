package com.ydoc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ydoc.app.model.ClusterSuggestion
import com.ydoc.app.ui.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchOrganizeSheet(
    uiState: BatchOrganizeUiState,
    onDismiss: () -> Unit,
    onReanalyze: () -> Unit,
    onApplyCluster: (ClusterSuggestion) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("批量整理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReanalyze, enabled = !uiState.loading) {
                    Text(if (uiState.loading) "分析中..." else "重新分析")
                }
            }

            // 提示信息
            if (uiState.totalAnalyzed > 0 && !uiState.loading) {
                Text(
                    "AI 分析了 ${uiState.totalAnalyzed} 条笔记，识别出 ${uiState.clusters.size} 组建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Loading / Error / Empty / Content
            when {
                uiState.loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(32.dp))
                        Text("AI 正在分析你的笔记...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                uiState.error != null -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "分析失败：${uiState.error}",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                uiState.clusters.isEmpty() -> {
                    Text(
                        "暂无整理建议",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(uiState.clusters, key = { it.cluster_id }) { cluster ->
                            ClusterCard(
                                cluster = cluster,
                                applied = cluster.cluster_id in uiState.appliedClusterIds,
                                applying = cluster.cluster_id in uiState.applyingClusterIds,
                                onApply = { onApplyCluster(cluster) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterCard(
    cluster: ClusterSuggestion,
    applied: Boolean,
    applying: Boolean,
    onApply: () -> Unit,
) {
    val actionLabel = when (cluster.suggested_action) {
        "merge" -> "合并"
        "convert_to_task" -> "转任务"
        "keep" -> "保持独立"
        else -> cluster.suggested_action
    }
    val actionColor = when (cluster.suggested_action) {
        "merge" -> MaterialTheme.colorScheme.tertiaryContainer
        "convert_to_task" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    cluster.theme,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(label = actionLabel, color = actionColor)
            }
            if (cluster.reason.isNotBlank()) {
                Text(
                    cluster.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cluster.suggested_title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "建议标题：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "涉及 ${cluster.note_ids.size} 条笔记",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cluster.suggested_action != "keep") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        applied -> AssistChip(
                            onClick = {},
                            label = { Text("已应用", style = MaterialTheme.typography.labelSmall) },
                            enabled = false,
                        )
                        applying -> AssistChip(
                            onClick = {},
                            label = { Text("应用中...", style = MaterialTheme.typography.labelSmall) },
                            enabled = false,
                        )
                        else -> Button(
                            onClick = onApply,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text("应用", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
