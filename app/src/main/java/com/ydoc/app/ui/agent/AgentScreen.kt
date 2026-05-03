package com.ydoc.app.ui.agent

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ydoc.app.appContainer
import com.ydoc.app.model.AgentMessage
import com.ydoc.app.model.AgentRole
import com.ydoc.app.model.AgentSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val application = context.applicationContext as Application
    val vm: AgentViewModel = viewModel(factory = AgentViewModel.factory(container, application))
    val state by vm.state.collectAsStateWithLifecycle()

    var sessionSheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()

    BackHandler { onBack() }

    val currentTitle = state.sessions
        .firstOrNull { it.id == state.currentSessionId }
        ?.title
        ?.ifBlank { "新会话" }
        ?: "助手"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = state.sessions.isNotEmpty()) { sessionSheetOpen = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentTitle,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.sessions.isNotEmpty()) {
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.createSession() }) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建会话")
                    }
                },
            )
        },
        // 不要 bottomBar —— Scaffold 本身会处理 IME insets，再叠 imePadding 会双层补偿把输入框推飞。
        // 把输入区直接放在 content 的 Column 最底下，配合 weight(1f) 的消息列表，就能避开这个冲突。
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                !state.aiConfigured -> UnconfiguredBanner()
                state.sessions.isEmpty() -> EmptyStatePanel(onCreateSession = vm::createSession)
                else -> MessageList(
                    messages = state.messages,
                    sending = state.sending,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            if (state.aiConfigured) {
                // imePadding 放在输入栏自己的 modifier 链上，而不是外层 Column——
                // 在 Column 外层叠加 imePadding 会让上方 weight(1f) 的消息列表一起被推，
                // 视觉上看起来"输入框跳到了很上方"。只让输入栏自己响应 IME。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    state.error?.let { err ->
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    AgentInputBar(
                        enabled = !state.sending,
                        onSend = vm::send,
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (sessionSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { sessionSheetOpen = false },
                sheetState = sheetState,
            ) {
                SessionSheetContent(
                    sessions = state.sessions,
                    currentSessionId = state.currentSessionId,
                    onSwitchSession = {
                        vm.switchSession(it)
                        sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                            sessionSheetOpen = false
                        }
                    },
                    onRenameSession = vm::renameSession,
                    onDeleteSession = vm::deleteSession,
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<AgentMessage>,
    sending: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { MessageBubble(message = it) }
        if (sending) {
            item { TypingIndicator() }
        }
    }
}

@Composable
private fun MessageBubble(message: AgentMessage) {
    when (message.role) {
        AgentRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        AgentRole.ASSISTANT -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    .padding(top = 6.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                if (message.pending) {
                    TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        text = message.content.ifBlank { "（空回复）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                message.providerError?.takeIf { it.isNotBlank() }?.let { err ->
                    Text(
                        "provider 错误：$err",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        AgentRole.ERROR -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        AgentRole.SYSTEM -> Unit
    }
}

@Composable
private fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.Start) {
        TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TypingDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(12.dp),
            color = color,
        )
        Text("思考中…", color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AgentInputBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问问你的笔记…", style = MaterialTheme.typography.bodyMedium) },
                maxLines = 5,
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            FilledIconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyStatePanel(onCreateSession: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("和你的笔记聊聊", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "问最近写了什么，让助手帮你找回忘记的事。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreateSession, shape = RoundedCornerShape(20.dp)) {
            Text("新建会话")
        }
    }
}

@Composable
private fun UnconfiguredBanner() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("助手还没配置", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "请在设置里启用 AI 并填写中转地址和令牌。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionSheetContent(
    sessions: List<AgentSession>,
    currentSessionId: String?,
    onSwitchSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var pendingRename by remember { mutableStateOf<AgentSession?>(null) }
    var pendingDelete by remember { mutableStateOf<AgentSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            "会话历史",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    isCurrent = session.id == currentSessionId,
                    onSwitch = { onSwitchSession(session.id) },
                    onRename = { pendingRename = session },
                    onDelete = { pendingDelete = session },
                )
            }
        }
    }

    pendingRename?.let { session ->
        var newTitle by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                TextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(session.id, newTitle)
                    pendingRename = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = { Text("「${session.title.ifBlank { "未命名会话" }}」会从本地和云端一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: AgentSession,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSwitch)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            session.lastMessagePreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
        }
    }
}
