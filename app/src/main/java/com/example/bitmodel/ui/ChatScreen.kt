// yxpil · BIT Mobile
// 对话页：设备独立会话 remote-<随机> 调 POST /api/chat（Agent 工具循环 + 审核在桌面端）。
// 等待回复期间轮询审批表（GET /api/approvals）弹出工具审批卡片（POST /api/approvals/{id}）。
package com.example.bitmodel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bitmodel.bit.Approval
import com.example.bitmodel.bit.BitClient
import com.example.bitmodel.bit.BitStore
import com.example.bitmodel.bit.ChatEntry
import com.example.bitmodel.bit.Connection
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    store: BitStore,
    conn: Connection?,
    client: BitClient,
    goScan: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val history = remember { mutableStateListOf<ChatEntry>().apply { addAll(store.chatHistory()) } }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var approvals by remember { mutableStateOf<List<Approval>>(emptyList()) }
    val listState = rememberLazyListState()

    // 新消息到达 / 回复完成 → 滚到底部
    LaunchedEffect(history.size, sending) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1 + if (sending) 1 else 0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .imePadding(),
    ) {
        Spacer(Modifier.height(4.dp))
        // 连接状态条（实心胶囊条，非透明）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(ok = conn != null && !sending)
            Spacer(Modifier.size(10.dp))
            Text(
                when {
                    conn == null -> "未连接"
                    sending -> "桌面端处理中…"
                    else -> "${conn.method} · ${conn.baseUrl.removePrefix("http://").removePrefix("https://")}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (conn == null) {
            EmptyChat(Modifier.weight(1f), goScan)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history) { e -> MessageBubble(e) }
                if (sending) item { TypingBubble() }
                approvals.forEach { ap ->
                    item(key = ap.id) {
                        ApprovalCard(ap) { allow ->
                            scope.launch {
                                client.answerApproval(conn, ap.id, allow)
                                approvals = approvals.filterNot { it.id == ap.id }
                            }
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息给 BIT…") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    enabled = !sending,
                )
                PillButton(
                    "发送",
                    enabled = !sending && input.isNotBlank(),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    val text = input.trim()
                    if (text.isNotEmpty() && !sending) {
                        input = ""
                        error = null
                        sending = true
                        val userEntry = ChatEntry("user", text, System.currentTimeMillis())
                        history.add(userEntry)
                        store.appendChat(userEntry)
                        scope.launch {
                            val outcome = client.chat(conn, store.sessionId(), text) { list ->
                                approvals = list
                            }
                            sending = false
                            approvals = emptyList()
                            if (outcome.error != null) {
                                error = outcome.error
                                // 失败时消息退回输入框，避免内容丢失
                                input = text
                                history.remove(userEntry)
                            } else {
                                val replyEntry = ChatEntry("assistant", outcome.reply, System.currentTimeMillis())
                                history.add(replyEntry)
                                store.appendChat(replyEntry)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, goScan: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有配对桌面端", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "扫码配对后即可在此与 BIT 对话（Agent 工具循环在桌面端执行）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PillButton("去扫码配对") { goScan() }
    }
}

/// 消息气泡：用户=强调色实心（右），助手=卡片面（左）。胶囊圆角、非透明
@Composable
private fun MessageBubble(e: ChatEntry) {
    val isUser = e.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(
                        topStart = 24.dp, topEnd = 24.dp,
                        bottomStart = if (isUser) 24.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 24.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                e.text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/// 打字指示：三枚实心圆点
@Composable
private fun TypingBubble() {
    Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                }
            }
        }
    }
}

/// 工具审批卡片（桌面端 Agent 请求执行工具，等待本设备或桌面端应答）
@Composable
private fun ApprovalCard(ap: Approval, onAnswer: (Boolean) -> Unit = {}) {
    var answered by remember(ap.id) { mutableStateOf<Boolean?>(null) }
    BitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = null)
            Spacer(Modifier.size(8.dp))
            Text("工具审批", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            TagChip(ap.tool)
        }
        Text(
            ap.params,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (answered == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("允许") {
                    answered = true
                    onAnswer(true)
                }
                PillOutline("拒绝") {
                    answered = false
                    onAnswer(false)
                }
            }
        } else {
            Text(
                if (answered == true) "已允许，等待桌面端执行…" else "已拒绝",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
